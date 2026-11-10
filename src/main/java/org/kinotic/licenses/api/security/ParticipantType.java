package org.kinotic.licenses.api.security;

/**
 * Who a signed-in participant is to the license server, which decides the zones it may address.
 */
public enum ParticipantType {
    /** A Minds Ignited staff member, signed in through the staff OIDC provider on the staff host. */
    STAFF,
    /** A contact of a customer, signed in with a one-time code emailed to that contact. */
    CUSTOMER,
    /** A Kinotic installation, signed in with a license key it holds. */
    LICENSEE
}
