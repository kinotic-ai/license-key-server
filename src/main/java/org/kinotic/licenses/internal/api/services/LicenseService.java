package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseFlag;
import org.kinotic.licenses.api.model.LicenseKeyClaims;
import org.kinotic.licenses.api.model.LicenseWithKeys;
import org.kinotic.licenses.api.model.RevenueConfirmation;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.model.NewLicense;
import org.kinotic.licenses.internal.api.model.PaidSubscription;
import org.kinotic.licenses.internal.api.model.PriceLookupKey;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.repositories.RevenueConfirmationRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The lifecycle of a license and the keys issued for it. A license runs in twelve-month license years from its start.
 * Its keys follow the date it is paid through, the end of the Stripe billing period for a paid plan and the end of
 * the license year for Free: each key expires {@link #KEY_GRACE} after that date, is emailed to the customer's contacts,
 * and is kept in the append-only key log. A key is issued only for an active license. A key, once issued, is never
 * withdrawn: it is verified offline and works until it expires, so revoking, cancelling or lapsing a license only
 * stops new keys.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseService {

    /** A license year. */
    public static final Period TERM = Period.ofMonths(12);
    /** How long before an anniversary the customer is asked to confirm its figures for it. */
    public static final Duration CONFIRMATION_WINDOW = Duration.ofDays(30);
    /** How long a key outlives the date its license is paid through. */
    public static final Duration KEY_GRACE = Duration.ofDays(7);

    private final LicenseRepository licenseRepository;
    private final IssuedKeyRepository issuedKeyRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final LicenseKeyIssuer keyIssuer;
    private final EmailService emailService;
    private final RevenueConfirmationRepository confirmationRepository;

    /**
     * Creates a paid license whose first payment has cleared, its year starting now on the subscription's price list
     * and the current terms, and issues its first key. The caller emails the key.
     *
     * @return the license and its key
     */
    public Future<NewLicense> createLicense(Customer customer, PaidSubscription subscription) {
        License license = paidLicense(customer, subscription).setStatus(LicenseStatus.ACTIVE).setLastPaymentAt(Instant.now());
        return licenseRepository.save(license)
                                .compose(saved -> issueKey(saved).map(key -> new NewLicense(saved, key)));
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
                                .compose(saved -> issueKey(saved).map(key -> new NewLicense(saved, key)));
    }

    /**
     * Creates a paid license whose first payment has not cleared, as with a bank debit. It holds no key and is
     * {@link LicenseStatus#PAST_DUE} until the payment succeeds, when {@link #issueKeyIfExtended(License)} issues its first.
     *
     * @return the license
     */
    public Future<License> createAwaitingPayment(Customer customer, PaidSubscription subscription) {
        return licenseRepository.save(paidLicense(customer, subscription).setStatus(LicenseStatus.PAST_DUE));
    }

    /**
     * Issues a key covering the date the license is paid through.
     *
     * @return the key, or a failure with {@link IllegalStateException} when the license is not active
     */
    public Future<IssuedKey> issueKey(License license) {
        return claims(license, paidThrough(license)).compose(claims -> sign(claims, license.getId()));
    }

    /**
     * Issues a key when the license is paid through a later date than its newest key covers, or it has none, as when a
     * payment extends it. Repeated calls issue one key for each extension.
     *
     * @return the key issued, or {@code null} when the newest key already covers the paid-through date; a failure with
     *         {@link IllegalStateException} when the license is not active
     */
    public Future<IssuedKey> issueKeyIfExtended(License license) {
        Instant coverage = keyExpiry(paidThrough(license));
        return issuedKeyRepository.findLatestByLicenseId(license.getId())
                                  .compose(newest -> newest == null || newest.getExpiresAt().isBefore(coverage)
                                          ? issueKey(license)
                                          : Future.succeededFuture());
    }

    /**
     * Starts a free license's next license year and issues the key covering it. The year starts at an anniversary of the
     * license: when the current year ends, or the latest anniversary since when that is more than a year past.
     *
     * @return the license and its key, or a failure with {@link IllegalStateException} when the license is not active,
     *         or changed since the caller read it
     */
    public Future<NewLicense> renewFreeTerm(License license) {
        Instant now = Instant.now();
        Instant start = license.getTermEnd();
        while (!termEnd(start).isAfter(now)) {
            start = termEnd(start);
        }
        return startFreeTerm(license, start);
    }

    /**
     * Starts a free license's license year now and issues the key covering it, for a free license that holds no key.
     *
     * @return the license and its key, or a failure with {@link IllegalStateException} when the license is not active,
     *         or changed since the caller read it
     */
    public Future<NewLicense> startFreeTerm(License license) {
        return startFreeTerm(license, Instant.now());
    }

    /**
     * Emails a key to the licensee's contacts as the license's first key or a replacement.
     */
    public Future<Void> emailNewKey(License license, IssuedKey key) {
        return emailContacts(license, (contact, organization) -> emailService.sendNewKey(contact, organization, license, key));
    }

    /**
     * Emails the key a payment extended the license with to the licensee's contacts.
     */
    public Future<Void> emailRenewalKey(License license, IssuedKey key) {
        return emailContacts(license, (contact, organization) -> emailService.sendRenewalKey(contact, organization, license, key));
    }

    /**
     * Asks the licensee's contacts to confirm the company's figures before the license's next anniversary.
     */
    public Future<Void> emailConfirmationDue(License license) {
        return emailContacts(license, (contact, organization) -> emailService.sendConfirmationDue(contact, organization, license));
    }

    /**
     * Tells the licensee's contacts what the figures they confirmed change at the anniversary {@code at}.
     */
    public Future<Void> emailPlanChanged(License license, Plan plan, Instant at) {
        return emailContacts(license, (contact, organization) -> emailService.sendPlanChanged(contact, organization, license, plan, at));
    }

    /**
     * Warns the licensee's contacts that the past-due license lapses at {@code lapsesAt}.
     */
    public Future<Void> emailLapseWarning(License license, Instant lapsesAt) {
        return emailContacts(license, (contact, organization) -> emailService.sendLapseWarning(contact, organization, license, lapsesAt));
    }

    /**
     * Tells the licensee's contacts the license lapsed.
     */
    public Future<Void> emailLapsed(License license) {
        return emailContacts(license, (contact, organization) -> emailService.sendLapsed(contact, organization));
    }

    /**
     * Warns the licensee's contacts that the license's newest key expires with no newer one issued.
     */
    public Future<Void> emailKeyExpiring(License license, IssuedKey key) {
        return emailContacts(license, (contact, organization) -> emailService.sendKeyExpiring(contact, organization, license, key));
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
     * Each license with its keys, its yearly confirmations and the anniversary its figures are due for, in the order
     * given.
     */
    public Future<List<LicenseWithKeys>> withKeys(List<License> licenses) {
        Future<List<LicenseWithKeys>> ret = Future.succeededFuture(new ArrayList<>());
        for (License license : licenses) {
            ret = ret.compose(list -> keysOf(license.getId()).compose(keys -> confirmationRepository.findByLicenseId(license.getId()).map(confirmations -> {
                list.add(new LicenseWithKeys().setLicense(license)
                                              .setKeys(keys)
                                              .setPaidThrough(paidThrough(license))
                                              .setConfirmations(confirmations)
                                              .setConfirmationDueFor(confirmationDueFor(license, confirmations)));
                return list;
            })));
        }
        return ret;
    }

    /**
     * The anniversary the license's figures are confirmed for now: its next one from {@link #CONFIRMATION_WINDOW} before
     * it, or the one it missed while it is flagged {@link LicenseFlag#REVENUE_UNCONFIRMED}; {@code null} when it is too
     * early or the license has ended.
     */
    public static Instant anniversaryToConfirm(License license) {
        Instant ret = null;
        if (license.getStatus() == LicenseStatus.ACTIVE || license.getStatus() == LicenseStatus.PAST_DUE) {
            if (license.getFlags() != null && license.getFlags().contains(LicenseFlag.REVENUE_UNCONFIRMED)) {
                ret = license.getTermStart();
            } else if (!Instant.now().isBefore(license.getTermEnd().minus(CONFIRMATION_WINDOW))) {
                ret = license.getTermEnd();
            }
        }
        return ret;
    }

    /**
     * The anniversary the license's figures are due for: {@link #anniversaryToConfirm(License)}, unless the figures for
     * it are confirmed already.
     *
     * @param confirmations the license's confirmations
     */
    public static Instant confirmationDueFor(License license, List<RevenueConfirmation> confirmations) {
        Instant ret = anniversaryToConfirm(license);
        Instant due = ret;
        return due != null && confirmations.stream().anyMatch(confirmation -> confirmation.getAnniversary().equals(due)) ? null : ret;
    }

    /**
     * The license's keys, newest first.
     */
    public Future<List<IssuedKey>> keysOf(String licenseId) {
        return issuedKeyRepository.findByLicenseId(licenseId);
    }

    /**
     * The end of a license year starting at {@code start}.
     */
    public static Instant termEnd(Instant start) {
        return start.atZone(ZoneOffset.UTC).plus(TERM).toInstant();
    }

    /**
     * The date the license is paid through: the end of its current Stripe billing period for a paid plan, the end of
     * its license year for Free.
     */
    public static Instant paidThrough(License license) {
        return paidThrough(license.getPlan(), license.getTermEnd(), license.getCurrentPeriodEnd());
    }

    /**
     * The date a license on the plan is paid through, given the end of its license year and of its billing period.
     */
    public static Instant paidThrough(Plan plan, Instant termEnd, Instant currentPeriodEnd) {
        return plan == Plan.FREE ? termEnd : currentPeriodEnd;
    }

    /**
     * When a key covering a license paid through {@code paidThrough} expires, in whole seconds as the key carries it.
     */
    public static Instant keyExpiry(Instant paidThrough) {
        return paidThrough.plus(KEY_GRACE).truncatedTo(ChronoUnit.SECONDS);
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

    // Signed first, so a signing failure leaves the year where it was for the next attempt. The year then moves only if
    // the license is active and nobody moved its year meanwhile, and the key is logged only once it has.
    private Future<NewLicense> startFreeTerm(License license, Instant termStart) {
        Instant termEnd = termEnd(termStart);
        return claims(license, termEnd).compose(claims -> keyIssuer.issue(claims)
                        .compose(key -> {
                            IssuedKey issued = issuedKey(claims, key, license.getId());
                            return licenseRepository.startTerm(license, termStart, termEnd, issued)
                                                    .map(moved -> {
                                                        if (moved == null) {
                                                            throw new IllegalStateException("The license " + license.getId()
                                                                    + " is not active, or changed while its key was issued");
                                                        }
                                                        logIssued(issued);
                                                        return new NewLicense(moved, issued);
                                                    });
                        }));
    }

    // A key names its licensee by the customer's legal name, which Kinotic shows on its License page
    private Future<LicenseKeyClaims> claims(License license, Instant paidThrough) {
        return customerRepository.findById(license.getCustomerId())
                                 .map(customer -> new LicenseKeyClaims(customer.getLegalName(), UUID.randomUUID().toString(),
                                                                       Instant.now(), keyExpiry(paidThrough)));
    }

    private Future<IssuedKey> sign(LicenseKeyClaims claims, String licenseId) {
        return keyIssuer.issue(claims).compose(key -> append(claims, key, licenseId));
    }

    private Future<IssuedKey> append(LicenseKeyClaims claims, String key, String licenseId) {
        return issuedKeyRepository.append(issuedKey(claims, key, licenseId)).map(issued -> {
            if (issued == null) {
                throw new IllegalStateException("The license " + licenseId + " is not active, so it is issued no key");
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
        return customerService.emailContacts(license.getCustomerId(), send);
    }
}
