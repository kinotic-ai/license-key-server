package org.kinotic.licenses.internal.api.rest;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.CustomerService;
import org.kinotic.licenses.internal.api.services.FreeLicenseService;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.internal.api.services.LicenseSigningKey;
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

    private static final String SITE_PUBLIC = "PUBLIC";
    private static final String SITE_STAFF = "STAFF";

    private final KinoticLicensesProperties properties;
    private final LicenseSigningKey signingKey;
    private final StripeBillingService billingService;
    private final CustomerService customerService;
    private final FreeLicenseService freeLicenseService;
    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final JsonMapper jsonMapper;

    // the Checkout Sessions whose key the success page has already been shown; this node is the whole cluster
    private final Set<String> shownKeys = ConcurrentHashMap.newKeySet();

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
        String site = RestSupport.addressedTo(ctx, properties.getLicenses().getStaffBaseUrl()) ? SITE_STAFF : SITE_PUBLIC;
        RestSupport.respondJson(ctx, 200, new JsonObject().put("site", site).put("termsVersion", AgreementTerms.VERSION));
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
     * Step three of the purchase: creates the customer and its Stripe customer, records the acceptance and answers with
     * the Checkout Session URL to send the browser to.
     */
    private void checkout(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        Plan plan = plan(body);
        Validate.isTrue(plan != Plan.FREE, "The free plan is requested at /free, not bought");
        requireAcceptance(body);
        String contactName = RestSupport.requiredString(body, "contactName");
        String contactEmail = RestSupport.requiredString(body, "contactEmail");
        customerService.createCustomer(RestSupport.requiredString(body, "legalName"), contactName, contactEmail)
                       .compose(customer -> billingService.startCheckout(customer, contactName, contactEmail, plan))
                       .onSuccess(url -> RestSupport.respondJson(ctx, 200, new JsonObject().put("url", url)))
                       .onFailure(ctx::fail);
    }

    /**
     * The success page's view of the key the Checkout Session bought: {@code PENDING} until the webhook has created
     * the license, then the key once, within {@link #KEY_SHOW_WINDOW} of its issue, and {@code SHOWN} after that.
     */
    private void checkoutKey(RoutingContext ctx) {
        String sessionId = ctx.pathParam("sessionId");
        licenseRepository.findByCheckoutSessionId(sessionId)
                         .compose(license -> license == null
                                 ? io.vertx.core.Future.succeededFuture(new JsonObject().put("status", "PENDING"))
                                 : licenseService.keysOf(license.getId()).map(keys -> {
                                     JsonObject ret = new JsonObject().put("status", "SHOWN");
                                     boolean withinWindow = license.getCreatedAt().plus(KEY_SHOW_WINDOW).isAfter(Instant.now());
                                     if (withinWindow && !keys.isEmpty() && shownKeys.add(sessionId)) {
                                         ret.put("status", "READY")
                                            .put("key", keys.getLast().getKey())
                                            .put("expiresAt", keys.getLast().getExpiresAt().toString());
                                     }
                                     return ret;
                                 }))
                         .onSuccess(result -> RestSupport.respondJson(ctx, 200, result))
                         .onFailure(ctx::fail);
    }

    private void freeRequest(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        requireAcceptance(body);
        FreeLicenseCertification certification = new FreeLicenseCertification()
                .setAnnualRevenue(amount(body, "annualRevenue"))
                .setCapitalRaised(amount(body, "capitalRaised"))
                .setIntendedUse(RestSupport.requiredString(body, "intendedUse"));
        freeLicenseService.request(RestSupport.requiredString(body, "organization"),
                                   RestSupport.requiredString(body, "contactName"),
                                   RestSupport.requiredString(body, "contactEmail"),
                                   certification)
                          .onSuccess(request -> RestSupport.respondJson(ctx, 201, new JsonObject().put("id", request.getId())))
                          .onFailure(ctx::fail);
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
