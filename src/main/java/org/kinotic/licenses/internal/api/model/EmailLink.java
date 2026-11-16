package org.kinotic.licenses.internal.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * A one-time link emailed to an address, stored as the hash of its token. It is usable until it expires or is used
 * once.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class EmailLink {
    private String id;
    private EmailLinkType type;
    private String email;
    private String tokenHash;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant usedAt;
}
