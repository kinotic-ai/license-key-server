package org.kinotic.licenses.internal.api.services;

import org.kinotic.licenses.api.security.LicenseParticipant;

/**
 * A completed staff sign-in: the participant and the provider sign-in that produced it, whose origin and return path
 * the callback route finishes with.
 */
public record StaffSignIn(LicenseParticipant participant, ProviderSignIn verified) {
}
