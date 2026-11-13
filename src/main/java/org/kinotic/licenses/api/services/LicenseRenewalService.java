package org.kinotic.licenses.api.services;

import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.licenses.api.security.LicenseZones;

import java.util.concurrent.CompletableFuture;

/**
 * The key renewal a Kinotic installation calls with {@code kinotic-client}, signed in with a key issued for its cluster
 * in the {@code license-key} header of the WebSocket upgrade to {@code wss://licenses.kinotic.ai/api/v1}. Kinotic declares
 * this interface in its own code with the same {@link Publish} namespace and name, {@link Zone} and {@link Version},
 * which together address the service; there is no shared jar.
 */
@Publish(namespace = "org.kinotic.license", name = "LicenseRenewalService")
@Version("1.0.0")
@Zone(LicenseZones.LICENSEE)
public interface LicenseRenewalService {

    /**
     * The newest key of the cluster the caller signed in as, bound to that cluster, issued first when its license is
     * paid beyond what that key covers. Calling it again returns the same key until the license is paid further. A
     * trial's keys cover the trial.
     *
     * @return the newest key, or a failure naming the reason when the license is past due and the caller holds its
     *         cluster's newest key already, or the license has fewer seats than clusters and this cluster is not one of
     *         its oldest; a past-due license whose caller holds an older key gets the newest
     */
    CompletableFuture<String> renew();
}
