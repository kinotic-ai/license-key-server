package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * An application to be a design partner: the paid plan the company's figures set, the billing period chosen, and what
 * it uses Kinotic for. Staff approve or reject it.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class DesignPartnerApplication {
    private String id;
    private String customerId;
    /** The customer's legal name. */
    private String organization;
    private String contactName;
    private String contactEmail;
    private Plan plan;
    private BillingPeriod billingPeriod;
    private CompanyFigures figures;
    private String intendedUse;
    private DesignPartnerApplicationStatus status;
    /** The staff member who approved or rejected it. */
    private String reviewedBy;
    private Instant reviewedAt;
    private Instant createdAt;
}
