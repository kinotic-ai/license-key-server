package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.api.model.CustomerAccount;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseWithKeys;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.services.CustomerAccountService;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.FreeLicenseRequestRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The customer account, scoped to the customer the calling contact belongs to.
 */
@Component
@RequiredArgsConstructor
public class DefaultCustomerAccountService implements CustomerAccountService {

    private final SecurityContext securityContext;
    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final LicenseRepository licenseRepository;
    private final FreeLicenseRequestRepository requestRepository;
    private final LicenseService licenseService;
    private final StripeBillingService billingService;
    private final FreeLicenseService freeLicenseService;

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
                                 .compose(licenses -> withKeys(licenses, licenseService))
                                 .compose(licenses -> {
                                     ret.setLicenses(licenses);
                                     return requestRepository.findByCustomerId(customerId);
                                 })
                                 .map(ret::setFreeLicenseRequests);
    }

    @Override
    public Future<String> createPortalSession() {
        return customerRepository.findById(callingCustomerId()).compose(billingService::createPortalSession);
    }

    @Override
    public Future<FreeLicenseRequest> requestFreeRenewal(FreeLicenseCertification certification) {
        return freeLicenseService.requestRenewal(callingCustomerId(), certification);
    }

    private String callingCustomerId() {
        return securityContext.requireParticipant(LicenseParticipant.class).getCustomerId();
    }

    /**
     * Each license with its keys, in the order given.
     */
    static Future<List<LicenseWithKeys>> withKeys(List<License> licenses, LicenseService licenseService) {
        Future<List<LicenseWithKeys>> ret = Future.succeededFuture(new ArrayList<>());
        for (License license : licenses) {
            ret = ret.compose(list -> licenseService.keysOf(license.getId()).map(keys -> {
                list.add(new LicenseWithKeys().setLicense(license).setKeys(keys));
                return list;
            }));
        }
        return ret;
    }
}
