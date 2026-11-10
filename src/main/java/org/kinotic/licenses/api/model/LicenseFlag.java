package org.kinotic.licenses.api.model;

/**
 * Something about a license staff should look at. A flag never stops a license or its keys.
 */
public enum LicenseFlag {
    /** The customer did not confirm its figures by the license's anniversary; the license went on as it was. */
    REVENUE_UNCONFIRMED,
    /**
     * An installation renewed with a key older than the license's newest, as a copied installation or a restored backup
     * does. The key still renews.
     */
    POSSIBLY_COPIED
}
