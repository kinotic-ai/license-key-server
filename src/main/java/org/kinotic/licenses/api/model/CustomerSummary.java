package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Set;

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
    private BillingPeriod billingPeriod;
    /** The price list the license keeps. */
    private Integer priceList;
    /** The date the license is paid through, which its keys follow. */
    private Instant paidThrough;
    private Instant currentPeriodEnd;
    /** The license's next anniversary. */
    private Instant termEnd;
    private Instant lastPaymentAt;
    /** What staff should look at on the license; empty when nothing or when the customer has no license. */
    private Set<LicenseFlag> flags;
    private String latestKeyJti;
    private Instant latestKeyExpiresAt;
}
