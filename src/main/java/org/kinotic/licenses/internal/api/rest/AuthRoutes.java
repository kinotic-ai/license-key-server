package org.kinotic.licenses.internal.api.rest;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.core.api.security.SessionBinding;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.internal.api.services.RateLimitedException;
import org.kinotic.licenses.internal.api.services.CustomerSignInService;
import org.kinotic.licenses.internal.api.services.StaffOidcService;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The sign-in and sign-up routes. On the public host a customer signs up with an email address, whose emailed link opens
 * the form that creates the account, signs in with an email and password, and sets a new password through an emailed
 * link; staff sign in through the OIDC provider on the staff host, where alone the staff routes answer.
 * {@code GET /api/auth/me} and {@code POST /api/auth/logout} serve every signed-in page.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRoutes implements SuppliesGatewayRoutes {

    private static final String STAFF_LOGIN_PATH = "/login";
    private static final String STAFF_LOGIN_ERROR_PATH = STAFF_LOGIN_PATH + "?error=";

    private final KinoticLicensesProperties properties;
    private final CustomerSignInService signInService;
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
                        .onSuccess(url -> ctx.response().setStatusCode(302).putHeader("Location", url).end())
                        .onFailure(ctx::fail);
    }

    private void completeStaffSignIn(RoutingContext ctx) {
        String staffBaseUrl = properties.getLicenses().getStaffBaseUrl();
        staffOidcService.complete(ctx)
                        .onSuccess(signIn -> {
                            String origin = signIn.flow().origin() != null ? signIn.flow().origin() : staffBaseUrl;
                            RestSupport.establishSession(ctx, origin, signIn.participant());
                            String path = signIn.flow().returnPath() != null ? signIn.flow().returnPath() : "/";
                            ctx.response().setStatusCode(302).putHeader("Location", staffBaseUrl + path).end();
                        })
                        .onFailure(t -> {
                            log.warn("A staff sign-in failed: {}", t.getMessage());
                            String error = URLEncoder.encode(t.getMessage(), StandardCharsets.UTF_8);
                            ctx.response().setStatusCode(302)
                               .putHeader("Location", staffBaseUrl + STAFF_LOGIN_ERROR_PATH + error).end();
                        });
    }
}
