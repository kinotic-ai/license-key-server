package org.kinotic.licenses.internal.api.rest;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.CheckoutKeyStatus;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.security.ParticipantType;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.api.model.SiteType;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.CustomerService;
import org.kinotic.licenses.internal.api.services.FreeLicenseService;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.internal.api.services.LicenseSigningKey;
import org.kinotic.licenses.internal.api.services.RateLimitedException;
import org.kinotic.licenses.internal.api.services.SlidingWindowRateLimiter;
import org.kinotic.licenses.internal.api.services.StripeBillingService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The routes anyone may call: the plans and terms the site shows, the signing key verifiers trust, the purchase
 * and free-request forms, and the success page's one look at a freshly bought key.
 */
@Component
@RequiredArgsConstructor
public class PublicRoutes implements SuppliesGatewayRoutes {

    /** How long after a license is created its success page may still show the key. */
    static final Duration KEY_SHOW_WINDOW = Duration.ofHours(1);

    private static final int SIGN_UPS_PER_WINDOW = 20;
    private static final Duration SIGN_UP_WINDOW = Duration.ofMinutes(15);
    private static final String TOO_MANY_SIGN_UPS = "Too many sign-ups from this address; try again later";

    private final KinoticLicensesProperties properties;
    private final LicenseSigningKey signingKey;
    private final StripeBillingService billingService;
    private final CustomerService customerService;
    private final FreeLicenseService freeLicenseService;
    private final LicenseRepository licenseRepository;
    private final CustomerRepository customerRepository;
    private final LicenseService licenseService;
    private final JsonMapper jsonMapper;

    // the Checkout Sessions whose key the success page has already been shown; this node is the whole cluster
    private final Set<String> shownKeys = ConcurrentHashMap.newKeySet();
    // anonymous sign-ups per client address, since each one says whether its email already has an account
    private final SlidingWindowRateLimiter signUpsPerAddress = new SlidingWindowRateLimiter(SIGN_UPS_PER_WINDOW, SIGN_UP_WINDOW);

    @Override
    public void mountRoutes(Router router) {
        router.get("/api/site").handler(this::site);
        router.get("/api/signing-key").handler(this::signingKey);
        router.get("/api/plans").handler(this::plans);
        router.post("/api/checkout").handler(this::checkout);
        router.get("/api/checkout/:sessionId/key").handler(this::checkoutKey);
        router.post("/api/free-requests").handler(this::freeRequest);
    }

    /** Which site the request reached, so the one SPA shows the public pages or the staff dashboard. */
    private void site(RoutingContext ctx) {
        SiteType site = RestSupport.addressedTo(ctx, properties.getLicenses().getStaffBaseUrl()) ? SiteType.STAFF : SiteType.PUBLIC;
        RestSupport.respondJson(ctx, 200, new JsonObject().put("site", site.name()).put("termsVersion", AgreementTerms.VERSION));
    }

    /** The public half of the key license keys are signed with, as a JSON Web Key. */
    private void signingKey(RoutingContext ctx) {
        RestSupport.respondJson(ctx, 200, signingKey.publicJwk());
    }

    private void plans(RoutingContext ctx) {
        billingService.listPlans()
                      .onSuccess(plans -> RestSupport.respondJson(ctx, jsonMapper, plans))
                      .onFailure(ctx::fail);
    }

    /**
     * Step three of the purchase: records the acceptance and answers with the Checkout Session URL to send the browser
     * to. A signed-in customer buys for its own account, as its signed-in contact; anyone else names a new licensee
     * and its billing contact, which become a new customer.
     */
    private void checkout(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        Plan plan = plan(body);
        Validate.isTrue(plan != Plan.FREE, "The free plan is requested at /free, not bought");
        requireAcceptance(body);
        LicenseParticipant customer = signedInCustomer(ctx);
        Future<String> url;
        if (customer != null) {
            url = customerRepository.findById(customer.getCustomerId())
                                    .compose(buyer -> billingService.startCheckout(buyer, customer.getName(), customer.getEmail(), plan))
                                    .compose(started -> customerService.recordAcceptance(customer.getCustomerId(), customer.getName(),
                                                                                         customer.getEmail()).map(started));
        } else {
            String legalName = RestSupport.requiredString(body, "legalName");
            String contactName = RestSupport.requiredString(body, "contactName");
            String contactEmail = RestSupport.requiredString(body, "contactEmail");
            url = withinSignUpLimit(ctx)
                    .compose(v -> customerService.createCustomer(legalName, contactName, contactEmail))
                    .compose(buyer -> billingService.startCheckout(buyer, contactName, contactEmail, plan));
        }
        url.onSuccess(started -> RestSupport.respondJson(ctx, 200, new JsonObject().put("url", started)))
           .onFailure(t -> failSignUp(ctx, t));
    }

