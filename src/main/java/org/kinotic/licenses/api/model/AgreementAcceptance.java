package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * A click-through acceptance of the order terms and the Kinotic Enterprise License: who accepted, for which
 * customer, which version of the terms, and when.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class AgreementAcceptance {
    private String id;
    private String customerId;
    private String termsVersion;
    private String acceptedByName;
    private String acceptedByEmail;
    private Instant acceptedAt;
}
