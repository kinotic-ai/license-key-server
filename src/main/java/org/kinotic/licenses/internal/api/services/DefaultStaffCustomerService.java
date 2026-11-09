package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.api.model.CustomerDetail;
import org.kinotic.licenses.api.model.CustomerSummary;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.services.StaffCustomerService;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The staff dashboard's customers and license actions. Every action is logged with the staff member taking it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultStaffCustomerService implements StaffCustomerService {

    private final SecurityContext securityContext;
    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final LicenseRepository licenseRepository;
    private final AgreementAcceptanceRepository acceptanceRepository;
    private final LicenseService licenseService;
    private final LicenseSigningKey signingKey;

    @Override
    public Future<List<CustomerSummary>> findCustomerSummaries() {
        return customerRepository.findSummaries();
    }

    @Override
    public Future<CustomerDetail> getCustomerDetail(String customerId) {
        CustomerDetail ret = new CustomerDetail();
        return customerRepository.findById(customerId)
                                 .compose(customer -> {
                                     Validate.isTrue(customer != null, "No customer has the id %s", customerId);
                                     ret.setCustomer(customer);
                                     return contactRepository.findByCustomerId(customerId);
                                 })
                                 .compose(contacts -> {
                                     ret.setContacts(contacts);
                                     return licenseRepository.findByCustomerId(customerId);
                                 })
                                 .compose(licenses -> DefaultCustomerAccountService.withKeys(licenses, licenseService))
                                 .compose(licenses -> {
                                     ret.setLicenses(licenses);
                                     return acceptanceRepository.findByCustomerId(customerId);
                                 })
                                 .map(ret::setAcceptances);
    }

    @Override
    public Future<License> revokeLicense(String licenseId) {
        return license(licenseId).compose(licenseService::revoke)
                                 .onSuccess(license -> log.info("{} revoked the license {}", staff(), licenseId));
    }

    @Override
    public Future<IssuedKey> issueReplacementKey(String licenseId) {
        // the key is issued either way; a failed email is retried with resendKey rather than by issuing another key
        return license(licenseId).compose(license -> licenseService.issueReplacementKey(license)
                                                                   .compose(key -> licenseService.emailNewKey(license, key)
                                                                                                 .recover(t -> {
                                                                                                     log.error("The replacement key {} was issued but could not be emailed", key.getJti(), t);
                                                                                                     return Future.succeededFuture();
                                                                                                 })
                                                                                                 .map(key)))
                                 .onSuccess(key -> log.info("{} issued the replacement key {} for the license {}", staff(), key.getJti(), licenseId));
    }

    @Override
    public Future<Void> resendKey(String licenseId) {
        return license(licenseId).compose(licenseService::resendLatestKey)
                                 .onSuccess(v -> log.info("{} resent the key of the license {}", staff(), licenseId));
    }

    @Override
    public Future<IssuedKey> issueTrialKey() {
        return licenseService.issueTrialKey()
                             .onSuccess(key -> log.info("{} issued the trial key {}", staff(), key.getJti()));
    }

    @Override
    public Future<Map<String, Object>> getSigningKey() {
        return Future.succeededFuture(signingKey.publicJwk().getMap());
    }

    private Future<License> license(String licenseId) {
        return licenseRepository.findById(licenseId).map(license -> {
            Validate.isTrue(license != null, "No license has the id %s", licenseId);
            return license;
        });
    }

    private String staff() {
        return securityContext.requireParticipant(LicenseParticipant.class).getEmail();
    }
}
