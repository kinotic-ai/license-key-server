package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseKeyClaims;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.model.NewLicense;
import org.kinotic.licenses.internal.api.model.PaidSubscription;
import org.kinotic.licenses.internal.api.model.PriceLookupKey;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The lifecycle of a license and the keys issued for it. A license runs in twelve-month terms; each term's key lives
 * the term plus a short buffer, is emailed to the customer's contacts, and is kept in the append-only key log. A key,
 * once issued, is never withdrawn: it is verified offline and works until it expires, so revoking or cancelling a
 * license only stops new keys.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseService {

    /** A license term; each key lives this long plus {@link #KEY_BUFFER}. */
    public static final Period TERM = Period.ofMonths(12);
    /** How long a key outlives its term, so a renewal pasted in late does not interrupt a deployment. */
    public static final Duration KEY_BUFFER = Duration.ofDays(14);
    /** How far ahead of a term's end its renewal key is issued. */
    public static final Duration RENEWAL_WINDOW = Duration.ofDays(30);

    private final LicenseRepository licenseRepository;
    private final IssuedKeyRepository issuedKeyRepository;
    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final LicenseKeyIssuer keyIssuer;
    private final EmailService emailService;

    /**
     * Creates a paid license whose first payment has cleared, its year starting now on the subscription's price list
     * and the current terms, and issues its first key. The caller emails the key.
     *
     * @return the license and its key
     */
    public Future<NewLicense> createLicense(Customer customer, PaidSubscription subscription) {
        License license = paidLicense(customer, subscription).setStatus(LicenseStatus.ACTIVE).setLastPaymentAt(Instant.now());
        return licenseRepository.save(license)
                                .compose(saved -> issueKey(saved, saved.getTermEnd()).map(key -> new NewLicense(saved, key)));
    }

    /**
     * Creates a free license, its year starting now on the given price list and the current terms, and issues its first
     * key. The caller emails the key.
     *
     * @param priceList the current price list, which the license keeps should it move to a paid plan
     * @return the license and its key
     */
    public Future<NewLicense> createFreeLicense(Customer customer, int priceList) {
        License license = newLicense(customer).setPlan(Plan.FREE).setPriceList(priceList).setStatus(LicenseStatus.ACTIVE);
        return licenseRepository.save(license)
                                .compose(saved -> issueKey(saved, saved.getTermEnd()).map(key -> new NewLicense(saved, key)));
    }

    /**
     * Creates a paid license whose first payment has not cleared, as with a bank debit. It holds no key and is
     * {@link LicenseStatus#PAST_DUE} until {@link #issueFirstKey(License)} is called once the payment succeeds.
     *
     * @return the license
     */
    public Future<License> createAwaitingPayment(Customer customer, PaidSubscription subscription) {
        return licenseRepository.save(paidLicense(customer, subscription).setStatus(LicenseStatus.PAST_DUE));
    }

    /**
     * Issues the first key of a license that has none, its term starting now.
     *
     * @return the license and its key, or a failure with {@link IllegalStateException} when the license is revoked, or
     *         changed since the caller read it
     */
    public Future<NewLicense> issueFirstKey(License license) {
        return issueForTerm(license, Instant.now());
    }

    /**
     * Starts the license's next term and issues its key. The next term starts when the current one ends, or now when
     * that is already past. The current key keeps working until it expires.
     *
     * @return the license and its key, or a failure with {@link IllegalStateException} when the license is revoked, or
     *         changed since the caller read it
     */
    public Future<NewLicense> issueRenewalKey(License license) {
        Instant now = Instant.now();
        return issueForTerm(license, license.getTermEnd().isAfter(now) ? license.getTermEnd() : now);
    }

    /**
     * Issues another key for the license's current term.
     *
     * @return the key, or a failure with {@link IllegalStateException} when the license is revoked
     */
    public Future<IssuedKey> issueReplacementKey(License license) {
        return issueKey(license, license.getTermEnd());
    }

    /**
     * Emails a key to the licensee's contacts as the license's first key or a replacement.
     */
    public Future<Void> emailNewKey(License license, IssuedKey key) {
        return emailContacts(license, (contact, organization) -> emailService.sendNewKey(contact, organization, license, key));
    }

    /**
     * Emails a renewal key to the licensee's contacts.
     */
    public Future<Void> emailRenewalKey(License license, IssuedKey key) {
        return emailContacts(license, (contact, organization) -> emailService.sendRenewalKey(contact, organization, license, key));
    }

    /**
     * Emails the key of an approved free license, or of its approved renewal, to the licensee's contacts.
     */
    public Future<Void> emailFreeApproval(License license, IssuedKey key) {
        return emailContacts(license, (contact, organization) -> emailService.sendFreeRequestApproved(contact, organization, license, key));
    }

    /**
     * Tells the licensee's contacts a payment failed and no renewal key is issued until one succeeds.
     */
    public Future<Void> emailPastDue(License license) {
        return emailContacts(license, (contact, organization) -> emailService.sendPastDue(contact, organization, license));
    }

    /**
     * Emails the license's newest key to the licensee's contacts again.
     *
     * @throws IllegalArgumentException when the license has no key
     */
    public Future<Void> resendLatestKey(License license) {
        return issuedKeyRepository.findLatestByLicenseId(license.getId()).compose(key -> {
            if (key == null) {
                throw new IllegalArgumentException("The license " + license.getId() + " has no key to resend");
            }
            return emailNewKey(license, key);
        });
    }

    /**
     * Revokes the license: no further key is issued for it.
     */
    public Future<License> revoke(License license) {
        return licenseRepository.revoke(license.getId());
    }

    /**
     * The license's keys, newest first.
     */
    public Future<List<IssuedKey>> keysOf(String licenseId) {
        return issuedKeyRepository.findByLicenseId(licenseId);
    }

    /**
     * The end of a term starting at {@code start}.
     */
    public static Instant termEnd(Instant start) {
        return start.atZone(ZoneOffset.UTC).plus(TERM).toInstant();
    }

    private static License paidLicense(Customer customer, PaidSubscription subscription) {
        PriceLookupKey price = subscription.price();
        return newLicense(customer)
                .setPlan(price.plan())
                .setBillingPeriod(price.period())
                .setPriceList(price.priceList())
                .setStripeSubscriptionId(subscription.stripeSubscriptionId())
                .setCheckoutSessionId(subscription.checkoutSessionId())
                .setCurrentPeriodEnd(subscription.currentPeriodEnd());
    }

    private static License newLicense(Customer customer) {
        Instant now = Instant.now();
        return new License()
                .setId(UUID.randomUUID().toString())
                .setCustomerId(customer.getId())
                .setTermsVersion(AgreementTerms.VERSION)
                .setTermStart(now)
                .setTermEnd(termEnd(now));
    }

    // Signed first, so a signing failure leaves the term where it was for the next attempt. The term then moves only if
    // nobody revoked the license or moved its term meanwhile, and the key is logged only once it has.
    private Future<NewLicense> issueForTerm(License license, Instant termStart) {
        Instant termEnd = termEnd(termStart);
        return claims(license, termEnd).compose(claims -> keyIssuer.issue(claims)
                        .compose(key -> {
                            IssuedKey issued = issuedKey(claims, key, license.getId());
                            return licenseRepository.startTerm(license, termStart, termEnd, issued)
                                                    .map(moved -> {
                                                        if (moved == null) {
                                                            throw new IllegalStateException("The license " + license.getId()
                                                                    + " is revoked, or changed while its key was issued");
                                                        }
                                                        logIssued(issued);
                                                        return new NewLicense(moved, issued);
                                                    });
                        }));
    }

    private Future<IssuedKey> issueKey(License license, Instant termEnd) {
        return claims(license, termEnd).compose(claims -> sign(claims, license.getId()));
    }

    // A key names its licensee by the customer's legal name, which Kinotic shows on its License page. Its expiry is in
    // whole seconds, as the JWT carries it, so the key log records the expiry the key has.
    private Future<LicenseKeyClaims> claims(License license, Instant termEnd) {
        return customerRepository.findById(license.getCustomerId())
                                 .map(customer -> new LicenseKeyClaims(customer.getLegalName(), UUID.randomUUID().toString(),
                                                                       Instant.now(), termEnd.plus(KEY_BUFFER).truncatedTo(ChronoUnit.SECONDS)));
    }

    private Future<IssuedKey> sign(LicenseKeyClaims claims, String licenseId) {
        return keyIssuer.issue(claims).compose(key -> append(claims, key, licenseId));
    }

    private Future<IssuedKey> append(LicenseKeyClaims claims, String key, String licenseId) {
        return issuedKeyRepository.append(issuedKey(claims, key, licenseId)).map(issued -> {
            if (issued == null) {
                throw new IllegalStateException("The license " + licenseId + " is revoked");
            }
            logIssued(issued);
            return issued;
        });
    }

    private static IssuedKey issuedKey(LicenseKeyClaims claims, String key, String licenseId) {
        return new IssuedKey()
                .setJti(claims.keyId())
                .setLicenseId(licenseId)
                .setKey(key)
                .setIssuedAt(claims.issuedAt())
                .setExpiresAt(claims.expiresAt());
    }

    private static void logIssued(IssuedKey issued) {
        log.info("Issued key {} for license {}", issued.getJti(), issued.getLicenseId());
    }

    // every contact of the licensee gets the email; a failure to send fails the caller, which retries or reports it
    private Future<Void> emailContacts(License license, BiFunction<CustomerContact, String, Future<Void>> send) {
        return customerRepository.findById(license.getCustomerId())
                                 .compose(customer -> contactRepository.findByCustomerId(customer.getId())
                                                                       .compose(contacts -> sendAll(contacts, customer, send)));
    }

    private static Future<Void> sendAll(List<CustomerContact> contacts, Customer customer,
                                        BiFunction<CustomerContact, String, Future<Void>> send) {
        Future<Void> ret = Future.succeededFuture();
        for (CustomerContact contact : contacts) {
            ret = ret.compose(v -> send.apply(contact, customer.getLegalName()));
        }
        return ret;
    }
}
