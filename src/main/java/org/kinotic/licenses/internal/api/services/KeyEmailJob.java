package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.repositories.LicensedClusterRepository;
import org.kinotic.licenses.internal.api.repositories.UnsentRenewalEmailRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends the key emails the server owes: the renewal emails of licenses a payment extended whose email failed, and a
 * warning, once per key, to the contacts of a license whose cluster's newest key expires within {@link #WARNING_WINDOW}
 * with no newer one coming by itself: one past due, set to end, or free. It runs daily, and once shortly after startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyEmailJob {

    /** How far ahead of its expiry a key is warned about. */
    public static final Duration WARNING_WINDOW = Duration.ofDays(14);

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(1);
    private static final Duration INTERVAL = Duration.ofDays(1);

    private final LicenseRepository licenseRepository;
    private final LicensedClusterRepository clusterRepository;
    private final IssuedKeyRepository issuedKeyRepository;
    private final UnsentRenewalEmailRepository unsentRepository;
    private final LicenseService licenseService;
    private final Vertx vertx;

    @PostConstruct
    void schedule() {
        vertx.setPeriodic(INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), timer -> run());
    }

    /**
     * Sends the renewal emails still owed, then the expiry warnings due now.
     *
     * @return how many expiry warnings were sent
     */
    public Future<Integer> run() {
        return unsentRepository.findAll()
                               .compose(this::sendAll)
                               .compose(v -> issuedKeyRepository.findExpiringUnwarned(Instant.now().plus(WARNING_WINDOW)))
                               .compose(this::warnAll)
                               .onSuccess(count -> log.info("The key email job sent {} expiry warning(s)", count))
                               .onFailure(t -> log.error("The key email job failed", t));
    }

    // one key at a time, each on its own: a failure is logged and the run goes on to the next key
    private Future<Integer> warnAll(List<IssuedKey> expiring) {
        Future<Integer> ret = Future.succeededFuture(0);
        for (IssuedKey key : expiring) {
            ret = ret.compose(count -> warn(key).map(count + 1)
                                                .recover(t -> {
                                                    log.error("The expiry warning for key {} failed; the next run tries again", key.getJti(), t);
                                                    return Future.succeededFuture(count);
                                                }));
        }
        return ret;
    }

    private Future<Void> warn(IssuedKey key) {
        return licenseRepository.findById(key.getLicenseId())
                                .compose(license -> clusterRepository.findById(key.getLicensedClusterId())
                                                                     .compose(cluster -> licenseService.emailKeyExpiring(license, cluster, key)))
                                .compose(v -> issuedKeyRepository.markWarned(key.getJti()));
    }

    private Future<Void> sendAll(List<String> licenseIds) {
        Future<Void> ret = Future.succeededFuture();
        for (String licenseId : licenseIds) {
            ret = ret.compose(v -> send(licenseId).recover(t -> {
                log.error("The renewal of license {} could still not be emailed", licenseId, t);
                return Future.succeededFuture();
            }));
        }
        return ret;
    }

    private Future<Void> send(String licenseId) {
        return licenseRepository.findById(licenseId)
                                .compose(licenseService::emailRenewed)
                                .compose(v -> unsentRepository.remove(licenseId));
    }
}
