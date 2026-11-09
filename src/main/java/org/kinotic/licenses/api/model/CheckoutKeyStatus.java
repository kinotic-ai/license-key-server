package org.kinotic.licenses.api.model;

/**
 * What the success page gets when it asks for the key its Checkout Session bought.
 */
public enum CheckoutKeyStatus {
    /** The webhook has not created the license yet. */
    PENDING,
    /** The key is in this response, the one time it is shown. */
    READY,
    /** The key was shown already, or its window has passed; it is in the account and the email. */
    SHOWN
}
