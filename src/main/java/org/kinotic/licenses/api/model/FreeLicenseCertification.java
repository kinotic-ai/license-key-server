package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * What an organization certifies about itself when it requests a free license or its renewal.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class FreeLicenseCertification {
    /** Self-reported annual revenue, in US dollars. */
    private BigDecimal annualRevenue;
    /** Self-reported capital raised, in US dollars. */
    private BigDecimal capitalRaised;
    private String intendedUse;
}
