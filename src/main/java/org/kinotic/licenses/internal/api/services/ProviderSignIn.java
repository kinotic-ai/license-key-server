package org.kinotic.licenses.internal.api.services;

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

    /** The key a sign-in's return path is kept under in its flow's context. */
    static final String RETURN_PATH = "returnPath";

    /** The person's email address as the provider gives it, or {@code null} when it gives none. */
    public String email() {
        return OAuth2Util.stringClaim(claims, "email");
    }

    /** The person's name as the provider gives it, or {@code null} when it gives none. */
    public String name() {
        return OAuth2Util.stringClaim(claims, "name");
    }

    /** The site path to land on, or {@code null} for the site's default. */
    public String returnPath() {
        return flow.context().getString(RETURN_PATH);
    }
}
