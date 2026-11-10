package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * The figures a customer confirmed for one anniversary of its license, and the plan they set.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class RevenueConfirmation {
    private String id;
    private String licenseId;
    /** The anniversary the figures were confirmed for. */
    private Instant anniversary;
    /** The figures; the capital raised is recorded where it decides between Free and Small. */
    private CompanyFigures figures;
    /** The plan the figures set. */
    private Plan plan;
    /** The plan the license was on when they were confirmed. */
    private Plan licensePlan;
    /**
     * The anniversary the plan the figures set takes effect at: the one confirmed, or the license's next for figures
     * confirmed after the anniversary they were for.
     */
    private Instant appliesAt;
    private String confirmedByName;
    private String confirmedByEmail;
    private Instant confirmedAt;
}
