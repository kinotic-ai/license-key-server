package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.CheckoutKey;
import org.kinotic.licenses.api.model.CheckoutKeyStatus;
import org.kinotic.licenses.api.model.CustomerAccount;
import org.kinotic.licenses.api.model.DesignPartnerApplication;
import org.kinotic.licenses.api.model.DesignPartnerApplicationForm;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.License;
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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The customer account, scoped to the customer the calling contact belongs to.
 */
@Component
@RequiredArgsConstructor
public class DefaultCustomerAccountService implements CustomerAccountService {

    // how long after a license is created its success page may still show the key
    private static final Duration KEY_SHOW_WINDOW = Duration.ofHours(1);

    // the Checkout Sessions whose key the success page has already been shown, with when; this node is the whole
    // cluster, and an entry past KEY_SHOW_WINDOW is dropped since its key is not shown again anyway
    private final Map<String, Instant> shownKeys = new ConcurrentHashMap<>();

    private final SecurityContext securityContext;
    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final LicenseRepository licenseRepository;
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
        LicenseParticipant caller = caller();
        requireCurrentTerms(termsVersion);
        return trialService.start(caller.getCustomerId(), request)
                           .compose(trial -> recordAcceptance(caller, null).map(trial));
    }

    @Override
    public Future<CheckoutKey> findCheckoutKey(String checkoutSessionId) {
        String customerId = callingCustomerId();
        Instant now = Instant.now();
        shownKeys.values().removeIf(shownAt -> shownAt.plus(KEY_SHOW_WINDOW).isBefore(now));
        return licenseRepository.findByCheckoutSessionId(checkoutSessionId).compose(license -> {
            Future<CheckoutKey> ret;
            // a session that bought nothing for this customer reads as one whose license is not created yet
            if (license == null || !license.getCustomerId().equals(customerId)) {
                ret = Future.succeededFuture(new CheckoutKey().setStatus(CheckoutKeyStatus.PENDING));
            } else {
                ret = licenseService.keysOf(license.getId()).map(keys -> {
                    CheckoutKey key;
                    // a license with no key yet is one whose payment is still clearing
                    if (keys.isEmpty()) {
                        key = new CheckoutKey().setStatus(CheckoutKeyStatus.PENDING);
                    } else if (license.getCreatedAt().plus(KEY_SHOW_WINDOW).isAfter(now) && shownKeys.putIfAbsent(checkoutSessionId, now) == null) {
                        key = new CheckoutKey().setStatus(CheckoutKeyStatus.READY)
                                               .setKey(keys.getFirst().getKey())
                                               .setExpiresAt(keys.getFirst().getExpiresAt());
                    } else {
                        key = new CheckoutKey().setStatus(CheckoutKeyStatus.SHOWN);
                    }
                    return key;
                });
            }
            return ret;
        });
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
