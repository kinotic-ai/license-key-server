package org.kinotic.licenses.api.model;

/**
 * What a customer let Minds Ignited publish about it when it accepted the terms.
 */
public enum PublicityConsent {
    /** Naming the customer as a Kinotic customer, as the Free terms ask. */
    CUSTOMER_NAME,
    /**
     * As a design partner: naming the customer, describing what it uses Kinotic for in words it approves, and a case
     * study written together and published only with its approval. Never its code, its data or anything it marks
     * confidential.
     */
    DESIGN_PARTNER
}
