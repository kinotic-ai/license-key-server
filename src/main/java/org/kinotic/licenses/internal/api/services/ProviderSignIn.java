package org.kinotic.licenses.internal.api.services;

import org.kinotic.licenses.internal.api.model.OidcRegistration;
import org.kinotic.core.api.security.SessionBinding;
import org.kinotic.common.api.oidc.OidcSignInFlow;
import org.kinotic.common.api.oidc.OidcErrorCodes;
import org.kinotic.common.api.oidc.OidcClientService;
import org.kinotic.common.api.oidc.OidcCallbackException;
import io.vertx.ext.web.RoutingContext;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import org.kinotic.common.api.oidc.OAuth2Util;
import org.kinotic.common.api.oidc.OidcFlowState;

import java.util.Map;

/**
 * A sign-in a provider verified: the claims of the person it signed in and the flow that produced them, whose origin
 * and return path the callback route finishes with.
 *
 * @param claims the person's verified claims
 * @param flow   the flow the sign-in completes
 */
public record ProviderSignIn(Map<String, Object> claims, OidcFlowState flow) {

    /** The person's email address as the provider gives it, or {@code null} when it gives none. */
    public String email() {
        return OAuth2Util.stringClaim(claims, "email");
    }

    /** The person's name as the provider gives it, or {@code null} when it gives none. */
    public String name() {
        return OAuth2Util.stringClaim(claims, "name");
    }

    /** The key the sign-in's return path is kept under in the flow's context. */
    public static final String RETURN_PATH = "returnPath";

    /** The site path to land on, or {@code null} for the site's default. */
    public String returnPath() {
        return flow.context().getString(RETURN_PATH);
    }

    /**
     * The state of a sign-in through {@code registration} that the request starts, to land on {@code returnPath}.
     *
     * @param returnPath the site path to land on afterwards, or {@code null} for the site's default
     */
    public static OidcFlowState begin(OidcRegistration registration, RoutingContext ctx, String returnPath) {
        return OidcFlowState.create(registration.getProvider().key(), SessionBinding.origin(ctx),
                                    new JsonObject().put(RETURN_PATH, returnPath));
    }

    /**
     * Completes a sign-in through {@code registration} from the provider's callback: checks it against the sign-in
     * {@code flow} keeps in the session and redeems its code for the person's verified claims.
     *
     * @return the sign-in, or a failure with {@link OidcCallbackException} for a callback that is refused
     */
    public static Future<ProviderSignIn> complete(RoutingContext ctx, OidcSignInFlow flow, OidcRegistration registration,
                                                  OidcClientService oidcClientService) {
        Future<ProviderSignIn> ret;
        try {
            OidcFlowState state = flow.consume(ctx);
            // one session key serves every registration of a service, so the sign-in must be this registration's
            if (!state.registrationId().equals(registration.getProvider().key())) {
                throw new OidcCallbackException(OidcErrorCodes.STATE_MISMATCH);
            }
            ret = registration.client(oidcClientService)
                              .compose(client -> flow.verifiedClaims(ctx, client, registration.getCallbackUrl(), state))
                              .map(claims -> new ProviderSignIn(claims, state));
        } catch (OidcCallbackException refused) {
            ret = Future.failedFuture(refused);
        }
        return ret;
    }
}
