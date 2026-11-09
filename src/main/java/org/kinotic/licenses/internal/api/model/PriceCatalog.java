package org.kinotic.licenses.internal.api.model;

import com.stripe.model.Price;

import java.time.Instant;
import java.util.Map;

/**
 * The paid plans' active Stripe Prices as read at {@code fetchedAt}, by lookup key, with the current price list: the
 * highest version that has an active Price for every paid plan and billing period.
 *
 * @param currentPriceList the current price list, or {@code 0} when no version is complete
 * @param prices           every active Price whose lookup key is a {@link PriceLookupKey}
 * @param fetchedAt        when the Prices were read
 */
public record PriceCatalog(int currentPriceList, Map<PriceLookupKey, Price> prices, Instant fetchedAt) {
}
