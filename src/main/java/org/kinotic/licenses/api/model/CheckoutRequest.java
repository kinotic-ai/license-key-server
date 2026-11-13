package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A purchase of a paid plan: the plan and billing period chosen, how many clusters it covers, and the company's
 * figures, which must match the plan.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class CheckoutRequest {
    private Plan plan;
    private BillingPeriod billingPeriod;
    /** How many clusters the license covers, each billed at the plan's price. */
    private int seats = 1;
    private CompanyFigures figures;
}
