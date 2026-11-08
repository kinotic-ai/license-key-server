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
     * @throws AlreadyExistsException when a request of the customer's is already pending
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
     * @return the request, or a failure with {@link AlreadyExistsException} when a request of the customer's is
     *         already pending
     */
    public Future<FreeLicenseRequest> requestForCustomer(String customerId, FreeLicenseCertification certification) {
        validate(certification);
        return requestRepository.findByCustomerId(customerId).compose(requests -> requests.stream().anyMatch(
                request -> request.getStatus() == FreeLicenseRequestStatus.PENDING)
                ? Future.failedFuture(new AlreadyExistsException("A free license request of this account is already being reviewed"))
                : saveRequest(customerId, certification));
    }

    public Future<List<FreeLicenseRequest>> findPending() {
        return requestRepository.findByStatus(FreeLicenseRequestStatus.PENDING);
    }

    /**
     * Approves a pending request: creates the free license, or renews the one the customer has, issues the key and
     * emails it.
     *
     * @param requestId  the request
     * @param reviewedBy the staff member approving
     */
    public Future<FreeLicenseRequest> approve(String requestId, String reviewedBy) {
        // The decision is recorded first, so a second decision on the request finds nothing to decide. Issuing then
        // either creates the license or starts its next term; a failure there reopens the request, since nothing was
        // issued, whereas a failed email leaves it approved and the key is resent from the customer page.
        return decide(requestId, FreeLicenseRequestStatus.APPROVED, reviewedBy)
                .compose(request -> licenseRepository.findFreeLicense(request.getCustomerId())
                                                     .compose(existing -> existing != null ? issueFor(existing) : create(request))
                                                     .recover(t -> reopen(request).compose(v -> Future.failedFuture(t)))
                                                     .compose(issued -> email(issued).map(request)))
                .onSuccess(request -> log.info("{} approved the free license request {}", reviewedBy, requestId));
    }

    /**
     * Rejects a pending request and tells the customer's contacts.
     */
    public Future<FreeLicenseRequest> reject(String requestId, String reviewedBy) {
        return decide(requestId, FreeLicenseRequestStatus.REJECTED, reviewedBy)
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

    private Future<NewLicense> create(FreeLicenseRequest request) {
        return customerRepository.findById(request.getCustomerId())
                                 .compose(customer -> licenseService.createLicense(customer, Plan.FREE, null, null, null));
    }

    // a license whose first key was never issued, as when signing failed on an earlier approval, gets that first key
    // with its term starting now; one that has a key is renewed
    private Future<NewLicense> issueFor(License license) {
        return licenseService.keysOf(license.getId()).compose(keys -> keys.isEmpty()
                ? licenseService.issueFirstKey(license)
                : licenseService.issueRenewalKey(license));
    }

    private Future<Void> email(NewLicense issued) {
        return licenseService.emailFreeApproval(issued.license(), issued.key())
                             .recover(t -> {
                                 log.error("The key {} was issued but could not be emailed; resend it from the customer page",
                                           issued.key().getJti(), t);
                                 return Future.succeededFuture();
                             });
    }

    private Future<FreeLicenseRequest> decide(String requestId, FreeLicenseRequestStatus decision, String reviewedBy) {
        return requestRepository.findById(requestId).compose(request -> {
            Validate.isTrue(request != null, "No free license request has the id %s", requestId);
            return requestRepository.review(requestId, decision, reviewedBy, Instant.now());
        }).map(reviewed -> {
            Validate.validState(reviewed != null, "The free license request %s has already been decided", requestId);
            return reviewed;
        });
    }

    private Future<Void> reopen(FreeLicenseRequest request) {
        return requestRepository.reopen(request.getId());
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
