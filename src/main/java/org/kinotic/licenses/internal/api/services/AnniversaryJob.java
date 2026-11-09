package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseFlag;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.RevenueConfirmation;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.repositories.RevenueConfirmationRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Runs each license's anniversary. From {@link LicenseService#CONFIRMATION_WINDOW} before it, the contacts are asked
 * once to confirm the company's figures. Once it has passed, a license whose figures were not confirmed is flagged
 * {@link LicenseFlag#REVENUE_UNCONFIRMED} and goes on as it was, keys included; a paid license whose figures set Free
 * continues as Free; and a paid license starts its next license year. A Free license whose figures were confirmed
 * waits for staff to approve its next year, or for its paid plan to be bought. It runs daily, and once shortly after
 * startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnniversaryJob {

    private static final Duration INITIAL_DELAY = Duration.ofMinutes(3);
    private static final Duration INTERVAL = Duration.ofDays(1);

    private final LicenseRepository licenseRepository;
    private final RevenueConfirmationRepository confirmationRepository;
    private final LicenseService licenseService;
    private final Vertx vertx;

    @PostConstruct
    void schedule() {
        vertx.setPeriodic(INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), timer -> run());
    }

    /**
     * Asks for the confirmations due, then runs the anniversaries that have passed.
     */
    public Future<Void> run() {
        return licenseRepository.findConfirmationRequestsDue(Instant.now().plus(LicenseService.CONFIRMATION_WINDOW))
                                .compose(due -> eachOf(due, this::requestConfirmation, "asking for the figures of"))
                                .compose(v -> licenseRepository.findAnniversariesPassed())
                                .compose(passed -> eachOf(passed, this::anniversary, "running the anniversary of"))
                                .onSuccess(v -> log.info("The anniversary job ran"))
                                .onFailure(t -> log.error("The anniversary job failed", t));
    }

    private Future<Void> requestConfirmation(License license) {
        return confirmationRepository.find(license.getId(), license.getTermEnd())
                                     .compose(confirmed -> confirmed == null ? licenseService.emailConfirmationDue(license) : Future.succeededFuture())
                                     .compose(v -> licenseRepository.markConfirmationRequested(license.getId()));
    }

    // Whether this anniversary was confirmed and what applies at it are separate: a confirmation made late, for the
    // anniversary before, decides what applies at this one, which still needs its own confirmation.
    private Future<Void> anniversary(License license) {
        return confirmationRepository.find(license.getId(), license.getTermEnd()).compose(confirmed ->
                confirmationRepository.findApplyingAt(license.getId(), license.getTermEnd()).compose(decided -> {
                    Future<License> current = confirmed != null
                            ? Future.succeededFuture(license)
                            : licenseRepository.addFlag(license.getId(), LicenseFlag.REVENUE_UNCONFIRMED)
                                               .onSuccess(flagged -> log.info("The license {} reached its anniversary with its figures unconfirmed",
                                                                              license.getId()));
                    return current.compose(flagged -> {
                        Future<Void> ret;
                        if (decided != null && endsAsFree(decided)) {
                            ret = continueAsFree(flagged);
                        } else if (flagged.getPlan() != Plan.FREE || decided == null) {
                            // a paid license's year moves on its own, and an unconfirmed free license goes on as it was
                            ret = nextYear(flagged);
                        } else {
                            // a free license waits for staff to approve its next year, or for the paid plan its figures set
                            // to be bought
                            ret = Future.succeededFuture();
                        }
                        return ret;
                    });
                }));
    }

    // the subscription ended at the anniversary, and the license goes on as Free, whether or not Stripe's event of the
    // ending has moved it already
    private Future<Void> continueAsFree(License license) {
        Future<License> free = license.getPlan() == Plan.FREE
                ? Future.succeededFuture(license)
                : licenseRepository.moveToFree(license.getId());
        return free.compose(moved -> moved != null ? nextYear(moved) : Future.succeededFuture())
                   .onSuccess(v -> log.info("The license {} continues as Free", license.getId()));
    }

    // a paid license's year moves on its own, its keys following the payments; a free license's year moves with the key
    // covering it, which is emailed
    private Future<Void> nextYear(License license) {
        Future<Void> ret;
        if (license.getPlan() == Plan.FREE) {
            ret = licenseService.renewFreeTerm(license)
                                .compose(renewed -> licenseService.emailRenewalKey(renewed.license(), renewed.key()));
        } else {
            ret = licenseRepository.advanceTerm(license.getId(), license.getTermEnd(), LicenseService.termEnd(license.getTermEnd())).mapEmpty();
        }
        return ret;
    }

    private static boolean endsAsFree(RevenueConfirmation decided) {
        return decided.getPlan() == Plan.FREE && decided.getLicensePlan() != Plan.FREE;
    }

    // one license at a time, each on its own: a failure is logged and the run goes on to the next license
    private static Future<Void> eachOf(List<License> licenses, Function<License, Future<Void>> action, String what) {
        Future<Void> ret = Future.succeededFuture();
        for (License license : licenses) {
            ret = ret.compose(v -> action.apply(license).recover(t -> {
                log.error("The anniversary job failed {} license {}; the next run tries again", what, license.getId(), t);
                return Future.succeededFuture();
            }));
        }
        return ret;
    }
}
