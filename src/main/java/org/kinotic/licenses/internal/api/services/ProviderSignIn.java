package org.kinotic.licenses.internal.api.services;

import org.kinotic.common.api.oidc.OidcProviderType;
import org.kinotic.licenses.internal.api.model.OidcFlowState;

/**
 * A completed sign-in at a provider customers use: the person it signed in and the flow that produced it, whose
 * origin and return path the callback route finishes with.
 *
 * @param provider the provider
 * @param email    the email address the provider proved belongs to the person
 * @param name     the person's name as the provider gives it, or {@code null} when it gives none
 * @param flow     the flow the sign-in completes
 */
public record ProviderSignIn(OidcProviderType provider, String email, String name, OidcFlowState flow) {
}
