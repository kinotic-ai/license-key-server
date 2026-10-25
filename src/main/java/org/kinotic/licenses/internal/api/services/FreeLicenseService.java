package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.FreeLicenseRequestStatus;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.Plan;
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
     */
    public Future<FreeLicenseRequest> requestRenewal(String customerId, FreeLicenseCertification certification) {
        validate(certification);
        return licenseRepository.findFreeLicense(customerId).compose(license -> {
            Validate.validState(license != null, "The customer %s has no free license to renew", customerId);
            return saveRequest(customerId, certification);
        });
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
        return pending(requestId)
                .compose(request -> licenseRepository.findFreeLicense(request.getCustomerId())
                                                     .compose(existing -> existing != null ? renew(existing) : create(request))
                                                     .compose(v -> requestRepository.review(requestId, FreeLicenseRequestStatus.APPROVED,
                                                                                            reviewedBy, Instant.now())))
                .onSuccess(request -> log.info("{} approved the free license request {}", reviewedBy, requestId));
    }

    /**
     * Rejects a pending request and tells the customer's contacts.
     */
    public Future<FreeLicenseRequest> reject(String requestId, String reviewedBy) {
        return pending(requestId)
                .compose(request -> requestRepository.review(requestId, FreeLicenseRequestStatus.REJECTED, reviewedBy, Instant.now()))
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

    private Future<Void> create(FreeLicenseRequest request) {
        return customerRepository.findById(request.getCustomerId())
                                 .compose(customer -> licenseService.createLicense(customer, Plan.FREE, null, null, null))
                                 .compose(created -> licenseService.emailFreeApproval(created.license(), created.key()));
    }

    private Future<Void> renew(License license) {
        return licenseService.issueRenewalKey(license)
                             .compose(key -> licenseService.emailFreeApproval(license, key));
    }

    private Future<FreeLicenseRequest> pending(String requestId) {
        return requestRepository.findById(requestId).map(request -> {
            Validate.isTrue(request != null, "No free license request has the id %s", requestId);
            Validate.validState(request.getStatus() == FreeLicenseRequestStatus.PENDING,
                                "The free license request %s is already %s", requestId, request.getStatus());
            return request;
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
