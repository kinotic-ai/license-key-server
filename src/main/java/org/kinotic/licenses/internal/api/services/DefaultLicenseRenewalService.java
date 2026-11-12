package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.services.LicenseRenewalService;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.repositories.LicensedClusterRepository;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * The key renewal of the cluster the calling installation signed in as.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultLicenseRenewalService implements LicenseRenewalService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM uuuu").withZone(ZoneOffset.UTC);

    private final SecurityContext securityContext;
    private final LicenseRepository licenseRepository;
    private final LicensedClusterRepository clusterRepository;
    private final IssuedKeyRepository issuedKeyRepository;
    private final LicenseService licenseService;

    @Override
    public CompletableFuture<String> renew() {
        LicenseParticipant caller = securityContext.requireParticipant(LicenseParticipant.class);
        return licenseRepository.findById(caller.getLicenseId())
                                .compose(license -> clusterRepository.findById(caller.getLicensedClusterId())
                                                                     .compose(cluster -> renew(caller, license, cluster)))
                                .compose(key -> clusterRepository.markRenewed(caller.getLicensedClusterId()).map(key.getKey()))
                                .onSuccess(key -> log.debug("The cluster {} of license {} renewed its key", caller.getLicensedClusterId(),
                                                            caller.getLicenseId()))
                                .toCompletionStage()
                                .toCompletableFuture();
    }

    private Future<IssuedKey> renew(LicenseParticipant caller, License license, LicensedCluster cluster) {
        // a session signed in before the cluster was released is still open
        if (cluster.getReleasedAt() != null) {
            return Future.failedFuture(new IllegalStateException(
                    "This cluster was released from its license in your account at licenses.kinotic.ai, so no newer key is issued."
                            + " Add the cluster again there to license it."));
        }
        return license.getStatus() == LicenseStatus.ACTIVE || license.getStatus() == LicenseStatus.TRIAL
                ? seated(license, cluster)
                : pastDue(caller, license, cluster);
    }

    // A seated cluster gets the key covering the license; one beyond the license's seats, as when its subscription
    // dropped clusters, is told why it gets none
    private Future<IssuedKey> seated(License license, LicensedCluster cluster) {
        return clusterRepository.findSeated(license.getId()).compose(seated -> {
            Future<IssuedKey> ret;
            if (seated.stream().anyMatch(each -> each.getId().equals(cluster.getId()))) {
                ret = licenseService.issueKeyIfExtended(license, cluster).compose(issued -> newest(cluster));
            } else {
                ret = Future.failedFuture(new IllegalStateException(
                        "The license has " + license.getSeats() + " seat(s) and more clusters than that, and this cluster is not one of"
                                + " its oldest, so no newer key is issued. Release a cluster or add seats in your account at licenses.kinotic.ai."));
            }
            return ret;
        });
    }

    // A license that is not active gets no new key. An installation holding an older key than the newest gets the newest,
    // as a restored backup does; one holding the newest is told why no newer one comes.
    private Future<IssuedKey> pastDue(LicenseParticipant caller, License license, LicensedCluster cluster) {
        return newest(cluster).compose(newest -> {
            Future<IssuedKey> ret;
            if (!newest.getJti().equals(caller.getKeyId())) {
                ret = Future.succeededFuture(newest);
            } else {
                String since = license.getPastDueSince() != null ? " since " + DATE.format(license.getPastDueSince()) : "";
                ret = Future.failedFuture(new IllegalStateException(
                        "The license is " + license.getStatus().name().toLowerCase().replace('_', ' ') + since
                                + ", so no newer key is issued; the newest key expires on " + DATE.format(newest.getExpiresAt())
                                + ". Settle the payment in your account at licenses.kinotic.ai."));
            }
            return ret;
        });
    }

    private Future<IssuedKey> newest(LicensedCluster cluster) {
        return issuedKeyRepository.findLatestByClusterId(cluster.getId());
    }
}
