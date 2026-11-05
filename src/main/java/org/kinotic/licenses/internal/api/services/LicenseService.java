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
     * Creates a license whose term starts now and issues its first key. The caller emails the key, with the message
     * that fits how the license came about.
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
        Instant now = Instant.now();
        License license = new License()
                .setId(UUID.randomUUID().toString())
                .setCustomerId(customer.getId())
                .setPlan(plan)
                .setStatus(LicenseStatus.ACTIVE)
                .setFeatures(plan.features())
                .setStripeSubscriptionId(stripeSubscriptionId)
                .setCheckoutSessionId(checkoutSessionId)
                .setCurrentPeriodEnd(currentPeriodEnd)
                .setTermStart(now)
                .setTermEnd(termEnd(now))
                .setLastPaymentAt(stripeSubscriptionId != null ? now : null);
        return licenseRepository.save(license)
                                .compose(saved -> issueKey(saved, saved.getTermEnd()).map(key -> new NewLicense(saved, key)));
    }

    /**
     * Starts the license's next term and issues its key. The current key keeps working until it expires.
     */
    public Future<IssuedKey> issueRenewalKey(License license) {
        Instant nextTermStart = license.getTermEnd();
        Instant nextTermEnd = termEnd(nextTermStart);
        // signed before the term moves, so a signing failure leaves the license due for the next run
        return issueKey(license, nextTermEnd)
                .compose(key -> licenseRepository.update(license.setTermStart(nextTermStart).setTermEnd(nextTermEnd)).map(key));
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
        return licenseRepository.update(license.setStatus(LicenseStatus.REVOKED));
    }

    /**
     * Records a successful payment and returns the license to good standing.
     */
    public Future<License> recordPayment(License license, Instant paidAt) {
        return transition(license.setLastPaymentAt(paidAt), LicenseStatus.ACTIVE);
    }

    /**
     * Marks a payment failed; no renewal key is issued until a payment succeeds.
     */
    public Future<License> markPastDue(License license) {
        return transition(license, LicenseStatus.PAST_DUE);
    }

    /**
     * Marks the subscription ended; no renewal key is issued.
     */
    public Future<License> cancel(License license) {
        return transition(license, LicenseStatus.CANCELED);
    }

    /**
     * Writes what Stripe reports about the subscription: its plan, whether it is in good standing, and the end of the
     * current billing period. A license staff revoked stays revoked.
     */
    public Future<License> syncSubscription(License license, Plan plan, LicenseStatus status, Instant currentPeriodEnd) {
        return transition(license.setPlan(plan).setFeatures(plan.features()).setCurrentPeriodEnd(currentPeriodEnd), status);
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

    // a billing event never undoes a staff revocation
    private Future<License> transition(License license, LicenseStatus status) {
        if (license.getStatus() != LicenseStatus.REVOKED) {
            license.setStatus(status);
        }
        return licenseRepository.update(license);
    }

    private Future<IssuedKey> issueKey(License license, Instant termEnd) {
        LicenseKeyClaims claims = new LicenseKeyClaims(license.getCustomerId(), UUID.randomUUID().toString(), Instant.now(),
                                                       termEnd.plus(KEY_BUFFER), LicensePurpose.PRODUCTION, license.getFeatures());
        return sign(claims, license.getId());
    }

    private Future<IssuedKey> sign(LicenseKeyClaims claims, String licenseId) {
        return keyIssuer.issue(claims)
                        .compose(key -> issuedKeyRepository.append(new IssuedKey()
                                                                           .setJti(claims.keyId())
                                                                           .setLicenseId(licenseId)
                                                                           .setPurpose(claims.purpose())
                                                                           .setKey(key)
                                                                           .setIssuedAt(claims.issuedAt())
                                                                           .setExpiresAt(claims.expiresAt())))
                        .onSuccess(key -> log.info("Issued key {} for {}", key.getJti(), licenseId != null ? "license " + licenseId : claims.subject()));
    }

    // every contact of the licensee gets the email; a failure to send fails the caller, which retries or reports it
    private Future<Void> emailContacts(License license, EmailSend send) {
        return customerRepository.findById(license.getCustomerId())
                                 .compose(customer -> contactRepository.findByCustomerId(customer.getId())
                                                                       .compose(contacts -> sendAll(contacts, customer, send)));
    }

    private static Future<Void> sendAll(List<CustomerContact> contacts, Customer customer, EmailSend send) {
        Future<Void> ret = Future.succeededFuture();
        for (CustomerContact contact : contacts) {
            ret = ret.compose(v -> send.apply(contact, customer.getLegalName()));
        }
        return ret;
    }

    @FunctionalInterface
    private interface EmailSend {
        Future<Void> apply(CustomerContact contact, String organization);
    }
}
