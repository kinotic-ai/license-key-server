package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.repositories.UnsentRenewalEmailRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Issues and emails the next term's key for every paid license in good standing whose subscription renews and whose
 * term ends within the renewal window. Each run first sends the renewal emails an earlier run could not. It runs
 * daily, and once shortly after startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyRenewalJob {

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(1);
    private static final Duration INTERVAL = Duration.ofDays(1);

    private final LicenseRepository licenseRepository;
    private final IssuedKeyRepository issuedKeyRepository;
    private final UnsentRenewalEmailRepository unsentRepository;
    private final LicenseService licenseService;
    private final Vertx vertx;

    @PostConstruct
    void schedule() {
        vertx.setPeriodic(INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), timer -> run());
    }

    /**
     * Sends the renewal emails still owed, then issues the renewal keys due now.
     *
     * @return how many renewal keys were issued
     */
    public Future<Integer> run() {
        return unsentRepository.findAll()
                               .compose(this::sendAll)
                               .compose(v -> licenseRepository.findPaidRenewalsDue(Instant.now().plus(LicenseService.RENEWAL_WINDOW)))
                               .compose(this::renewAll)
                               .onSuccess(count -> log.info("The renewal job issued {} key(s)", count))
                               .onFailure(t -> log.error("The renewal job failed", t));
    }

    // one license at a time, each on its own: a failure is logged and the run goes on to the next license
    private Future<Integer> renewAll(List<License> due) {
        Future<Integer> ret = Future.succeededFuture(0);
        for (License license : due) {
            ret = ret.compose(count -> renew(license).map(count + 1)
                                                     .recover(t -> {
                                                         log.error("Renewing the license {} failed; the next run tries again", license.getId(), t);
                                                         return Future.succeededFuture(count);
                                                     }));
        }
        return ret;
    }

    // the key is issued once; an email that fails is owed, and the next run sends it
    private Future<Void> renew(License license) {
        return licenseService.issueRenewalKey(license)
                             .compose(issued -> licenseService.emailRenewalKey(issued.license(), issued.key())
                                                              .recover(t -> {
                                                                  log.error("The renewal key {} could not be emailed; the next run sends it",
                                                                            issued.key().getJti(), t);
                                                                  return unsentRepository.add(issued.key().getJti(), issued.license().getId());
                                                              }));
    }

    private Future<Void> sendAll(List<String> jtis) {
        Future<Void> ret = Future.succeededFuture();
        for (String jti : jtis) {
            ret = ret.compose(v -> send(jti).recover(t -> {
                log.error("The renewal key {} could still not be emailed", jti, t);
                return Future.succeededFuture();
            }));
        }
        return ret;
    }

    private Future<Void> send(String jti) {
        return issuedKeyRepository.findByJti(jti)
                                  .compose(key -> licenseRepository.findById(key.getLicenseId())
                                                                   .compose(license -> licenseService.emailRenewalKey(license, key)))
                                  .compose(v -> unsentRepository.remove(jti));
    }
}
