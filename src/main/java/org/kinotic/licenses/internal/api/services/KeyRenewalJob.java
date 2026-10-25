package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Issues and emails the next term's key for every paid license in good standing whose term ends within the renewal
 * window. It runs daily, and once shortly after startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyRenewalJob {

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(1);
    private static final Duration INTERVAL = Duration.ofDays(1);

    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final Vertx vertx;

    @PostConstruct
    void schedule() {
        vertx.setPeriodic(INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), timer -> run());
    }

    /**
     * Issues the renewal keys due now.
     *
     * @return how many were issued
     */
    public Future<Integer> run() {
        return licenseRepository.findPaidRenewalsDue(Instant.now().plus(LicenseService.RENEWAL_WINDOW))
                                .compose(this::renewAll)
                                .onSuccess(count -> log.info("The renewal job issued {} key(s)", count))
                                .onFailure(t -> log.error("The renewal job failed", t));
    }

    // one license at a time: a failure stops the run, and the next run picks that license up again
    private Future<Integer> renewAll(List<License> due) {
        Future<Integer> ret = Future.succeededFuture(0);
        for (License license : due) {
            ret = ret.compose(count -> licenseService.issueRenewalKey(license)
                                                     .compose(key -> licenseService.emailRenewalKey(license, key))
                                                     .map(count + 1));
        }
        return ret;
    }
}
