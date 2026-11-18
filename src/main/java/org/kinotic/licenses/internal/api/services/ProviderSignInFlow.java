package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.kinotic.common.api.oidc.OidcCallbackException;
import org.kinotic.common.api.oidc.OidcClient;
import org.kinotic.common.api.oidc.OidcClientService;
import org.kinotic.common.api.oidc.OidcFlowState;
import org.kinotic.common.api.oidc.OidcSignInFlow;
import org.kinotic.core.api.security.SessionBinding;
import org.kinotic.licenses.internal.api.model.OidcRegistration;

/**
 * The sign-ins through one of the license server's registrations at a provider. A sign-in is kept in the browser's
 * session, under a key of the registration's own, between {@link #start} and the provider's callback, which
 * {@link #complete} checks and redeems.
 */
public class ProviderSignInFlow {

    private final OidcClientService oidcClientService;
    private final OidcRegistration registration;
    private final OidcSignInFlow flow;
    private Future<OidcClient> client;

    /**
     * @param sessionKey the session key the registration's sign-ins are kept under until their callbacks
     */
    public ProviderSignInFlow(OidcClientService oidcClientService, OidcRegistration registration, String sessionKey) {
        this.oidcClientService = oidcClientService;
        this.registration = registration;
        this.flow = new OidcSignInFlow(sessionKey);
    }

    /**
     * Starts a sign-in: keeps it in the session and returns the provider's authorization URL to redirect to.
     *
     * @param ctx        the request that starts the sign-in
     * @param returnPath the site path to land on afterwards, or {@code null} for the site's default
     */
    public Future<String> start(RoutingContext ctx, String returnPath) {
        // kept once the provider is reachable, so a failed start leaves any pending sign-in intact
        return client().map(client -> flow.start(
                ctx, client, registration.getCallbackUrl(), registration.getScopes(),
                OidcFlowState.create(registration.getProvider().key(), SessionBinding.origin(ctx),
                                     new JsonObject().put(ProviderSignIn.RETURN_PATH, returnPath))));
    }

    /**
     * Completes a sign-in from the provider's callback: checks it against the sign-in the session keeps and redeems
     * its code for the person's verified claims.
     *
     * @return the sign-in, or a failure: an {@link OidcCallbackException} for a callback that is refused or an id
     *         token that fails verification, and the provider client's own failure otherwise
     */
    public Future<ProviderSignIn> complete(RoutingContext ctx) {
        Future<ProviderSignIn> ret;
        try {
            OidcFlowState state = flow.consume(ctx);
            ret = client().compose(client -> flow.verifiedClaims(ctx, client, registration.getCallbackUrl(), state))
                          .map(claims -> new ProviderSignIn(claims, state));
        } catch (OidcCallbackException refused) {
            ret = Future.failedFuture(refused);
        }
        return ret;
    }

    /** Releases the provider client the sign-ins used. */
    public synchronized void close() {
        if (client != null && client.succeeded()) {
            client.result().close();
        }
    }

    // created on the first sign-in; a discovery that fails is retried by the next
    private synchronized Future<OidcClient> client() {
        if (client == null || client.failed()) {
            client = oidcClientService.createClient(registration, registration.getClientSecret());
        }
        return client;
    }
}
