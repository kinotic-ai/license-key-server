package org.kinotic.licenses.internal.api.model;

import org.kinotic.licenses.api.security.LicenseParticipant;

/**
 * An email address its owner proved by entering the code sent to it.
 *
 * @param email       the address
 * @param participant the customer contact the address belongs to, or {@code null} when it has no account yet
 */
public record VerifiedEmail(String email, LicenseParticipant participant) {
}
