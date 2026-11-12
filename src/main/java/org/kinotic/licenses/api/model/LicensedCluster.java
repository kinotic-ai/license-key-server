package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * A cluster that holds a seat of a license, and that the license's keys are issued for. Each key is bound to its
 * cluster's id, so it works on that cluster alone.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class LicensedCluster {
    private String id;
    private String licenseId;
    /** The cluster's id: the UID of its kube-system namespace, which the system console's License page shows. */
    private String clusterId;
    /** The name the customer gave the cluster. */
    private String name;
    private Instant addedAt;
    /** When the cluster gave up its seat, as when its license ended and it was added to another; {@code null} while it holds one. */
    private Instant releasedAt;
    /** When the cluster last renewed its key from this server; {@code null} when it never has. */
    private Instant lastRenewedAt;
}
