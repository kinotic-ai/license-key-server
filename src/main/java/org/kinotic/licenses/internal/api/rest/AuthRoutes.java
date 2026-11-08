package org.kinotic.licenses.internal.api.rest;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.core.api.security.SessionBinding;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.internal.api.services.RateLimitedException;
import org.kinotic.licenses.internal.api.services.SignInCodeService;
import org.kinotic.licenses.internal.api.services.StaffOidcService;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The sign-in routes. Customers sign in with an emailed code on the public host; staff sign in through the OIDC
 * provider on the staff host, where alone the staff routes answer. {@code GET /api/auth/me} and
 * {@code POST /api/auth/logout} serve every signed-in page.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRoutes implements SuppliesGatewayRoutes {

    private static final String STAFF_LOGIN_PATH = "/login";
    private static final String STAFF_LOGIN_ERROR_PATH = STAFF_LOGIN_PATH + "?error=";

    private final KinoticLicensesProperties properties;
    private final SignInCodeService signInCodeService;
    private final StaffOidcService staffOidcService;

    @Override
    public void mountRoutes(Router router) {
        router.get("/api/auth/me").handler(this::me);
        router.post("/api/auth/logout").handler(this::logout);
        router.post("/api/auth/code/request").handler(this::requestCode);
        router.post("/api/auth/code/verify").handler(this::verifyCode);
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

    /** Always {@code 202}: every address is sent a code, so the response never says whether it has an account. */
    private void requestCode(RoutingContext ctx) {
        String email = RestSupport.requiredString(RestSupport.jsonBody(ctx), "email");
        signInCodeService.requestCode(email, RestSupport.clientAddress(ctx))
                         .onSuccess(v -> ctx.response().setStatusCode(202).end())
                         .onFailure(t -> failSignIn(ctx, t));
    }

    private void verifyCode(RoutingContext ctx) {
        JsonObject body = RestSupport.jsonBody(ctx);
        String email = RestSupport.requiredString(body, "email");
        String code = RestSupport.requiredString(body, "code");
        signInCodeService.verifyCode(email, code, RestSupport.clientAddress(ctx))
                         .onSuccess(verified -> {
                             // only the address's owner reaches here, so the answer may say whether it has an account
                             if (verified.participant() != null) {
                                 RestSupport.establishSession(ctx, SessionBinding.origin(ctx), verified.participant());
                             } else {
                                 RestSupport.rememberVerifiedEmail(ctx, SessionBinding.origin(ctx), verified.email());
                             }
                             RestSupport.respondJson(ctx, 200, new JsonObject().put("account", verified.participant() != null));
                         })
                         .onFailure(t -> failSignIn(ctx, t));
    }

    private void failSignIn(RoutingContext ctx, Throwable t) {
        if (t instanceof RateLimitedException) {
            RestSupport.respondJson(ctx, 429, new JsonObject().put("error", t.getMessage()));
        } else if (t instanceof AuthenticationException) {
            RestSupport.respondJson(ctx, 401, new JsonObject().put("error", t.getMessage()));
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
