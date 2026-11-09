package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseFlag;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.RevenueConfirmation;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.repositories.RevenueConfirmationRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The yearly confirmation of a customer's figures. From 30 days before each anniversary of its license, the customer
 * confirms its annual revenue, and for a Free license its capital raised too. Figures that set another plan move the
 * license at the anniversary: a paid plan to the plan's Price on the license's own price list, a paid plan to Free by
 * ending the subscription, from which the license continues as Free. A Free license whose figures still set Free asks
 * staff to approve its next year; one whose figures set a paid plan must buy that plan by the anniversary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RevenueConfirmationService {

    private final LicenseRepository licenseRepository;
    private final RevenueConfirmationRepository confirmationRepository;
    private final StripeBillingService billingService;
    private final FreeLicenseService freeLicenseService;
    private final LicenseService licenseService;

    /**
     * Records the figures the calling contact confirms for the anniversary its license's figures are due for, and
     * applies the plan they set at the license's next anniversary.
     *
     * @return the confirmation, or a failure with {@link IllegalArgumentException} when the figures are not valid or no
     *         license of the caller's customer has the id, {@link IllegalStateException} when no confirmation is due, or
     *         {@link AlreadyExistsException} when the figures are confirmed already
     */
    public Future<RevenueConfirmation> confirm(LicenseParticipant caller, String licenseId, CompanyFigures figures) {
        Plan plan = Plan.matching(figures);
        return licenseRepository.findById(licenseId).compose(license -> {
            Validate.isTrue(license != null && license.getCustomerId().equals(caller.getCustomerId()), "No license of yours has the id %s", licenseId);
            Validate.isTrue(license.getPlan() != Plan.FREE || figures.getCapitalRaised() != null, "A free license confirms its capital raised too");
            return confirmationRepository.findByLicenseId(licenseId).compose(confirmations -> {
                Instant anniversary = LicenseService.anniversaryToConfirm(license);
                Validate.validState(anniversary != null, "Your figures are due from %s", license.getTermEnd().minus(LicenseService.CONFIRMATION_WINDOW));
                if (confirmations.stream().anyMatch(confirmed -> confirmed.getAnniversary().equals(anniversary))) {
                    throw new AlreadyExistsException(RevenueConfirmationRepository.ALREADY_CONFIRMED);
                }
                RevenueConfirmation confirmation = new RevenueConfirmation()
                        .setId(UUID.randomUUID().toString())
                        .setLicenseId(licenseId)
                        .setAnniversary(anniversary)
                        // the capital raised decides between Free and Small, so only a free license records it
                        .setFigures(new CompanyFigures().setAnnualRevenue(figures.getAnnualRevenue())
                                                        .setCapitalRaised(license.getPlan() == Plan.FREE ? figures.getCapitalRaised() : null))
                        .setPlan(plan)
                        .setLicensePlan(license.getPlan())
                        .setConfirmedByName(caller.getName())
                        .setConfirmedByEmail(caller.getEmail());
                // the move is applied first, so a Stripe failure leaves the figures unconfirmed for another attempt
                return apply(license, confirmation)
                        .compose(v -> confirmationRepository.save(confirmation))
                        .compose(saved -> licenseRepository.removeFlag(licenseId, LicenseFlag.REVENUE_UNCONFIRMED).map(saved))
                        .onSuccess(saved -> log.info("{} confirmed the figures of license {} for {}: {}", caller.getEmail(), licenseId,
                                                     anniversary, plan));
            });
        });
    }

    // what the figures change at the license's next anniversary, which a late confirmation also waits for
    private Future<Void> apply(License license, RevenueConfirmation confirmation) {
        Plan plan = confirmation.getPlan();
        Instant at = license.getTermEnd();
        Future<Void> ret;
        if (license.getPlan() == Plan.FREE && plan == Plan.FREE) {
            ret = freeLicenseService.requestRenewal(license.getCustomerId(), confirmation.getFigures());
        } else if (plan == license.getPlan()) {
            ret = Future.succeededFuture();
        } else {
            Future<Void> moved;
            if (license.getPlan() == Plan.FREE) {
                // a free license buys its paid plan itself, at the price list it keeps
                moved = Future.succeededFuture();
            } else if (plan == Plan.FREE) {
                moved = billingService.endSubscriptionAt(license, at);
            } else {
                moved = billingService.schedulePlanMove(license, plan, at);
            }
            ret = moved.compose(v -> licenseService.emailPlanChanged(license, plan, at)
                                                   .recover(t -> {
                                                       log.error("The plan change of license {} was applied but could not be emailed", license.getId(), t);
                                                       return Future.succeededFuture();
                                                   }));
        }
        return ret;
    }
}
