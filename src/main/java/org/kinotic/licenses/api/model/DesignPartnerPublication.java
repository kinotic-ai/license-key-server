package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * A thing Minds Ignited may publish about a design partner, and whether the customer has approved it.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class DesignPartnerPublication {
    private String id;
    private String designPartnerId;
    private PublicationType type;
    /** The person at the customer who approved it; {@code null} until it is approved. */
    private String approvedBy;
    /** When the customer approved it; {@code null} until then. */
    private Instant approvedAt;
}
