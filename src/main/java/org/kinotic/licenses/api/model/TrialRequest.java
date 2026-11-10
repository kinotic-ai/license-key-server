package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A trial of a paid plan: the plan chosen, and the company's figures, which must match the plan.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class TrialRequest {
    private Plan plan;
    private CompanyFigures figures;
}
