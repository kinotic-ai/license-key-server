package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * The price of a paid plan for one billing period on one price list, as its Stripe Price has it.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class PlanPrice {
    private Plan plan;
    private BillingPeriod billingPeriod;
    /** The version of the price list the price belongs to. */
    private int priceList;
    /** What one billing period costs, in {@link #currency}. */
    private BigDecimal amount;
    /** The ISO currency code, lowercase as Stripe reports it. */
    private String currency;
}
