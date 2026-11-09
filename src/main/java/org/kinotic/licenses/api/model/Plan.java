package org.kinotic.licenses.api.model;

/**
 * The plans Kinotic Enterprise is licensed under. Every paid plan is a monthly Stripe subscription whose price
 * lives on the Stripe Price with the plan's {@link #lookupKey()}; the free plan is granted by staff.
 */
public enum Plan {
    /** Small organizations, below about $1M annual revenue and about $3M raised; approved by staff. */
    FREE,
    STARTUP,
    BUSINESS,
    ENTERPRISE;

    private static final String LOOKUP_KEY_PREFIX = "kinotic-";

    /**
     * The {@code lookup_key} of the Stripe Price that prices this plan, {@code kinotic-startup} for
     * {@link #STARTUP}.
     */
    public String lookupKey() {
        return LOOKUP_KEY_PREFIX + name().toLowerCase();
    }

    /**
     * The plan priced by a Stripe Price lookup key.
     *
     * @param lookupKey the Price's {@code lookup_key}
     * @return the plan
     * @throws IllegalArgumentException when no plan has the key
     */
    public static Plan fromLookupKey(String lookupKey) {
        Plan ret = null;
        for (Plan plan : values()) {
            if (plan.lookupKey().equals(lookupKey)) {
                ret = plan;
            }
        }
        if (ret == null) {
            throw new IllegalArgumentException("No plan has the Stripe Price lookup key " + lookupKey);
        }
        return ret;
    }
}
