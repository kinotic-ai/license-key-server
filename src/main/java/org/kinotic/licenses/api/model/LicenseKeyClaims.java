package org.kinotic.licenses.api.model;

import java.time.Instant;

/**
 * The claims of a license key: who it licenses, which key it is, the cluster it is bound to, and when it was issued and
 * expires. Every key turns on every Enterprise Feature.
 *
 * @param subject   the licensee, the customer's legal name, which Kinotic shows as the licensee
 * @param keyId     the key id
 * @param clusterId the id of the cluster the key is bound to, the UID of its kube-system namespace
 * @param issuedAt  when the key was issued
 * @param expiresAt when the key expires
 */
public record LicenseKeyClaims(String subject,
                               String keyId,
                               String clusterId,
                               Instant issuedAt,
                               Instant expiresAt) {
}
