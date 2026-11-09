package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * A request for a free license, with the figures the organization reported about itself. Staff approve or reject
 * it; approving creates the free license and issues its key.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class FreeLicenseRequest {
    private String id;
    private String customerId;
    /** The customer's legal name. */
    private String organization;
    private String contactName;
    private String contactEmail;
    /** What the organization certified about itself. */
    private FreeLicenseCertification certification;
    private FreeLicenseRequestStatus status;
    /** The staff member who approved or rejected it. */
    private String reviewedBy;
    private Instant reviewedAt;
    private Instant createdAt;
}
