package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.FreeLicenseRequestStatus;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.FreeLicenseRequestRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Free licenses: an organization requests one with the figures it certifies about itself, staff approve or reject
 * the request, and approval creates the license for one cluster. A free license renews the same way: the customer
 * re-certifies and staff approve again, which issues its clusters' keys for the next term.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreeLicenseService {

    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final FreeLicenseRequestRepository requestRepository;
    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final EmailService emailService;
    private final StripeBillingService billingService;

    /**
     * Records a free license request of the customer, which holds no active free license.
     *
     * @return the request, or a failure with {@link IllegalStateException} when the customer holds an active free license,
     *         or with {@link AlreadyExistsException} when another request of the customer's is open: pending, or being
     *         approved
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
     * The requests awaiting a decision, oldest first, including approvals that stopped before the license was created
     * or renewed.
     */
    public Future<List<FreeLicenseRequest>> findPending() {
        return requestRepository.findAwaitingDecision();
    }

    /**
     * Approves a pending request: creates the free license, or starts the next year of the one the customer has and
     * issues its clusters' keys, and tells the customer's contacts. Approving a request whose approval stopped before
     * its license was recorded completes it.
     *
     * @param requestId  the request
     * @param reviewedBy the staff member approving
     */
    public Future<FreeLicenseRequest> approve(String requestId, String reviewedBy) {
        // The approval is claimed first, so a second decision on the request finds nothing to decide. The license is
        // created, or its year moved, in the same statement that completes the approval, so a failure before that reopens
        // the request and a server that stopped mid-way leaves a claim that expires with nothing done. The keys and the
        // email follow; a failure there leaves the approval complete, and the customer sees its keys in the account.
        return requestRepository.claimApproval(requestId, reviewedBy)
                .map(request -> {
                    Validate.validState(request != null, "The free license request %s has already been decided, or is being approved", requestId);
                    return request;
                })
                .compose(request -> licenseFor(request)
                        .recover(t -> requestRepository.reopen(request.getId()).compose(v -> Future.failedFuture(t)))
                        .compose(license -> licenseService.issueExtendedKeys(license)
                                                          .compose(issued -> licenseService.emailFreeApproval(license))
                                                          .recover(t -> {
                                                              log.error("The free license {} was approved, but its keys or email failed", license.getId(), t);
                                                              return Future.succeededFuture();
                                                          })
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
                // the decision stands once recorded; a notice that fails is logged, as an approval's email is
                .compose(request -> customerService.emailContacts(request.getCustomerId(), emailService::sendFreeRequestRejected)
                                                   .recover(t -> {
                                                       log.error("The free license request {} was rejected but its contacts could not be told", requestId, t);
                                                       return Future.succeededFuture();
                                                   })
                                                   .map(request))
                .onSuccess(request -> log.info("{} rejected the free license request {}", reviewedBy, requestId));
    }

    // The license this approval creates, for a first request, or whose next year it starts
    private Future<License> licenseFor(FreeLicenseRequest request) {
        return licenseRepository.findFreeLicense(request.getCustomerId()).compose(license -> license == null
                ? customerRepository.findById(request.getCustomerId())
                                    .compose(customer -> billingService.currentPriceList()
                                                                       .compose(priceList -> licenseService.createFreeLicense(customer, priceList, request.getId())))
                : licenseService.renewFreeTerm(license, request.getId()));
    }

    private static void validate(FreeLicenseCertification certification) {
        Validate.notNull(certification, "The certification is required");
        Plan.validate(certification.getFigures());
        Validate.isTrue(certification.getFigures().getCapitalRaised() != null && certification.getFigures().getCapitalRaised().signum() >= 0,
                        "The capital raised must be zero or more");
        Validate.notBlank(certification.getIntendedUse(), "The intended use is required");
        Plan plan = Plan.matching(certification.getFigures());
        Validate.isTrue(plan == Plan.FREE, "Your company's figures fit the %s plan, which is bought rather than requested", plan);
    }
}
