package org.kinotic.licenses.internal.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * A one-time sign-in code emailed to a customer contact, stored as a hash. It is usable until it expires or is
 * used once.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class SignInCode {
    private String id;
    private String email;
    private String codeHash;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant usedAt;
}
