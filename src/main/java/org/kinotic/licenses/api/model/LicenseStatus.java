package org.kinotic.licenses.api.model;

/**
 * Where a license stands. A license stays active, keeping its price list and terms, for as long as it is never
 * cancelled and every failed payment is settled within 30 days of the failure. An issued key works until it expires
 * whatever the status becomes, since keys are verified offline.
 */
public enum LicenseStatus {
    /** In good standing. */
    ACTIVE,
    /** A payment failed and Stripe is retrying; settling it within 30 days keeps the license active. */
    PAST_DUE,
    /** A failed payment went unsettled for 30 days; the subscription was cancelled. */
    LAPSED,
    /** The subscription ended. */
    CANCELED,
    /** Staff revoked it. */
    REVOKED
}
