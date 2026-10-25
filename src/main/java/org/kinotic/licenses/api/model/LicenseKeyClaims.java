package org.kinotic.licenses.api.model;

import java.time.Instant;
import java.util.Set;

/**
 * The claims of a license key: who it licenses, which key it is, when it was issued and expires, what it licenses
 * Kinotic Enterprise for, and the Enterprise Features it enables.
 *
 * @param subject   the licensee, the customer id
 * @param keyId     the key id
 * @param issuedAt  when the key was issued
 * @param expiresAt when the key expires, or {@code null} for a key that never does
 * @param purpose   what the key licenses Kinotic Enterprise for
 * @param features  the Enterprise Features the key enables
 */
public record LicenseKeyClaims(String subject,
                               String keyId,
                               Instant issuedAt,
                               Instant expiresAt,
                               LicensePurpose purpose,
                               Set<Feature> features) {
}
