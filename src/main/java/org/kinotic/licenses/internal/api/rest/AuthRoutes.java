package org.kinotic.licenses.internal.api.rest;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.common.api.oidc.OidcProviderType;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.core.api.security.SessionBinding;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.internal.api.model.PendingProviderSignUp;
import org.kinotic.licenses.internal.api.services.CustomerOidcService;
import org.kinotic.licenses.internal.api.services.CustomerSignInService;
import org.kinotic.licenses.internal.api.services.ProviderSignIn;
import org.kinotic.licenses.internal.api.services.RateLimitedException;
import org.kinotic.licenses.internal.api.services.StaffOidcService;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * The sign-in and sign-up routes. On the public host a customer signs up with an email address, whose emailed link opens
 * the form that creates the account, signs in with an email and password, and sets a new password through an emailed
 * link. A customer also signs up and in through GitHub, Microsoft or Google: the provider proves the email address, so
 * an address with an account is signed in to it, and any other names its organization on the sign-up form with no
 * emailed link and no password. Staff sign in through the OIDC provider on the staff host, where alone the staff
 * routes answer. {@code GET /api/auth/me} and {@code POST /api/auth/logout} serve every signed-in page.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRoutes implements SuppliesGatewayRoutes {

    private static final String LOGIN_ERROR_PATH = "/login?error=";
    private static final String ACCOUNT_PATH = "/account";
    private static final String PROVIDER_SIGN_UP_PATH = "/signup/complete?provider=";
    private static final String PENDING_PROVIDER_SIGN_UP_SESSION_KEY = "pendingProviderSignUp";
    private static final Duration PENDING_PROVIDER_SIGN_UP_LIFETIME = Duration.ofMinutes(10);
    private static final String NO_PENDING_PROVIDER_SIGN_UP = "The sign-up has expired; start again";

    private final KinoticLicensesProperties properties;
    private final CustomerSignInService signInService;
    private final CustomerOidcService customerOidcService;
    private final StaffOidcService staffOidcService;

    @Override
    public void mountRoutes(Router router) {
        router.get("/api/auth/me").handler(this::me);
        router.post("/api/auth/logout").handler(this::logout);
        router.post("/api/auth/login").handler(this::signIn);
        router.post("/api/auth/signup").handler(this::requestSignUp);
        router.post("/api/auth/signup/complete").handler(this::completeSignUp);
        router.post("/api/auth/password/forgot").handler(this::requestPasswordReset);
        router.post("/api/auth/password/reset").handler(this::resetPassword);
        router.post("/api/auth/provider/:provider/start").handler(this::startProviderSignIn);
        router.get("/api/auth/provider/:provider/callback").handler(this::completeProviderSignIn);
        router.get("/api/auth/signup/provider").handler(this::pendingProviderSignUp);
        router.post("/api/auth/signup/provider/complete").handler(this::completeProviderSignUp);
        router.get("/api/auth/staff/login").handler(this::requireStaffHost).handler(this::startStaffSignIn);
        router.get(StaffOidcService.CALLBACK_PATH).handler(this::requireStaffHost).handler(this::completeStaffSignIn);
    }

    /** {@code 204} when the page is signed in, {@code 401} otherwise. */
    private void me(RoutingContext ctx) {
        boolean signedIn = RestSupport.signedIn(ctx) != null;
        if (!signedIn) {
            SessionBinding.unbind(ctx);
        }
        ctx.response().setStatusCode(signedIn ? 204 : 401).end();
    }

    private void logout(RoutingContext ctx) {
        SessionBinding.unbind(ctx);
        ctx.response().setStatusCode(204).end();
    }

    /** Signs the page in with an email and password: {@code 204}, or {@code 401} when they match no contact. */
    private void signIn(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        String email = RestSupport.requiredString(body, "email");
        String password = RestSupport.requiredUntrimmedString(body, "password");
        respondSignedIn(ctx, signInService.signIn(email, password, RestSupport.clientAddress(ctx)));
    }

    /**
     * Always {@code 202}: the address is emailed a link to create its account, or to set a new password when it has
     * one, so the response never says which. {@code referer} is the site path the link's page continues to.
     */
    private void requestSignUp(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        String email = RestSupport.requiredString(body, "email");
        String returnPath = RestSupport.safeReturnPath(body.getString("referer"));
        signInService.requestSignUp(email, returnPath, RestSupport.clientAddress(ctx))
                     .onSuccess(v -> ctx.response().setStatusCode(202).end())
                     .onFailure(t -> failSignIn(ctx, t));
    }

    /**
     * Creates the account of the address a sign-up link was emailed to, with the organization, contact name and
     * password given, and signs the page in to it: {@code 204}, or {@code 401} when the token is not of a usable link,
     * or {@code 409} when the address has an account by now.
     */
    private void completeSignUp(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        String token = RestSupport.requiredString(body, "token");
        String organization = RestSupport.requiredString(body, "organization");
        String contactName = RestSupport.requiredString(body, "contactName");
        String password = RestSupport.requiredUntrimmedString(body, "password");
        respondSignedIn(ctx, signInService.completeSignUp(token, organization, contactName, password));
    }

    /** Always {@code 202}: a contact with the address is emailed a link to set a new password, and anyone else nothing. */
    private void requestPasswordReset(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        String email = RestSupport.requiredString(body, "email");
        String returnPath = RestSupport.safeReturnPath(body.getString("referer"));
        signInService.requestPasswordReset(email, returnPath, RestSupport.clientAddress(ctx))
                     .onSuccess(v -> ctx.response().setStatusCode(202).end())
                     .onFailure(t -> failSignIn(ctx, t));
    }

    /**
     * Sets the password of the contact a password reset link was emailed to, and signs the page in as it: {@code 204},
     * or {@code 401} when the token is not of a usable link.
     */
    private void resetPassword(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        String token = RestSupport.requiredString(body, "token");
        String password = RestSupport.requiredUntrimmedString(body, "password");
        respondSignedIn(ctx, signInService.resetPassword(token, password));
    }

    /**
     * Sends the browser to the provider to sign in, from a form the login or sign-up page posts: {@code 302}, or
     * {@code 404} for a provider customers are not offered. {@code referer} is the site path to land on afterwards.
     */
    private void startProviderSignIn(RoutingContext ctx) {
        OidcProviderType provider = offeredProvider(ctx);
        if (provider == null) {
            ctx.response().setStatusCode(404).end();
        } else {
            String returnPath = RestSupport.safeReturnPath(ctx.request().getParam("referer"));
            customerOidcService.start(ctx, provider, returnPath)
                               .onSuccess(url -> redirect(ctx, url))
                               .onFailure(ctx::fail);
        }
    }

    /**
     * The provider's callback. A person whose email address has an account is signed in to it and sent to the path the
     * sign-in started with; any other is sent to the sign-up form, which names the organization and creates the
     * account through {@code POST /api/auth/signup/provider/complete}. A sign-in that fails returns to the login page
     * with the reason.
     */
    private void completeProviderSignIn(RoutingContext ctx) {
        OidcProviderType provider = offeredProvider(ctx);
        if (provider == null) {
            ctx.response().setStatusCode(404).end();
        } else {
            String publicBaseUrl = properties.getLicenses().getPublicBaseUrl();
            customerOidcService.complete(ctx, provider)
                               .compose(signIn -> signInService.signInWithProvenEmail(signIn.email())
                                                               .map(participant -> finishProviderSignIn(ctx, signIn, participant)))
                               .onSuccess(path -> redirect(ctx, publicBaseUrl + path))
                               .onFailure(t -> {
                                   String reason;
                                   if (t instanceof AuthenticationException) {
                                       log.warn("A sign-in with {} failed: {}", provider.key(), t.getMessage());
                                       reason = t.getMessage();
                                   } else {
                                       log.error("A sign-in with {} failed", provider.key(), t);
                                       reason = "The sign-in failed; try again";
                                   }
                                   redirect(ctx, publicBaseUrl + LOGIN_ERROR_PATH + URLEncoder.encode(reason, StandardCharsets.UTF_8));
                               });
        }
    }

    // signs the page in as the participant, or keeps the sign-up of a person with no account; returns the site path to continue at
    private String finishProviderSignIn(RoutingContext ctx, ProviderSignIn signIn, LicenseParticipant participant) {
        String returnPath = signIn.flow().returnPath();
        String ret;
        if (participant != null) {
            String origin = signIn.flow().origin() != null ? signIn.flow().origin() : properties.getLicenses().getPublicBaseUrl();
            RestSupport.establishSession(ctx, origin, participant);
            ret = returnPath != null ? returnPath : ACCOUNT_PATH;
        } else {
            ctx.session().put(PENDING_PROVIDER_SIGN_UP_SESSION_KEY,
                              new PendingProviderSignUp(signIn.email(), signIn.name(),
                                                        Instant.now().plus(PENDING_PROVIDER_SIGN_UP_LIFETIME)));
            ret = PROVIDER_SIGN_UP_PATH + signIn.provider().key()
                    + (returnPath != null ? "&referer=" + URLEncoder.encode(returnPath, StandardCharsets.UTF_8) : "");
        }
        return ret;
    }

    /**
     * The sign-up a provider started in this browser, for the sign-up form to show: the {@code email} the account is
     * created for and the person's {@code name} where the provider gives one; {@code 404} when there is none.
     */
    private void pendingProviderSignUp(RoutingContext ctx) {
        PendingProviderSignUp pending = pendingProviderSignUp(ctx.session());
        if (pending == null) {
            RestSupport.respondJson(ctx, 404, new JsonObject().put("error", NO_PENDING_PROVIDER_SIGN_UP));
        } else {
            RestSupport.respondJson(ctx, 200, new JsonObject().put("email", pending.email()).put("name", pending.name()));
        }
    }

    /**
     * Creates the account of the sign-up a provider started in this browser, with the organization and contact name
     * given, and signs the page in to it: {@code 204}, or {@code 401} when there is no such sign-up for the
     * {@code email} the form shows, or {@code 409} when the address has an account by now.
     */
    private void completeProviderSignUp(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        String email = RestSupport.requiredString(body, "email");
        String organization = RestSupport.requiredString(body, "organization");
        String contactName = RestSupport.requiredString(body, "contactName");
        PendingProviderSignUp pending = pendingProviderSignUp(ctx.session());
        // a sign-in in another tab replaces the pending sign-up, so the form's address is the one the person saw
        if (pending == null || !pending.email().equals(email)) {
            RestSupport.respondJson(ctx, 401, new JsonObject().put("error", NO_PENDING_PROVIDER_SIGN_UP));
        } else {
            respondSignedIn(ctx, signInService.completeProviderSignUp(pending.email(), organization, contactName)
                                              // kept until the account exists, so a refused request leaves the form usable
                                              .onSuccess(participant -> ctx.session().remove(PENDING_PROVIDER_SIGN_UP_SESSION_KEY)));
        }
    }

    private static PendingProviderSignUp pendingProviderSignUp(Session session) {
        PendingProviderSignUp ret = session.get(PENDING_PROVIDER_SIGN_UP_SESSION_KEY);
        if (ret != null && ret.expiresAt().isBefore(Instant.now())) {
            session.remove(PENDING_PROVIDER_SIGN_UP_SESSION_KEY);
            ret = null;
        }
        return ret;
    }

    // the provider the route names, or null when customers are not offered it
    private OidcProviderType offeredProvider(RoutingContext ctx) {
        String key = ctx.pathParam("provider");
        return customerOidcService.providers().stream().filter(provider -> provider.key().equals(key)).findFirst().orElse(null);
    }

    private static void redirect(RoutingContext ctx, String url) {
        ctx.response().setStatusCode(302).putHeader("Location", url).end();
    }

    private void respondSignedIn(RoutingContext ctx, Future<LicenseParticipant> signedIn) {
        signedIn.onSuccess(participant -> {
                    RestSupport.establishSession(ctx, SessionBinding.origin(ctx), participant);
                    ctx.response().setStatusCode(204).end();
                })
                .onFailure(t -> failSignIn(ctx, t));
    }

    private void failSignIn(RoutingContext ctx, Throwable t) {
        if (t instanceof RateLimitedException) {
            RestSupport.respondJson(ctx, 429, new JsonObject().put("error", t.getMessage()));
        } else if (t instanceof AuthenticationException) {
            RestSupport.respondJson(ctx, 401, new JsonObject().put("error", t.getMessage()));
        } else if (t instanceof AlreadyExistsException) {
            RestSupport.respondJson(ctx, 409, new JsonObject().put("error", t.getMessage()));
        } else if (t instanceof IllegalArgumentException) {
            RestSupport.respondJson(ctx, 400, new JsonObject().put("error", t.getMessage()));
        } else {
            ctx.fail(t);
        }
    }

    // a staff route addressed to any other host does not exist
    private void requireStaffHost(RoutingContext ctx) {
        if (RestSupport.addressedTo(ctx, properties.getLicenses().getStaffBaseUrl())) {
            ctx.next();
        } else {
            ctx.response().setStatusCode(404).end();
        }
    }

    private void startStaffSignIn(RoutingContext ctx) {
        String returnPath = RestSupport.safeReturnPath(ctx.request().getParam("referer"));
        staffOidcService.start(ctx, returnPath)
                        .onSuccess(url -> redirect(ctx, url))
                        .onFailure(ctx::fail);
    }

    private void completeStaffSignIn(RoutingContext ctx) {
        String staffBaseUrl = properties.getLicenses().getStaffBaseUrl();
        staffOidcService.complete(ctx)
                        .onSuccess(signIn -> {
                            String origin = signIn.flow().origin() != null ? signIn.flow().origin() : staffBaseUrl;
                            RestSupport.establishSession(ctx, origin, signIn.participant());
                            String path = signIn.flow().returnPath() != null ? signIn.flow().returnPath() : "/";
                            redirect(ctx, staffBaseUrl + path);
                        })
                        .onFailure(t -> {
                            log.warn("A staff sign-in failed: {}", t.getMessage());
                            redirect(ctx, staffBaseUrl + LOGIN_ERROR_PATH + URLEncoder.encode(t.getMessage(), StandardCharsets.UTF_8));
                        });
    }
}
