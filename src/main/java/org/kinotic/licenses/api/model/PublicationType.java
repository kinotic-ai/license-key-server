package org.kinotic.licenses.api.model;

/**
 * A thing Minds Ignited may publish about a design partner once the customer approves it.
 */
public enum PublicationType {
    /** The customer's name and logo, as a Kinotic customer. */
    LOGO,
    /** What the customer uses Kinotic for, in words it approves. */
    USE_DESCRIPTION,
    /** A case study written with the customer. */
    CASE_STUDY
}
