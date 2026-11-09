package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * A plan as the purchase flow shows it: its price, read from the Stripe Price with the plan's
 * lookup key, and what it includes.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class PlanInfo {
    private Plan id;
    private String name;
    /** The monthly price in {@link #currency}; zero for the free plan. */
    private BigDecimal monthlyPrice;
    /** The ISO currency code, lowercase as Stripe reports it. */
    private String currency;
    /** The Enterprise Features a key for the plan enables. */
    private Set<Feature> features;
    /** The selling points of the plan's Stripe Product, as its marketing features; empty for the free plan. */
    private List<String> highlights;
    /** Whether staff approve the plan rather than Stripe billing it. */
    private boolean requiresApproval;
}
