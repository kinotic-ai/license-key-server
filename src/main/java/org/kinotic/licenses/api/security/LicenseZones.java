package org.kinotic.licenses.api.security;

/**
 * The zones the license server's services are published in. A staff participant may address both, a customer only
 * {@link #CUSTOMER}.
 */
public final class LicenseZones {

    /** The staff dashboard's services. */
    public static final String STAFF = "staff";

    /** The customer account's services, each answering only with the calling customer's own data. */
    public static final String CUSTOMER = "customer";

    private LicenseZones() {}
}
