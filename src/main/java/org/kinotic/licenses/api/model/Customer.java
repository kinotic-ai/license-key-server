package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * A licensee: the legal entity that accepted the agreement and holds a license.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class Customer {
    private String id;
    private String legalName;
    /** The Stripe Customer the subscription bills; {@code null} until a purchase starts. */
    private String stripeCustomerId;
    private Instant createdAt;
}
