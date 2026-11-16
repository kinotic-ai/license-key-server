package org.kinotic.licenses.internal.api.model;

/**
 * What an {@link EmailLink} lets the owner of its address do.
 */
public enum EmailLinkType {
    /** Create a license account for the address, naming its organization and setting a password. */
    SIGN_UP,
    /** Set a new password for the contact with the address. */
    PASSWORD_RESET
}
