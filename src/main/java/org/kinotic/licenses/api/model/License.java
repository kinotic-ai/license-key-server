package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Set;

/**
 * A customer's license to Kinotic Enterprise. It runs in license years from its start; a paid plan bills monthly or
 * annually through the Stripe subscription. A trial runs for its three months instead, and buying its plan starts the
 * license's first year. It keeps the price list and the terms it started on for as long as it
 * stays active.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class License {
    private String id;
    private String customerId;
    private Plan plan;
    private LicenseStatus status;
    /** How the subscription bills; {@code null} for a free license or a trial. */
    private BillingPeriod billingPeriod;
    /** The version of the price list in effect when the license started, which its plan moves are priced on. */
    private int priceList;
    /** The version of the terms in effect when the license started. */
    private String termsVersion;
    /** The Stripe Subscription; {@code null} for a free license or a trial. */
    private String stripeSubscriptionId;
    /** The Stripe Checkout Session that bought it; {@code null} for a free license or a trial. */
    private String checkoutSessionId;
    /** The end of the current billing period, as Stripe reports it. */
    private Instant currentPeriodEnd;
    /** When the subscription ends because the customer cancelled it; {@code null} while it renews. */
    private Instant cancelsAt;
    /** When the payment that left the license past due failed; {@code null} unless it is {@link LicenseStatus#PAST_DUE}. */
    private Instant pastDueSince;
    /** What staff should look at; empty when nothing. */
    private Set<LicenseFlag> flags;
    /** The start of the current license year, or of the trial. */
    private Instant termStart;
    /** The end of the current license year, the license's next anniversary, or when the trial ends. */
    private Instant termEnd;
    private Instant lastPaymentAt;
    private Instant createdAt;
}
