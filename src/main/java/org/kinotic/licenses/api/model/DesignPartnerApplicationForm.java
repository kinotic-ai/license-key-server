package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * What a customer gives when it applies to be a design partner: its figures, which set a paid plan, the billing period
 * it wants, and what it uses Kinotic for.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class DesignPartnerApplicationForm {
    private CompanyFigures figures;
    private BillingPeriod billingPeriod;
    private String intendedUse;
}
