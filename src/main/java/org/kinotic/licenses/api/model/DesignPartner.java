package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.List;

/**
 * A customer approved as a design partner: half off its plan for 36 months from its subscription's start, in return
 * for the publications it approves.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class DesignPartner {
    private String id;
    private String customerId;
    /** The customer's legal name. */
    private String organization;
    private String applicationId;
    /** The license its discounted subscription bought; {@code null} until it is bought. */
    private String licenseId;
    private Instant approvedAt;
    /** When the discount ends; {@code null} until the subscription is bought. */
    private Instant discountEndsAt;
    private CaseStudyStatus caseStudyStatus;
    /** One per {@link PublicationType}. */
    private List<DesignPartnerPublication> publications;
}
