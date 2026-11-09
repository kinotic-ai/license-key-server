package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.model.CaseStudyStatus;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.DesignPartner;
import org.kinotic.licenses.api.model.DesignPartnerApplication;
import org.kinotic.licenses.api.model.DesignPartnerApplicationForm;
import org.kinotic.licenses.api.model.DesignPartnerApplicationStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.DesignPartnerApplicationRepository;
import org.kinotic.licenses.internal.api.repositories.DesignPartnerRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Design partners: a company on a paid plan applies, staff approve or reject the application, and approval makes it a
 * design partner, emailed the link that starts its subscription at half off for 36 months. Staff then track what may
 * be published about it, each publication approved by the customer, and its case study, which is published only once
 * its publication is approved.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesignPartnerService {

    /** The page an approved applicant starts its discounted subscription from, signed in, given the application id. */
    public static final String CHECKOUT_PATH = "/design-partner/checkout?application=";

    private final KinoticLicensesProperties properties;
    private final DesignPartnerApplicationRepository applicationRepository;
    private final DesignPartnerRepository designPartnerRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final StripeBillingService billingService;
    private final EmailService emailService;

    /**
     * Records an application of the customer to be a design partner, on the paid plan its figures set.
     *
     * @return the pending application, or a failure with {@link IllegalArgumentException} when the form is not valid or
     *         the figures set Free, or {@link org.kinotic.core.api.exceptions.AlreadyExistsException} when another
     *         application of the customer's is pending
     */
    public Future<DesignPartnerApplication> apply(String customerId, DesignPartnerApplicationForm form) {
        Validate.isTrue(form != null, "The application is required");
        Plan plan = Plan.matching(form.getFigures());
        Validate.isTrue(plan != Plan.FREE, "Your company qualifies for a free license; design partners are on a paid plan");
        Validate.isTrue(form.getBillingPeriod() != null, "The billing period is required");
        Validate.notBlank(form.getIntendedUse(), "What you use Kinotic for is required");
        return applicationRepository.save(new DesignPartnerApplication()
                                                  .setId(UUID.randomUUID().toString())
                                                  .setCustomerId(customerId)
                                                  .setPlan(plan)
                                                  .setBillingPeriod(form.getBillingPeriod())
                                                  .setFigures(form.getFigures())
                                                  .setIntendedUse(form.getIntendedUse().trim())
                                                  .setStatus(DesignPartnerApplicationStatus.PENDING));
    }

    /**
     * The applications awaiting review, oldest first.
     */
    public Future<List<DesignPartnerApplication>> findPending() {
        return applicationRepository.findPending();
    }

    /**
     * Approves a pending application: the customer becomes a design partner and its contacts are emailed the link that
     * starts its discounted subscription.
     *
     * @throws IllegalStateException when the application is not pending
     */
    public Future<DesignPartner> approve(String applicationId, String reviewedBy) {
        return decide(applicationId, DesignPartnerApplicationStatus.APPROVED, reviewedBy).compose(application ->
                designPartnerRepository.create(application.getCustomerId(), application.getId(), application.getReviewedAt())
                                       .compose(partner -> notify(application, (contact, organization) -> emailService.sendDesignPartnerApproved(
                                               contact, organization, application,
                                               properties.getLicenses().getPublicBaseUrl() + CHECKOUT_PATH + application.getId()))
                                               .map(partner)));
    }

    /**
     * Rejects a pending application and tells the customer's contacts.
     *
     * @throws IllegalStateException when the application is not pending
     */
    public Future<DesignPartnerApplication> reject(String applicationId, String reviewedBy) {
        return decide(applicationId, DesignPartnerApplicationStatus.REJECTED, reviewedBy)
                .compose(application -> notify(application, emailService::sendDesignPartnerRejected).map(application));
    }

    /**
     * Starts the discounted subscription of the caller's approved application.
     *
     * @return the Stripe Checkout URL, or a failure with {@link IllegalArgumentException} when the caller's customer has
     *         no approved application with the id, or {@link IllegalStateException} when its subscription is bought
     *         already
     */
    public Future<String> startCheckout(LicenseParticipant caller, String applicationId) {
        return applicationRepository.findById(applicationId).compose(application -> {
            Validate.isTrue(application != null && application.getCustomerId().equals(caller.getCustomerId())
                                    && application.getStatus() == DesignPartnerApplicationStatus.APPROVED,
                            "No approved design partner application of yours has the id %s", applicationId);
            return designPartnerRepository.findByCustomerId(caller.getCustomerId()).compose(partner -> {
                Validate.validState(partner == null || partner.getLicenseId() == null, "Your design partner subscription is bought already");
                return customerRepository.findById(caller.getCustomerId())
                                         .compose(customer -> billingService.startDesignPartnerCheckout(customer, caller, application));
            });
        });
    }

    /**
     * Every design partner with its publications, newest first.
     */
    public Future<List<DesignPartner>> findDesignPartners() {
        return designPartnerRepository.findAll();
    }

    /**
     * Records that the customer approved a publication.
     *
     * @param approvedBy the person at the customer who approved it
     * @throws IllegalArgumentException when no publication has the id
     */
    public Future<DesignPartner> approvePublication(String publicationId, String approvedBy, String recordedBy) {
        Validate.notBlank(approvedBy, "Who at the customer approved it is required");
        return designPartnerRepository.approvePublication(publicationId, approvedBy.trim()).map(partner -> {
            Validate.isTrue(partner != null, "No publication has the id %s", publicationId);
            log.info("{} recorded that {} approved the publication {}", recordedBy, approvedBy, publicationId);
            return partner;
        });
    }

    /**
     * Sets a design partner's case study status.
     *
     * @throws IllegalStateException when the status is {@link CaseStudyStatus#PUBLISHED} and the customer has not
     *                               approved the case study, or no design partner has the id
     */
    public Future<DesignPartner> setCaseStudyStatus(String designPartnerId, CaseStudyStatus status, String recordedBy) {
        Validate.isTrue(status != null, "The status is required");
        return designPartnerRepository.setCaseStudyStatus(designPartnerId, status).map(partner -> {
            Validate.validState(partner != null, "The case study of design partner %s cannot be published before the customer approves it",
                                designPartnerId);
            log.info("{} set the case study of design partner {} to {}", recordedBy, designPartnerId, status);
            return partner;
        });
    }

    private Future<DesignPartnerApplication> decide(String applicationId, DesignPartnerApplicationStatus status, String reviewedBy) {
        return applicationRepository.decide(applicationId, status, reviewedBy).map(application -> {
            Validate.validState(application != null, "The design partner application %s is not pending", applicationId);
            log.info("{} set the design partner application {} to {}", reviewedBy, applicationId, status);
            return application;
        });
    }

    // the decision stands once recorded; a notice that fails is logged, and staff tell the customer themselves
    private Future<Void> notify(DesignPartnerApplication application,
                                BiFunction<CustomerContact, String, Future<Void>> send) {
        return customerService.emailContacts(application.getCustomerId(), send)
                              .recover(t -> {
                                  log.error("The design partner application {} was decided but its contacts could not be told", application.getId(), t);
                                  return Future.succeededFuture();
                              });
    }
}
