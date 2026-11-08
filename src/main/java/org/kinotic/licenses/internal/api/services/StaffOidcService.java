package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2AuthorizationURL;
import io.vertx.ext.auth.oauth2.OAuth2FlowType;
import io.vertx.ext.auth.oauth2.OAuth2Options;
import io.vertx.ext.auth.oauth2.Oauth2Credentials;
import io.vertx.ext.auth.oauth2.providers.OpenIDConnectAuth;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.core.api.security.SessionBinding;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.config.StaffOidcProperties;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.security.ParticipantType;
import org.kinotic.licenses.internal.api.model.OidcFlowState;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Signs staff in through Minds Ignited's OpenID Connect provider with the authorization code flow and PKCE. The
 * provider is discovered from its issuer on the first sign-in. A signed-in account is accepted only when the
 * provider says its email is verified and the email is on the allowed domain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaffOidcService {

    /** The route the provider redirects back to, relative to the staff site's origin. */
    public static final String CALLBACK_PATH = "/api/auth/staff/callback";

    private static final String FLOW_SESSION_KEY = "staffOidcFlow";
    private static final String ENTRA_HOST = "login.microsoftonline.com";
    private static final List<String> SCOPES = List.of("openid", "email", "profile");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KinoticLicensesProperties properties;
    private final Vertx vertx;

    private volatile Future<OAuth2Auth> oauth2;

    /**
     * Starts a sign-in: stores the flow in the session and returns the provider's authorization URL to redirect to.
     *
     * @param ctx        the request that starts the sign-in
     * @param returnPath the staff site path to land on afterwards, or {@code null} for the root
     */
    public Future<String> start(RoutingContext ctx, String returnPath) {
        String state = token(32);
        String nonce = token(32);
        String pkceVerifier = token(64);
        return provider().map(oauth -> oauth.authorizeURL(new OAuth2AuthorizationURL()
                                                                  .setRedirectUri(callbackUrl())
                                                                  .setScopes(SCOPES)
                                                                  .setState(state)
                                                                  .setCodeChallenge(s256(pkceVerifier))
                                                                  .setCodeChallengeMethod("S256")
                                                                  .putAdditionalParameter("nonce", nonce)))
                         // stored once the provider is reachable, so a failed start leaves any pending flow intact
                         .onSuccess(url -> {
                             Session session = ctx.session();
                             session.regenerateId();
                             session.put(FLOW_SESSION_KEY, new OidcFlowState(state, nonce, pkceVerifier,
                                                                             SessionBinding.origin(ctx), returnPath));
                         });
    }

    /**
     * Completes a sign-in from the provider's callback: checks the state, exchanges the code, verifies the id
     * token's nonce and the account's email, and returns the staff participant with the flow it completes.
     *
     * @return the participant and flow, or a failure with {@link AuthenticationException}
     */
    public Future<StaffSignIn> complete(RoutingContext ctx) {
        String code = ctx.request().getParam("code");
        String state = ctx.request().getParam("state");
        String providerError = ctx.request().getParam("error");
        // consumed first, so a replayed callback finds no flow
        OidcFlowState flow = ctx.session().remove(FLOW_SESSION_KEY);

        Future<StaffSignIn> ret;
        if (providerError != null) {
            ret = Future.failedFuture(new AuthenticationException("The sign-in provider reported: " + providerError));
        } else if (flow == null || code == null || state == null || !flow.state().equals(state)) {
            ret = Future.failedFuture(new AuthenticationException("The sign-in did not complete; start again"));
        } else {
            ret = provider().compose(oauth -> oauth.authenticate(new Oauth2Credentials()
                                                                         .setFlow(OAuth2FlowType.AUTH_CODE)
                                                                         .setCode(code)
                                                                         .setRedirectUri(callbackUrl())
                                                                         .setCodeVerifier(flow.pkceVerifier())))
                             .recover(t -> Future.failedFuture(new AuthenticationException("The code exchange failed", t)))
                             .compose(user -> verifiedParticipant(user, flow))
                             .map(participant -> new StaffSignIn(participant, flow));
        }
        return ret;
    }

    private Future<LicenseParticipant> verifiedParticipant(User user, OidcFlowState flow) {
        StaffOidcProperties oidc = properties.getLicenses().getStaffOidc();
        // Vert.x puts the id token's claims under the idToken attribute; the principal holds the raw token response
        JsonObject claims = user.attributes().getJsonObject("idToken", new JsonObject());
        String email = claims.getString("email");
        String domain = email != null && email.contains("@") ? email.substring(email.indexOf('@') + 1) : null;
        Future<LicenseParticipant> ret;
        if (!flow.nonce().equals(claims.getString("nonce"))) {
            ret = Future.failedFuture(new AuthenticationException("The id token's nonce does not match the sign-in"));
        } else if (!emailVerified(claims, oidc.getIssuer())) {
            ret = Future.failedFuture(new AuthenticationException("The provider has not verified the account's email"));
        } else if (domain == null || !domain.equalsIgnoreCase(oidc.getAllowedEmailDomain())) {
            log.warn("A staff sign-in from outside {} was refused", oidc.getAllowedEmailDomain());
            ret = Future.failedFuture(new AuthenticationException("The account is not a staff account"));
        } else {
            ret = Future.succeededFuture(LicenseParticipant.builder()
                                                           .id(email.toLowerCase())
                                                           .type(ParticipantType.STAFF)
                                                           .email(email)
                                                           .name(claims.getString("name", email))
                                                           .metadata(Map.of())
                                                           .roles(List.of())
                                                           .build());
        }
        return ret;
    }

    // Google states email_verified. Entra never sends it: a tenant's administrator issues its accounts' addresses, and the
    // allowed email domain check that follows confines staff to Minds Ignited's own tenant.
    private static boolean emailVerified(JsonObject claims, String issuer) {
        Object verified = claims.getValue("email_verified");
        boolean ret;
        if (verified != null) {
            ret = Boolean.TRUE.equals(verified) || "true".equals(verified);
        } else {
            ret = ENTRA_HOST.equalsIgnoreCase(URI.create(issuer).getHost());
        }
        return ret;
    }

    // discovered once; a discovery that fails is retried by the next sign-in
    private Future<OAuth2Auth> provider() {
        Future<OAuth2Auth> ret = oauth2;
        if (ret == null || ret.failed()) {
            synchronized (this) {
                ret = oauth2;
                if (ret == null || ret.failed()) {
                    StaffOidcProperties oidc = properties.getLicenses().getStaffOidc();
                    Validate.validState(StringUtils.isNoneBlank(oidc.getIssuer(), oidc.getClientId(), oidc.getAllowedEmailDomain()),
                                        "kinotic.licenses.staffOidc needs an issuer, a clientId and an allowedEmailDomain");
                    OAuth2Options options = new OAuth2Options()
                            .setClientId(oidc.getClientId())
                            .setClientSecret(oidc.getClientSecret())
                            .setSite(oidc.getIssuer());
                    ret = OpenIDConnectAuth.discover(vertx, options);
                    oauth2 = ret;
                }
            }
        }
        return ret;
    }

    @PreDestroy
    void stop() {
        Future<OAuth2Auth> provider = oauth2;
        if (provider != null && provider.succeeded()) {
            // cancels the JWKS refresh timer discovery left running
            provider.result().close();
        }
    }

    private String callbackUrl() {
        return properties.getLicenses().getStaffBaseUrl() + CALLBACK_PATH;
    }

    private static String token(int bytes) {
        byte[] ret = new byte[bytes];
        RANDOM.nextBytes(ret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ret);
    }

    private static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
