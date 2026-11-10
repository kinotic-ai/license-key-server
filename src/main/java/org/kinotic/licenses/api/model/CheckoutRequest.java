package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A purchase of a paid plan: the plan and billing period chosen, and the company's figures, which must match the plan.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class CheckoutRequest {
    private Plan plan;
    private BillingPeriod billingPeriod;
    private CompanyFigures figures;
}
