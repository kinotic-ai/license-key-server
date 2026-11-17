package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import lombok.RequiredArgsConstructor;
import org.kinotic.common.api.oidc.OidcAuthorization;
import org.kinotic.common.api.oidc.OidcClient;
import org.kinotic.common.api.oidc.OidcClientService;
import org.kinotic.core.api.security.SessionBinding;
import org.kinotic.licenses.internal.api.model.OidcFlowState;
import org.kinotic.licenses.internal.api.model.OidcRegistration;

import java.util.regex.Pattern;

/**
 * The sign-ins through one of the license server's client registrations at a provider, with the authorization code
 * flow and PKCE. A sign-in is kept in the browser session between {@link #start}, which sends the browser to the
 * provider, and {@link #complete}, which redeems the provider's callback for the person's verified claims.
 */
@RequiredArgsConstructor
public class OidcSignInFlow {

    private static final String ACCESS_DENIED = "access_denied";
    // the form of an OAuth error code (RFC 6749 §4.1.2.1)
    private static final Pattern OAUTH_ERROR_CODE = Pattern.compile("[a-z0-9_]{1,64}");

    private final OidcClientService oidcClientService;
    private final OidcRegistration registration;
    /** The session key a sign-in is kept under until its callback. */
    private final String sessionKey;

    private Future<OidcClient> client;

    /**
     * Starts a sign-in: stores it in the session and returns the provider's authorization URL to redirect to.
     *
     * @param ctx        the request that starts the sign-in
     * @param returnPath the site path to land on afterwards, or {@code null} for the site's default
     */
    public Future<String> start(RoutingContext ctx, String returnPath) {
        OidcAuthorization authorization = OidcAuthorization.create();
        return client()
                .map(client -> client.authorizationUrl(authorization, registration.getCallbackUrl(), registration.getScopes()))
                // stored once the provider is reachable, so a failed start leaves any pending sign-in intact
                .onSuccess(url -> {
                    Session session = ctx.session();
                    session.regenerateId();
                    session.put(sessionKey, new OidcFlowState(authorization, SessionBinding.origin(ctx), returnPath));
                });
    }

    /**
     * Completes a sign-in from the provider's callback: checks the state and redeems the code for the person's
     * verified claims.
     *
     * @return the claims with the flow they complete, or a failure with {@link ProviderSignInException}
     */
    public Future<ProviderSignIn> complete(RoutingContext ctx) {
        String code = ctx.request().getParam("code");
        String state = ctx.request().getParam("state");
        String providerError = ctx.request().getParam("error");
        // consumed first, so a replayed callback finds no sign-in
        OidcFlowState flow = ctx.session().remove(sessionKey);

        Future<ProviderSignIn> ret;
        if (providerError != null) {
            // anyone can address the callback, so only a value in the form of an OAuth error code reaches the log
            String reported = OAUTH_ERROR_CODE.matcher(providerError).matches() ? providerError : "an unrecognized error";
            ProviderSignInFailureType type = ACCESS_DENIED.equals(providerError) ? ProviderSignInFailureType.ACCESS_DENIED
                                                                                 : ProviderSignInFailureType.FAILED;
            ret = Future.failedFuture(new ProviderSignInException(type, providerKey() + " reported " + reported));
        } else if (flow == null || code == null || state == null || !flow.authorization().state().equals(state)) {
            ret = Future.failedFuture(new ProviderSignInException(ProviderSignInFailureType.STATE_MISMATCH,
                                                                  "The callback matches no sign-in the browser started"));
        } else {
            ret = client()
                    .compose(client -> client.verifiedClaims(code, registration.getCallbackUrl(), flow.authorization()))
                    .recover(t -> Future.failedFuture(new ProviderSignInException(
                            ProviderSignInFailureType.FAILED,
                            "The sign-in with " + providerKey() + " could not be verified: " + t.getMessage(), t)))
                    .map(claims -> new ProviderSignIn(claims, flow));
        }
        return ret;
    }

    /** Releases the provider client the sign-ins used. */
    public synchronized void close() {
        if (client != null && client.succeeded()) {
            client.result().close();
        }
    }

    private String providerKey() {
        return registration.getProvider().key();
    }

    // created on the first sign-in; a discovery that fails is retried by the next
    private synchronized Future<OidcClient> client() {
        if (client == null || client.failed()) {
            client = oidcClientService.createClient(registration, registration.getClientSecret());
        }
        return client;
    }
}
