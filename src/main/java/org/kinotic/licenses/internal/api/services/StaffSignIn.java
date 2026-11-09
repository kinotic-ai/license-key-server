package org.kinotic.licenses.internal.api.services;

import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.internal.api.model.OidcFlowState;

/**
 * A completed staff sign-in: the participant and the flow that produced it, whose origin and return path the
 * callback route finishes with.
 */
public record StaffSignIn(LicenseParticipant participant, OidcFlowState flow) {
}
