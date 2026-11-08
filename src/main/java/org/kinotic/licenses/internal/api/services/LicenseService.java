package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.Feature;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseKeyClaims;
import org.kinotic.licenses.api.model.LicensePurpose;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.model.NewLicense;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.EnumSet;
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
    /** The licensee named by the trial key, which Kinotic's own tests and CI run under. */
    public static final String TRIAL_SUBJECT = "kinotic-trial";

    private final LicenseRepository licenseRepository;
    private final IssuedKeyRepository issuedKeyRepository;
    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final LicenseKeyIssuer keyIssuer;
    private final EmailService emailService;

    /**
     * Creates a paid or free license whose term starts now and issues its first key. The caller emails the key, with
     * the message that fits how the license came about.
     *
     * @param customer             the licensee
     * @param plan                 the plan
     * @param stripeSubscriptionId the Stripe Subscription billing it, or {@code null} for a free license
     * @param checkoutSessionId    the Stripe Checkout Session that bought it, or {@code null} for a free license
     * @param currentPeriodEnd     the end of the first billing period, or {@code null} for a free license
     * @return the license and its key
     */
    public Future<NewLicense> createLicense(Customer customer, Plan plan, String stripeSubscriptionId, String checkoutSessionId,
                                            Instant currentPeriodEnd) {
        License license = newLicense(customer, plan, stripeSubscriptionId, checkoutSessionId, currentPeriodEnd)
                .setStatus(LicenseStatus.ACTIVE)
                .setLastPaymentAt(stripeSubscriptionId != null ? Instant.now() : null);
        return licenseRepository.save(license)
                                .compose(saved -> issueKey(saved, saved.getTermEnd()).map(key -> new NewLicense(saved, key)));
    }

    /**
     * Creates a paid license whose first payment has not cleared, as with a bank debit. It holds no key and is
     * {@link LicenseStatus#PAST_DUE} until {@link #issueFirstKey(License)} is called once the payment succeeds.
     *
     * @return the license
     */
    public Future<License> createAwaitingPayment(Customer customer, Plan plan, String stripeSubscriptionId, String checkoutSessionId,
                                                 Instant currentPeriodEnd) {
        return licenseRepository.save(newLicense(customer, plan, stripeSubscriptionId, checkoutSessionId, currentPeriodEnd)
                                              .setStatus(LicenseStatus.PAST_DUE));
    }

    /**
     * Issues the first key of a license that has none, its term starting now.
     *
     * @return the license and its key, or a failure with {@link IllegalStateException} when the license is revoked or
     *         its term changed meanwhile
     */
    public Future<NewLicense> issueFirstKey(License license) {
        return issueForTerm(license, Instant.now());
    }

    /**
     * Starts the license's next term and issues its key. The next term starts when the current one ends, or now when
     * that is already past. The current key keeps working until it expires.
     *
     * @return the license and its key, or a failure with {@link IllegalStateException} when the license is revoked or
     *         its term changed meanwhile
     */
    public Future<NewLicense> issueRenewalKey(License license) {
        Instant now = Instant.now();
        return issueForTerm(license, license.getTermEnd().isAfter(now) ? license.getTermEnd() : now);
    }

    /**
     * Issues another key for the license's current term.
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
     * Issues the trial key: it licenses {@link #TRIAL_SUBJECT} for development and testing with every feature and
     * never expires. It belongs to no license and is logged like every other key.
     */
    public Future<IssuedKey> issueTrialKey() {
        LicenseKeyClaims claims = new LicenseKeyClaims(TRIAL_SUBJECT, UUID.randomUUID().toString(), Instant.now(), null,
                                                       LicensePurpose.DEVELOPMENT_AND_TESTING, EnumSet.allOf(Feature.class));
        return sign(claims, null);
    }

    /**
     * Revokes the license: no further key is issued for it.
     */
    public Future<License> revoke(License license) {
        return licenseRepository.revoke(license.getId());
    }

    /**
     * Records a successful payment and returns the license to good standing, unless it is revoked.
     */
    public Future<License> recordPayment(License license, Instant paidAt) {
        return licenseRepository.recordPayment(license.getId(), paidAt);
    }

    /**
     * Marks a payment failed, unless the license is revoked; no renewal key is issued until a payment succeeds.
     */
    public Future<License> markPastDue(License license) {
        return licenseRepository.updateStatus(license.getId(), LicenseStatus.PAST_DUE);
    }

    /**
     * Marks the subscription ended, unless the license is revoked; no renewal key is issued.
     */
    public Future<License> cancel(License license) {
        return licenseRepository.updateStatus(license.getId(), LicenseStatus.CANCELED);
    }

    /**
     * Writes what Stripe reports about the subscription: its plan, whether it is in good standing, the end of the
     * current billing period, and when it ends if the customer cancelled it. A license staff revoked stays revoked.
     */
    public Future<License> syncSubscription(License license, Plan plan, LicenseStatus status, Instant currentPeriodEnd,
                                            Instant cancelsAt) {
        return licenseRepository.syncSubscription(license.getId(), plan, status, currentPeriodEnd, cancelsAt);
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

    private static License newLicense(Customer customer, Plan plan, String stripeSubscriptionId, String checkoutSessionId,
                                      Instant currentPeriodEnd) {
        Instant now = Instant.now();
        return new License()
                .setId(UUID.randomUUID().toString())
                .setCustomerId(customer.getId())
                .setPlan(plan)
                .setFeatures(plan.features())
                .setStripeSubscriptionId(stripeSubscriptionId)
                .setCheckoutSessionId(checkoutSessionId)
                .setCurrentPeriodEnd(currentPeriodEnd)
                .setTermStart(now)
                .setTermEnd(termEnd(now));
    }

    // Signed first, so a signing failure leaves the term where it was for the next attempt. The term then moves only if
    // nobody revoked the license or moved its term meanwhile, and the key is logged only once it has.
    private Future<NewLicense> issueForTerm(License license, Instant termStart) {
        Instant termEnd = termEnd(termStart);
        LicenseKeyClaims claims = claims(license, termEnd);
        return keyIssuer.issue(claims)
                        .compose(key -> licenseRepository.moveTerm(license.getId(), license.getTermEnd(), termStart, termEnd)
                                                         .compose(moved -> {
                                                             if (moved == null) {
                                                                 throw new IllegalStateException("The license " + license.getId()
                                                                         + " is revoked, or its term changed while its key was issued");
                                                             }
                                                             return append(claims, key, moved.getId()).map(issued -> new NewLicense(moved, issued));
                                                         }));
    }

    private Future<IssuedKey> issueKey(License license, Instant termEnd) {
        return sign(claims(license, termEnd), license.getId());
    }

    private static LicenseKeyClaims claims(License license, Instant termEnd) {
        return new LicenseKeyClaims(license.getCustomerId(), UUID.randomUUID().toString(), Instant.now(),
                                    termEnd.plus(KEY_BUFFER), LicensePurpose.PRODUCTION, license.getFeatures());
    }

    private Future<IssuedKey> sign(LicenseKeyClaims claims, String licenseId) {
        return keyIssuer.issue(claims).compose(key -> append(claims, key, licenseId));
    }

    private Future<IssuedKey> append(LicenseKeyClaims claims, String key, String licenseId) {
        return issuedKeyRepository.append(new IssuedKey()
                                                  .setJti(claims.keyId())
                                                  .setLicenseId(licenseId)
                                                  .setPurpose(claims.purpose())
                                                  .setKey(key)
                                                  .setIssuedAt(claims.issuedAt())
                                                  .setExpiresAt(claims.expiresAt()))
                                  .onSuccess(issued -> log.info("Issued key {} for {}", issued.getJti(),
                                                                licenseId != null ? "license " + licenseId : claims.subject()));
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
