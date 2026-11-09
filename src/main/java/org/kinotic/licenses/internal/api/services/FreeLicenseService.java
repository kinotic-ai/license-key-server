package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.FreeLicenseRequestStatus;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.model.NewLicense;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.FreeLicenseRequestRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

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

    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final FreeLicenseRequestRepository requestRepository;
    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final EmailService emailService;
    private final StripeBillingService billingService;

    /**
     * Records a free license request of the customer, which holds no active free license.
     *
     * @return the request, or a failure with {@link IllegalStateException} when the customer holds an active free license,
     *         or with {@link AlreadyExistsException} when another request of the customer's is open: pending, or approved
     *         with its key still being issued
     */
    public Future<FreeLicenseRequest> request(String customerId, FreeLicenseCertification certification) {
        validate(certification);
        certification.setIntendedUse(certification.getIntendedUse().trim());
        return licenseRepository.findFreeLicense(customerId).compose(free -> {
            Validate.validState(free == null || free.getStatus() != LicenseStatus.ACTIVE,
                                "Your free license renews when you confirm your figures for its anniversary in your account");
            return requestRepository.save(new FreeLicenseRequest()
                                              .setId(UUID.randomUUID().toString())
                                              .setCustomerId(customerId)
                                              .setCertification(certification)
                                              .setStatus(FreeLicenseRequestStatus.PENDING));
        });
    }

    /**
     * Asks staff to approve the next year of the customer's free license, with the figures it confirmed for its
     * anniversary and the intended use of its last request. A request of the customer's that is open already stands.
     */
    public Future<Void> requestRenewal(String customerId, CompanyFigures figures) {
        return requestRepository.findByCustomerId(customerId).compose(requests -> {
            String intendedUse = requests.isEmpty() ? "Renewal" : requests.getFirst().getCertification().getIntendedUse();
            return requestRepository.save(new FreeLicenseRequest()
                                                  .setId(UUID.randomUUID().toString())
                                                  .setCustomerId(customerId)
                                                  .setCertification(new FreeLicenseCertification().setFigures(figures).setIntendedUse(intendedUse))
                                                  .setStatus(FreeLicenseRequestStatus.PENDING))
                                    .<Void>mapEmpty()
                                    .recover(t -> t instanceof AlreadyExistsException ? Future.succeededFuture() : Future.failedFuture(t));
        });
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
                                                      // the decision stands once recorded; a notice that fails is logged, as an
                                                      // approval's email is
                                                      .recover(t -> {
                                                          log.error("The free license request {} was rejected but its contacts could not be told", requestId, t);
                                                          return Future.succeededFuture();
                                                      })
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
                                        .compose(customer -> billingService.currentPriceList()
                                                                           .compose(priceList -> licenseService.createFreeLicense(customer, priceList)));
            } else {
                ret = licenseService.keysOf(license.getId()).compose(keys -> {
                    Future<NewLicense> issued;
                    // keys come newest first, so the first one says whether this approval already issued its key
                    if (!keys.isEmpty() && !keys.getFirst().getIssuedAt().isBefore(request.getReviewedAt())) {
                        issued = Future.succeededFuture(new NewLicense(license, keys.getFirst()));
                    } else if (keys.isEmpty()) {
                        issued = licenseService.startFreeTerm(license);
                    } else {
                        issued = licenseService.renewFreeTerm(license);
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

    private static void validate(FreeLicenseCertification certification) {
        Validate.notNull(certification, "The certification is required");
        Plan.validate(certification.getFigures());
        Validate.isTrue(certification.getFigures().getCapitalRaised() != null && certification.getFigures().getCapitalRaised().signum() >= 0,
                        "The capital raised must be zero or more");
        Validate.notBlank(certification.getIntendedUse(), "The intended use is required");
    }
}
