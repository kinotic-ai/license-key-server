package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * What a customer reports about its company, which sets its {@link Plan}.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class CompanyFigures {
    /** Annual revenue, including any companies the customer owns, in US dollars. */
    private BigDecimal annualRevenue;
    /**
     * The total ever raised in equity, SAFEs and convertible notes, not grants or loans, in US dollars. It decides
     * eligibility for Free only, so it may be {@code null} when the annual revenue is $1M or more.
     */
    private BigDecimal capitalRaised;
}
