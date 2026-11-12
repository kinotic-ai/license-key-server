package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * A license key that was issued, bound to one cluster of its license. The log is append-only: a key is never changed or
 * removed once issued, since it is verified offline and works until it expires.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class IssuedKey {
    /** The key id, the {@code jti} claim. */
    private String jti;
    /** The license the key was issued for. */
    private String licenseId;
    /** The {@link LicensedCluster} the key is bound to. */
    private String licensedClusterId;
    /** The compact JWS. */
    private String key;
    private Instant issuedAt;
    private Instant expiresAt;
}
