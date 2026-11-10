package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.TrialRequest;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.internal.api.model.NewLicense;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Trials of the paid plans: a customer that has never held a paid license or a trial, and holds no active free license,
 * starts a {@link LicenseService#TRIAL} trial of the paid plan its figures set, with no payment details. Buying a plan
 * before the trial ends moves the trial onto the subscription; otherwise it expires.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrialService {

    private final CustomerRepository customerRepository;
    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final StripeBillingService billingService;
    private final CustomerService customerService;

    /**
     * Starts the calling contact's customer's trial, records that the contact accepted the current terms, and emails
     * the trial's key.
     *
     * @return the trial, or a failure with {@link IllegalArgumentException} when the plan is Free or the figures set
     *         another plan, with {@link IllegalStateException} when the customer has held a paid license or a trial, or
     *         holds an active free license, or with {@link org.kinotic.core.api.exceptions.AlreadyExistsException} when
     *         another trial of the customer's started meanwhile
     */
    public Future<License> start(LicenseParticipant caller, TrialRequest request) {
        Validate.isTrue(request != null, "The trial is required");
        Plan.requirePaidMatching(request.getPlan(), request.getFigures());
        String customerId = caller.getCustomerId();
        return licenseRepository.findByCustomerId(customerId)
                                .compose(licenses -> {
                                    Validate.validState(isAvailable(licenses), "A trial is for an account that has never had a"
                                            + " trial or a paid plan, and holds no active free license. Buy a plan from your account.");
                                    // recorded before the trial, which the customer has once, so a failure here leaves the
                                    // trial still to start
                                    return customerService.recordAcceptance(customerId, caller.getName(), caller.getEmail(), null);
                                })
                                .compose(accepted -> customerRepository.findById(customerId))
                                .compose(customer -> billingService.currentPriceList()
                                                                   .compose(priceList -> licenseService.createTrial(customer, request.getPlan(), priceList)))
                                .compose(created -> {
                                    log.info("The customer {} started a trial of the {} plan, license {}", customerId, request.getPlan(),
                                             created.license().getId());
                                    return emailKey(created).map(created.license());
                                });
    }

    // the trial stands once started; a failed email leaves the key in the account, which the customer is sent to
    private Future<Void> emailKey(NewLicense created) {
        return licenseService.emailTrialStarted(created.license(), created.key())
                             .recover(t -> {
                                 log.error("The key {} of the trial {} could not be emailed", created.key().getJti(),
                                           created.license().getId(), t);
                                 return Future.succeededFuture();
                             });
    }

    /**
     * Whether a customer holding these licenses may start a trial: none is a paid license or a trial, and none is an
     * active free license.
     *
     * @param licenses every license of the customer
     */
    public static boolean isAvailable(List<License> licenses) {
        // a paid license that moved to Free shows plan FREE, and is refused as the active free license it is
        return licenses.stream().allMatch(license -> license.getPlan() == Plan.FREE && license.getStatus() != LicenseStatus.ACTIVE);
    }
}
