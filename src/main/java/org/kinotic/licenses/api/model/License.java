package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Set;

/**
 * A customer's license to Kinotic Enterprise. Its term is the lifetime of the keys issued for it; a paid plan bills
 * monthly through the Stripe subscription.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class License {
    private String id;
    private String customerId;
    private Plan plan;
    private LicenseStatus status;
    private Set<Feature> features;
    /** The Stripe Subscription; {@code null} for a free license. */
    private String stripeSubscriptionId;
    /** The Stripe Checkout Session that bought it; {@code null} for a free license. */
    private String checkoutSessionId;
    /** The end of the current monthly billing period, as Stripe reports it. */
    private Instant currentPeriodEnd;
    /** When the subscription ends because the customer cancelled it; {@code null} while it renews. */
    private Instant cancelsAt;
    private Instant termStart;
    private Instant termEnd;
    private Instant lastPaymentAt;
    private Instant createdAt;
}