    /**
     * The success page's view of the key the Checkout Session bought: {@code PENDING} until the webhook has created
     * the license, then the key once, within {@link #KEY_SHOW_WINDOW} of its issue, and {@code SHOWN} after that.
     */
    private void checkoutKey(RoutingContext ctx) {
        String sessionId = ctx.pathParam("sessionId");
        licenseRepository.findByCheckoutSessionId(sessionId)
                         .compose(license -> license == null
                                 ? Future.succeededFuture(new JsonObject().put("status", CheckoutKeyStatus.PENDING.name()))
                                 : licenseService.keysOf(license.getId()).map(keys -> {
                                     // a license with no key yet is one whose payment is still clearing
                                     JsonObject ret = new JsonObject().put("status", keys.isEmpty()
                                             ? CheckoutKeyStatus.PENDING.name()
                                             : CheckoutKeyStatus.SHOWN.name());
                                     boolean withinWindow = license.getCreatedAt().plus(KEY_SHOW_WINDOW).isAfter(Instant.now());
                                     if (!keys.isEmpty() && withinWindow && shownKeys.add(sessionId)) {
                                         ret.put("status", CheckoutKeyStatus.READY.name())
                                            .put("key", keys.getFirst().getKey())
                                            .put("expiresAt", keys.getFirst().getExpiresAt().toString());
                                     }
                                     return ret;
                                 }))
                         .onSuccess(result -> RestSupport.respondJson(ctx, 200, result))
                         .onFailure(ctx::fail);
    }

    /**
     * A free license request, with the acceptance. A signed-in customer requests for its own account; anyone else names
     * a new organization and its contact.
     */
    private void freeRequest(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        requireAcceptance(body);
        FreeLicenseCertification certification = new FreeLicenseCertification()
                .setAnnualRevenue(amount(body, "annualRevenue"))
                .setCapitalRaised(amount(body, "capitalRaised"))
                .setIntendedUse(RestSupport.requiredString(body, "intendedUse"));
        LicenseParticipant customer = signedInCustomer(ctx);
        Future<FreeLicenseRequest> requested;
        if (customer != null) {
            requested = freeLicenseService.requestForCustomer(customer.getCustomerId(), certification)
                                          .compose(request -> customerService.recordAcceptance(customer.getCustomerId(), customer.getName(),
                                                                                               customer.getEmail()).map(request));
        } else {
            String organization = RestSupport.requiredString(body, "organization");
            String contactName = RestSupport.requiredString(body, "contactName");
            String contactEmail = RestSupport.requiredString(body, "contactEmail");
            requested = withinSignUpLimit(ctx).compose(v -> freeLicenseService.request(organization, contactName, contactEmail,
                                                                                       certification));
        }
        requested.onSuccess(request -> RestSupport.respondJson(ctx, 201, new JsonObject().put("id", request.getId())))
                 .onFailure(t -> failSignUp(ctx, t));
    }

    // the customer contact signed in on the page that sent the request; a staff session buys nothing
    private static LicenseParticipant signedInCustomer(RoutingContext ctx) {
        LicenseParticipant ret = RestSupport.signedIn(ctx);
        return ret != null && ret.getType() == ParticipantType.CUSTOMER ? ret : null;
    }

    private Future<Void> withinSignUpLimit(RoutingContext ctx) {
        return signUpsPerAddress.tryAcquire(RestSupport.clientAddress(ctx), Instant.now())
                ? Future.succeededFuture()
                : Future.failedFuture(new RateLimitedException(TOO_MANY_SIGN_UPS));
    }

    // an address that already has an account, a second subscription, or a second pending request is a conflict the
    // form explains, not a server error
    private static void failSignUp(RoutingContext ctx, Throwable t) {
        if (t instanceof AlreadyExistsException) {
            RestSupport.respondJson(ctx, 409, new JsonObject().put("error", t.getMessage()));
        } else if (t instanceof RateLimitedException) {
            RestSupport.respondJson(ctx, 429, new JsonObject().put("error", t.getMessage()));
        } else {
            ctx.fail(t);
        }
    }

    private static Plan plan(JsonObject body) {
        try {
            return Plan.valueOf(RestSupport.requiredString(body, "plan").toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown plan " + body.getValue("plan"), e);
        }
    }

    // the click-through is the agreement, so nothing proceeds without it
    private static void requireAcceptance(JsonObject body) {
        Validate.isTrue(Boolean.TRUE.equals(body.getValue("acceptTerms")), "The terms must be accepted");
    }

    private static BigDecimal amount(JsonObject body, String name) {
        Object value = body.getValue(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("The field " + name + " must be a number");
        }
        return new BigDecimal(number.toString());
    }
}
