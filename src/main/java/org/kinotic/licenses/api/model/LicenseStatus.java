package org.kinotic.licenses.api.model;

/**
 * Where a license stands. Only an {@link #ACTIVE} license is issued a renewal key; an issued key works until it
 * expires whatever the status becomes, since keys are verified offline.
 */
public enum LicenseStatus {
    /** In good standing. */
    ACTIVE,
    /** A payment failed and Stripe is retrying; no renewal key is issued until it is paid. */
    PAST_DUE,
    /** The subscription ended. */
    CANCELED,
    /** Staff revoked it. */
    REVOKED
}
