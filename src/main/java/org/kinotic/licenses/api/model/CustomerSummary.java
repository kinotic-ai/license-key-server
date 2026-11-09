package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * A row of the staff customer list: the customer, its newest license and that license's newest key. The license
 * and key fields are {@code null} for a customer that has no license yet.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class CustomerSummary {
    private String id;
    private String legalName;
    private String billingEmail;
    private Instant createdAt;
    private String licenseId;
    private Plan plan;
    private LicenseStatus status;
    private Instant currentPeriodEnd;
    private Instant termEnd;
    private Instant lastPaymentAt;
    private String latestKeyJti;
    private Instant latestKeyExpiresAt;
}
