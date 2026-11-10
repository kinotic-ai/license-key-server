package org.kinotic.licenses.internal.api.model;

import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.Plan;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code lookup_key} of the Stripe Price of a paid plan for one billing period on one price list:
 * {@code kinotic-<priceList>-<plan>-<period>}, such as {@code kinotic-1-small-annual}.
 *
 * @param priceList the version of the price list
 * @param plan      the paid plan
 * @param period    the billing period
 */
public record PriceLookupKey(int priceList, Plan plan, BillingPeriod period) {

    private static final Pattern FORMAT = Pattern.compile("kinotic-(\\d+)-(small|medium|large)-(monthly|annual)");

    /**
     * The lookup key, such as {@code kinotic-1-small-annual}.
     */
    public String lookupKey() {
        return "kinotic-" + priceList + "-" + plan.name().toLowerCase(Locale.ROOT) + "-" + period.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The key a lookup key names.
     *
     * @return the key, or {@code null} when the lookup key is not one of a paid plan's Prices
     */
    public static PriceLookupKey parse(String lookupKey) {
        PriceLookupKey ret = null;
        Matcher matcher = lookupKey != null ? FORMAT.matcher(lookupKey) : null;
        if (matcher != null && matcher.matches()) {
            ret = new PriceLookupKey(Integer.parseInt(matcher.group(1)),
                                     Plan.valueOf(matcher.group(2).toUpperCase(Locale.ROOT)),
                                     BillingPeriod.valueOf(matcher.group(3).toUpperCase(Locale.ROOT)));
        }
        return ret;
    }
}
