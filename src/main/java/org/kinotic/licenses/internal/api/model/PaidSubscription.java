package org.kinotic.licenses.internal.api.model;

import java.time.Instant;

/**
 * A Stripe subscription a Checkout Session bought, as a paid license starts from it.
 *
 * @param price                the Price it bills: the plan, billing period and price list
 * @param stripeSubscriptionId the Stripe Subscription
 * @param checkoutSessionId    the Stripe Checkout Session that bought it
 * @param currentPeriodEnd     the end of its first billing period
 */
public record PaidSubscription(PriceLookupKey price, String stripeSubscriptionId, String checkoutSessionId,
                               Instant currentPeriodEnd) {
}
