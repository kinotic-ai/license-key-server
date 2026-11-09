package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * What an organization certifies about itself when it requests a free license: its figures, annual revenue and
 * capital raised, which must match {@link Plan#FREE}, and what it uses Kinotic for.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class FreeLicenseCertification {
    private CompanyFigures figures;
    private String intendedUse;
}
