package org.kinotic.licenses.api.model;

import org.apache.commons.lang3.Validate;

import java.math.BigDecimal;

/**
 * The plans Kinotic Enterprise is licensed under, set by the customer's annual revenue, including any companies it
 * owns. Free also requires under $5M raised in total in equity, SAFEs and convertible notes; a company under $1M in
 * revenue that has raised more is on {@link #SMALL}. Paid plans are bought through Stripe; Free is approved by staff.
 */
public enum Plan {
    /** Annual revenue under $1M and under $5M raised. */
    FREE,
    /** Annual revenue $1M to under $20M, or under $1M with $5M or more raised. */
    SMALL,
    /** Annual revenue $20M to $100M. */
    MEDIUM,
    /** Annual revenue over $100M. */
    LARGE;

    /** The annual revenue a Free license stays under, in US dollars. */
    public static final BigDecimal FREE_REVENUE_LIMIT = new BigDecimal("1000000");
    /** The capital raised a Free license stays under, in US dollars. */
    public static final BigDecimal FREE_CAPITAL_LIMIT = new BigDecimal("5000000");
    private static final BigDecimal SMALL_REVENUE_LIMIT = new BigDecimal("20000000");
    private static final BigDecimal MEDIUM_REVENUE_LIMIT = new BigDecimal("100000000");

    /**
     * The plan a company with these figures is on.
     *
     * @throws IllegalArgumentException when the figures are not valid: see {@link #validate(CompanyFigures)}
     */
    public static Plan matching(CompanyFigures figures) {
        validate(figures);
        BigDecimal revenue = figures.getAnnualRevenue();
        Plan ret;
        if (revenue.compareTo(FREE_REVENUE_LIMIT) < 0) {
            ret = figures.getCapitalRaised().compareTo(FREE_CAPITAL_LIMIT) < 0 ? FREE : SMALL;
        } else if (revenue.compareTo(SMALL_REVENUE_LIMIT) < 0) {
            ret = SMALL;
        } else if (revenue.compareTo(MEDIUM_REVENUE_LIMIT) <= 0) {
            ret = MEDIUM;
        } else {
            ret = LARGE;
        }
        return ret;
    }

    /**
     * Checks that the figures can decide a plan: the annual revenue is given and not negative, and so is the capital
     * raised when the revenue is under {@link #FREE_REVENUE_LIMIT}, where it decides between Free and Small.
     *
     * @throws IllegalArgumentException when they cannot
     */
    public static void validate(CompanyFigures figures) {
        Validate.isTrue(figures != null, "The company's figures are required");
        Validate.isTrue(isAmount(figures.getAnnualRevenue()), "The annual revenue must be zero or more");
        Validate.isTrue(figures.getAnnualRevenue().compareTo(FREE_REVENUE_LIMIT) >= 0 || isAmount(figures.getCapitalRaised()),
                        "The capital raised must be zero or more when the annual revenue is under $1M");
    }

    private static boolean isAmount(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }
}
