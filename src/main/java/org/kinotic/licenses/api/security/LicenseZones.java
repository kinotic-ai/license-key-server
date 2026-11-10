package org.kinotic.licenses.api.security;

/**
 * The zones the license server's services are published in. A staff participant may address the staff and customer
 * zones, a customer only {@link #CUSTOMER}, and a Kinotic installation signed in with its license key only
 * {@link #LICENSEE}.
 */
public final class LicenseZones {

    /** The staff dashboard's services. */
    public static final String STAFF = "staff";

    /** The customer account's services, each answering only with the calling customer's own data. */
    public static final String CUSTOMER = "customer";

    /** The services a Kinotic installation reaches with its license key: the key renewal alone. */
    public static final String LICENSEE = "licensee";

    private LicenseZones() {}
}
