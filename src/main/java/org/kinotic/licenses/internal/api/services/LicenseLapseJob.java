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
 * Lapses every license that has been past due for longer than {@link #LAPSE_AFTER}: marks it
 * {@link org.kinotic.licenses.api.model.LicenseStatus#LAPSED}, which ends the price list and terms it kept, cancels its
 * Stripe subscription and tells its contacts. Past due for {@link #WARN_AFTER}, the contacts are warned once. It runs
 * daily, and once shortly after startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseLapseJob {

    /** How long a failed payment may stay unsettled before the license lapses. */
    public static final Duration LAPSE_AFTER = Duration.ofDays(30);
    /** How long a failed payment stays unsettled before the contacts are warned the license lapses. */
    public static final Duration WARN_AFTER = Duration.ofDays(20);

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(2);
    private static final Duration INTERVAL = Duration.ofDays(1);

    private final LicenseRepository licenseRepository;
    private final StripeBillingService billingService;
    private final LicenseService licenseService;
    private final Vertx vertx;

    @PostConstruct
    void schedule() {
        vertx.setPeriodic(INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), timer -> run());
    }

    /**
     * Warns the contacts of the licenses past due for {@link #WARN_AFTER}, then lapses the licenses past due for longer
     * than {@link #LAPSE_AFTER}.
     *
     * @return how many licenses lapsed
     */
    public Future<Integer> run() {
        return licenseRepository.findLapseWarningsDue(Instant.now().minus(WARN_AFTER))
                                .compose(this::warnAll)
                                .compose(v -> licenseRepository.findPastDueSince(Instant.now().minus(LAPSE_AFTER)))
                                .compose(this::lapseAll)
                                .onSuccess(count -> log.info("The lapse job lapsed {} license(s)", count))
                                .onFailure(t -> log.error("The lapse job failed", t));
    }

    // one license at a time, each on its own: a warning that fails is logged and sent on the next run
    private Future<Void> warnAll(List<License> due) {
        Future<Void> ret = Future.succeededFuture();
        for (License license : due) {
            ret = ret.compose(v -> licenseService.emailLapseWarning(license, license.getPastDueSince().plus(LAPSE_AFTER))
                                                 .compose(sent -> licenseRepository.markLapseWarned(license.getId()))
                                                 .recover(t -> {
                                                     log.error("The lapse warning of license {} failed; the next run tries again", license.getId(), t);
                                                     return Future.succeededFuture();
                                                 }));
        }
        return ret;
    }

    // one license at a time, each on its own: a failure is logged and the run goes on to the next license
    private Future<Integer> lapseAll(List<License> due) {
        Future<Integer> ret = Future.succeededFuture(0);
        for (License license : due) {
            ret = ret.compose(count -> lapse(license).map(lapsed -> lapsed ? count + 1 : count)
                                                     .recover(t -> {
                                                         log.error("Lapsing the license {} failed; the next run tries again", license.getId(), t);
                                                         return Future.succeededFuture(count);
                                                     }));
        }
        return ret;
    }

    // A license whose payment Stripe has settled by now is left to the webhook event that reports it, and does not lapse.
    // The lapse stands once recorded; a notice that fails is logged.
    private Future<Boolean> lapse(License license) {
        return billingService.lapseIfUnpaid(license).compose(lapsed -> {
            Future<Boolean> ret;
            if (lapsed == null) {
                ret = Future.succeededFuture(false);
            } else {
                log.info("The license {} lapsed after {} past due", license.getId(), LAPSE_AFTER);
                ret = licenseService.emailLapsed(lapsed)
                                    .recover(t -> {
                                        log.error("The license {} lapsed but its contacts could not be told", license.getId(), t);
                                        return Future.succeededFuture();
                                    })
                                    .map(true);
            }
            return ret;
        });
    }
}
