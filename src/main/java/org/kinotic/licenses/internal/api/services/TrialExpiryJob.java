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
import java.util.List;

/**
 * Expires every trial whose term has ended without its plan being bought: marks it
 * {@link org.kinotic.licenses.api.model.LicenseStatus#EXPIRED} and tells its contacts. It runs hourly, and once shortly
 * after startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrialExpiryJob {

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(3);
    private static final Duration INTERVAL = Duration.ofHours(1);

    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final Vertx vertx;

    @PostConstruct
    void schedule() {
        vertx.setPeriodic(INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), timer -> run());
    }

    /**
     * Expires the trials that have ended.
     *
     * @return how many trials expired
     */
    public Future<Integer> run() {
        return licenseRepository.findTrialsEnded()
                                .compose(this::expireAll)
                                .onSuccess(count -> log.info("The trial expiry job expired {} trial(s)", count))
                                .onFailure(t -> log.error("The trial expiry job failed", t));
    }

    // one trial at a time, each on its own: a failure is logged and the run goes on to the next trial
    private Future<Integer> expireAll(List<License> ended) {
        Future<Integer> ret = Future.succeededFuture(0);
        for (License trial : ended) {
            ret = ret.compose(count -> expire(trial).map(expired -> expired ? count + 1 : count)
                                                    .recover(t -> {
                                                        log.error("Expiring the trial {} failed; the next run tries again", trial.getId(), t);
                                                        return Future.succeededFuture(count);
                                                    }));
        }
        return ret;
    }

    // A trial whose plan was bought meanwhile is no longer a trial and stays as it is. The expiry stands once recorded;
    // a notice that fails is logged.
    private Future<Boolean> expire(License trial) {
        return licenseRepository.expire(trial.getId()).compose(expired -> {
            Future<Boolean> ret;
            if (expired == null) {
                ret = Future.succeededFuture(false);
            } else {
                log.info("The trial {} expired", trial.getId());
                ret = licenseService.emailTrialExpired(expired)
                                    .recover(t -> {
                                        log.error("The trial {} expired but its contacts could not be told", trial.getId(), t);
                                        return Future.succeededFuture();
                                    })
                                    .map(true);
            }
            return ret;
        });
    }
}
