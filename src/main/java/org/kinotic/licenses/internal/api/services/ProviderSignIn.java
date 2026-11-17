package org.kinotic.licenses.internal.api.services;

import org.kinotic.common.api.oidc.OAuth2Util;
import org.kinotic.licenses.internal.api.model.OidcFlowState;

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
}
