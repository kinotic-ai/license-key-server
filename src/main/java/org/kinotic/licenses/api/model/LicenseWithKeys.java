package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.List;

/**
 * A license with every key issued for it, newest first, and its yearly confirmations.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class LicenseWithKeys {
    private License license;
    private List<IssuedKey> keys;
    /** The date the license is paid through, which its keys follow; {@code null} for a paid license not yet paid. */
    private Instant paidThrough;
    /**
     * The anniversary the customer is asked to confirm its figures for: 30 days before the next one, or a missed one
     * while the license is flagged {@link LicenseFlag#REVENUE_UNCONFIRMED}; {@code null} when none is due.
     */
    private Instant confirmationDueFor;
    /** The figures confirmed for each anniversary, newest first. */
    private List<RevenueConfirmation> confirmations;
}
