package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.FreeLicenseRequestStatus;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.model.NewLicense;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.FreeLicenseRequestRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Free licenses: an organization requests one with the figures it certifies about itself, staff approve or reject
 * the request, and approval creates the license and issues its key. A free license renews the same way: the customer
 * re-certifies and staff approve again, which issues the next term's key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreeLicenseService {

    private final CustomerService customerService;
    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final FreeLicenseRequestRepository requestRepository;
    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final EmailService emailService;

    /**
     * Records a new organization's request, creating the customer, its contact and its acceptance of the terms.
     */
    public Future<FreeLicenseRequest> request(String organization, String contactName, String contactEmail,
                                              FreeLicenseCertification certification) {
        validate(certification);
        return customerService.createCustomer(organization, contactName, contactEmail)
                              .compose(customer -> saveRequest(customer.getId(), certification));
    }

    /**
     * Records an existing free licensee's re-certification for its next term.
     *
     * @throws IllegalStateException when the customer has no free license
     * @throws AlreadyExistsException when another request of the customer's is open: pending, or approved with its key
     *                                still being issued
     */
    public Future<FreeLicenseRequest> requestRenewal(String customerId, FreeLicenseCertification certification) {
        validate(certification);
        return licenseRepository.findFreeLicense(customerId).compose(license -> {
            Validate.validState(license != null, "The customer %s has no free license to renew", customerId);
            return requestForCustomer(customerId, certification);
        });
    }

    /**
     * Records a free license request for an existing customer, as a signed-in customer makes one: a first request, or a
     * renewal when the customer holds a free license already.
     *
     * @return the request, or a failure with {@link AlreadyExistsException} when another request of the customer's is
     *         open: pending, or approved with its key still being issued
     */
    public Future<FreeLicenseRequest> requestForCustomer(String customerId, FreeLicenseCertification certification) {
        validate(certification);
        return saveRequest(customerId, certification);
    }

    /**
     * The requests awaiting a decision, oldest first, including approvals that stopped before their key was issued.
     */
    public Future<List<FreeLicenseRequest>> findPending() {
        return requestRepository.findAwaitingDecision();
    }

    /**
     * Approves a pending request: creates the free license, or issues the next term's key of the one the customer has,
     * and emails the key. Approving a request whose approval stopped before its key was issued completes it.
     *
     * @param requestId  the request
     * @param reviewedBy the staff member approving
     */
    public Future<FreeLicenseRequest> approve(String requestId, String reviewedBy) {
        // The approval is claimed first, so a second decision on the request finds nothing to decide. A failure while
        // issuing reopens it, since nothing was issued; a server that stopped mid-way leaves a claim that expires, and
        // approving again then finds the key that approval issued, or issues it. A failed email leaves the approval
        // complete, and the key is resent from the customer page.
        return requestRepository.claimApproval(requestId, reviewedBy)
                .map(request -> {
                    Validate.validState(request != null, "The free license request %s has already been decided, or is being approved", requestId);
                    return request;
                })
                .compose(request -> issueFor(request)
                        .recover(t -> requestRepository.reopen(request.getId()).compose(v -> Future.failedFuture(t)))
                        .compose(issued -> requestRepository.markIssued(request.getId(), issued.key().getJti())
                                                            .compose(v -> email(issued))
                                                            .map(request)))
                .onSuccess(request -> log.info("{} approved the free license request {}", reviewedBy, requestId));
    }

    /**
     * Rejects a pending request and tells the customer's contacts.
     */
    public Future<FreeLicenseRequest> reject(String requestId, String reviewedBy) {
        return requestRepository.reject(requestId, reviewedBy)
                .map(request -> {
                    Validate.validState(request != null, "The free license request %s is not pending", requestId);
                    return request;
                })
                .compose(request -> customerRepository.findById(request.getCustomerId())
                                                      .compose(customer -> contactRepository.findByCustomerId(customer.getId())
                                                                                            .compose(contacts -> {
                                                                                                Future<Void> sent = Future.succeededFuture();
                                                                                                for (var contact : contacts) {
                                                                                                    sent = sent.compose(x -> emailService.sendFreeRequestRejected(contact, customer.getLegalName()));
                                                                                                }
                                                                                                return sent;
                                                                                            }))
                                                      .map(request))
                .onSuccess(request -> log.info("{} rejected the free license request {}", reviewedBy, requestId));
    }

    // The key this approval owes: none issued since it was approved means one is issued now, creating the license for a
    // first request. A license whose first key was never issued, as when signing failed on an earlier approval, gets
    // that first key with its term starting now; one that has a key is renewed.
    private Future<NewLicense> issueFor(FreeLicenseRequest request) {
        return licenseRepository.findFreeLicense(request.getCustomerId()).compose(license -> {
            Future<NewLicense> ret;
            if (license == null) {
                ret = customerRepository.findById(request.getCustomerId())
                                        .compose(customer -> licenseService.createLicense(customer, Plan.FREE, null, null, null));
            } else {
                ret = licenseService.keysOf(license.getId()).compose(keys -> {
                    Future<NewLicense> issued;
                    // keys come newest first, so the first one says whether this approval already issued its key
                    if (!keys.isEmpty() && !keys.getFirst().getIssuedAt().isBefore(request.getReviewedAt())) {
                        issued = Future.succeededFuture(new NewLicense(license, keys.getFirst()));
                    } else if (keys.isEmpty()) {
                        issued = licenseService.issueFirstKey(license);
                    } else {
                        issued = licenseService.issueRenewalKey(license);
                    }
                    return issued;
                });
            }
            return ret;
        });
    }

    private Future<Void> email(NewLicense issued) {
        return licenseService.emailFreeApproval(issued.license(), issued.key())
                             .recover(t -> {
                                 log.error("The key {} was issued but could not be emailed; resend it from the customer page",
                                           issued.key().getJti(), t);
                                 return Future.succeededFuture();
                             });
    }



    private Future<FreeLicenseRequest> saveRequest(String customerId, FreeLicenseCertification certification) {
        return requestRepository.save(new FreeLicenseRequest()
                                              .setId(UUID.randomUUID().toString())
                                              .setCustomerId(customerId)
                                              .setAnnualRevenue(certification.getAnnualRevenue())
                                              .setCapitalRaised(certification.getCapitalRaised())
                                              .setIntendedUse(certification.getIntendedUse().trim())
                                              .setStatus(FreeLicenseRequestStatus.PENDING));
    }

    private static void validate(FreeLicenseCertification certification) {
        Validate.notNull(certification, "The certification is required");
        Validate.isTrue(isAmount(certification.getAnnualRevenue()), "The annual revenue must be zero or more");
        Validate.isTrue(isAmount(certification.getCapitalRaised()), "The capital raised must be zero or more");
        Validate.notBlank(certification.getIntendedUse(), "The intended use is required");
    }

    private static boolean isAmount(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }
}
