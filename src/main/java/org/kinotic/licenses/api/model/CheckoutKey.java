package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * What the purchase's success page is told about the key its Checkout Session bought.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class CheckoutKey {
    private CheckoutKeyStatus status;
    /** The key, present only when {@link #status} is {@link CheckoutKeyStatus#READY}. */
    private String key;
    /** When the key expires, present only with the key. */
    private Instant expiresAt;
}
