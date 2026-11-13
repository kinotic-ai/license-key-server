package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.CustomerAccount;
import org.kinotic.licenses.api.model.DesignPartnerApplication;
import org.kinotic.licenses.api.model.DesignPartnerApplicationForm;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.PlanPrice;
import org.kinotic.licenses.api.model.PublicityConsent;
import org.kinotic.licenses.api.model.RevenueConfirmation;
import org.kinotic.licenses.api.model.CheckoutRequest;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.TrialRequest;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.services.CustomerAccountService;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.DesignPartnerApplicationRepository;
import org.kinotic.licenses.internal.api.repositories.DesignPartnerRepository;
import org.kinotic.licenses.internal.api.repositories.FreeLicenseRequestRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.repositories.LicensedClusterRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The customer account, scoped to the customer the calling contact belongs to.
 */
@Component
@RequiredArgsConstructor
public class DefaultCustomerAccountService implements CustomerAccountService {

    // a cluster id is the UID Kubernetes gives the cluster's kube-system namespace
    private static final Pattern CLUSTER_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int NAME_LENGTH = 100;

    private final SecurityContext securityContext;
    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final LicenseRepository licenseRepository;
    private final LicensedClusterRepository clusterRepository;
    private final FreeLicenseRequestRepository requestRepository;
    private final LicenseService licenseService;
    private final StripeBillingService billingService;
    private final FreeLicenseService freeLicenseService;
    private final CustomerService customerService;
    private final RevenueConfirmationService confirmationService;
    private final DesignPartnerService designPartnerService;
    private final DesignPartnerRepository designPartnerRepository;
    private final DesignPartnerApplicationRepository applicationRepository;
    private final TrialService trialService;

    @Override
    public Future<CustomerAccount> getAccount() {
        String customerId = callingCustomerId();
        CustomerAccount ret = new CustomerAccount();
        return customerRepository.findById(customerId)
                                 .compose(customer -> {
                                     ret.setCustomer(customer);
                                     return contactRepository.findByCustomerId(customerId);
                                 })
                                 .compose(contacts -> {
                                     ret.setContacts(contacts);
                                     return licenseRepository.findByCustomerId(customerId);
                                 })
                                 .compose(licenses -> {
                                     ret.setTrialAvailable(TrialService.isAvailable(licenses));
                                     return licenseService.withKeys(licenses);
                                 })
                                 .compose(licenses -> {
                                     ret.setLicenses(licenses);
                                     return requestRepository.findByCustomerId(customerId);
                                 })
                                 .compose(requests -> {
                                     ret.setFreeLicenseRequests(requests);
                                     return applicationRepository.findByCustomerId(customerId);
                                 })
                                 .compose(applications -> {
                                     ret.setDesignPartnerApplications(applications);
                                     return designPartnerRepository.findByCustomerId(customerId);
                                 })
                                 .map(ret::setDesignPartner);
    }

    @Override
    public Future<String> createPortalSession() {
        return customerRepository.findById(callingCustomerId()).compose(billingService::createPortalSession);
    }

    @Override
    public Future<List<PlanPrice>> findPrices() {
        return customerRepository.findById(callingCustomerId()).compose(billingService::findPrices);
    }

    @Override
    public Future<Plan> findPlanFor(CompanyFigures figures) {
        return Future.succeededFuture(Plan.matching(figures));
    }

    @Override
    public Future<String> startCheckout(CheckoutRequest request, String termsVersion) {
        LicenseParticipant caller = caller();
        requireCurrentTerms(termsVersion);
        return customerRepository.findById(caller.getCustomerId())
                                 .compose(customer -> billingService.startCheckout(customer, caller, request))
                                 .compose(url -> recordAcceptance(caller, null).map(url));
    }

    @Override
    public Future<License> startTrial(TrialRequest request, String termsVersion) {
        requireCurrentTerms(termsVersion);
        return trialService.start(caller(), request);
    }

    @Override
    public Future<LicensedCluster> addCluster(String licenseId, String clusterId, String name) {
        Validate.isTrue(clusterId != null && CLUSTER_ID.matcher(clusterId.trim()).matches(),
                        "The cluster id is the one your system console's License page shows");
        Validate.isTrue(StringUtils.isNotBlank(name) && name.trim().length() <= NAME_LENGTH,
                        "A cluster needs a name of at most %s characters", NAME_LENGTH);
        LicensedCluster cluster = new LicensedCluster().setId(UUID.randomUUID().toString())
                                                       .setLicenseId(licenseId)
                                                       .setClusterId(clusterId.trim().toLowerCase(Locale.ROOT))
                                                       .setName(name.trim());
        // a cluster whose first key cannot be issued gives its seat back, so adding it again starts over
        return callersLicense(licenseId).compose(license -> clusterRepository.add(cluster).compose(added -> licenseService.issueKey(license, added)
                .map(added)
                .recover(t -> clusterRepository.remove(added.getId()).compose(v -> Future.failedFuture(t)))));
    }

    @Override
    public Future<DesignPartnerApplication> applyAsDesignPartner(DesignPartnerApplicationForm form, String termsVersion) {
        LicenseParticipant caller = caller();
        requireCurrentTerms(termsVersion);
        return designPartnerService.apply(caller.getCustomerId(), form)
                                   .compose(application -> recordAcceptance(caller, PublicityConsent.DESIGN_PARTNER).map(application));
    }

    @Override
    public Future<String> startDesignPartnerCheckout(String applicationId) {
        return designPartnerService.startCheckout(caller(), applicationId);
    }

    @Override
    public Future<RevenueConfirmation> confirmRevenue(String licenseId, CompanyFigures figures) {
        return confirmationService.confirm(caller(), licenseId, figures);
    }

    @Override
    public Future<FreeLicenseRequest> requestFreeLicense(FreeLicenseCertification certification, String termsVersion) {
        LicenseParticipant caller = caller();
        requireCurrentTerms(termsVersion);
        return freeLicenseService.request(caller.getCustomerId(), certification)
                                 .compose(request -> recordAcceptance(caller, PublicityConsent.CUSTOMER_NAME).map(request));
    }

    // the calling customer's license with the id
    private Future<License> callersLicense(String licenseId) {
        String customerId = callingCustomerId();
        return licenseRepository.findById(licenseId).map(license -> {
            Validate.isTrue(license != null && license.getCustomerId().equals(customerId), "Your account has no license %s", licenseId);
            return license;
        });
    }

    private String callingCustomerId() {
        return caller().getCustomerId();
    }

    private LicenseParticipant caller() {
        return securityContext.requireParticipant(LicenseParticipant.class);
    }

    private Future<Void> recordAcceptance(LicenseParticipant caller, PublicityConsent publicityConsent) {
        return customerService.recordAcceptance(caller.getCustomerId(), caller.getName(), caller.getEmail(), publicityConsent).mapEmpty();
    }

    private static void requireCurrentTerms(String termsVersion) {
        Validate.isTrue(AgreementTerms.VERSION.equals(termsVersion), "The terms must be accepted in their current version, %s",
                        AgreementTerms.VERSION);
    }
}
