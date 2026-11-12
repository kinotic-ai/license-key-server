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
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.internal.api.model.PaidSubscription;
import org.kinotic.licenses.internal.api.model.PriceLookupKey;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicensedClusterRepository;
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
 * The lifecycle of a license and the keys issued for its clusters. A license runs in twelve-month license years from
 * its start, and holds as many clusters as it has seats. Each key is bound to one cluster and follows the date the
 * license is paid through, the end of the Stripe billing period for a paid plan and the end of the license year for
 * Free: it expires {@link #KEY_GRACE} after that date, and is kept in the append-only key log. A trial's keys expire
 * when the trial ends, {@link #TRIAL} after it starts. A key is issued only for a seated cluster of an active license or
 * a trial. A key, once issued, is never withdrawn: it is verified offline and works until it expires, so revoking,
 * cancelling or lapsing a license, or releasing a cluster, only stops new keys.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseService {

    /** A license year. */
    public static final Period TERM = Period.ofMonths(12);
    /** How long a trial lasts. */
    public static final Period TRIAL = Period.ofMonths(3);
    /** How long before an anniversary the customer is asked to confirm its figures for it. */
    public static final Duration CONFIRMATION_WINDOW = Duration.ofDays(30);
    /** How long a key outlives the date its license is paid through. */
    public static final Duration KEY_GRACE = Duration.ofDays(7);

    private final LicenseRepository licenseRepository;
    private final LicensedClusterRepository clusterRepository;
    private final IssuedKeyRepository issuedKeyRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final LicenseKeyIssuer keyIssuer;
    private final EmailService emailService;
    private final RevenueConfirmationRepository confirmationRepository;

    /**
     * Creates a paid license whose first payment has cleared, its year starting now on the subscription's price list
     * and the current terms. The caller emails that it is ready for its clusters.
     *
     * @return the license
     */
    public Future<License> createLicense(Customer customer, PaidSubscription subscription) {
        return licenseRepository.save(paidLicense(customer, subscription).setStatus(LicenseStatus.ACTIVE).setLastPaymentAt(Instant.now()));
    }

    /**
     * Creates a free license, its year starting now on the given price list and the current terms, completing the
     * approval of the free license request it was created for. The caller emails that it is ready for its clusters.
     *
     * @param priceList the current price list, which the license keeps should it move to a paid plan
     * @param requestId the approved free license request
     * @return the license
     */
    public Future<License> createFreeLicense(Customer customer, int priceList, String requestId) {
        return licenseRepository.saveFree(newLicense(customer).setPlan(Plan.FREE).setPriceList(priceList).setStatus(LicenseStatus.ACTIVE),
                                          requestId);
    }

    /**
     * Creates a trial of a paid plan, running {@link #TRIAL} from now on the given price list and the current terms. Its
     * clusters' keys expire when the trial ends. The caller emails that it is ready for its cluster.
     *
     * @param priceList the current price list, which the license keeps when its plan is bought
     * @return the license, or a failure with {@link org.kinotic.core.api.exceptions.AlreadyExistsException} when the
     *         customer holds a trial already
     */
    public Future<License> createTrial(Customer customer, Plan plan, int priceList) {
        License license = newLicense(customer).setPlan(plan).setPriceList(priceList).setStatus(LicenseStatus.TRIAL);
        license.setTermEnd(license.getTermStart().atZone(ZoneOffset.UTC).plus(TRIAL).toInstant());
        return licenseRepository.saveTrial(license);
    }

    /**
     * Creates a paid license whose first payment has not cleared, as with a bank debit. It is
     * {@link LicenseStatus#PAST_DUE}, and takes no cluster, until the payment succeeds.
     *
     * @return the license
     */
    public Future<License> createAwaitingPayment(Customer customer, PaidSubscription subscription) {
        return licenseRepository.save(paidLicense(customer, subscription).setStatus(LicenseStatus.PAST_DUE));
    }

    /**
     * Issues a key for the cluster covering the date the license is paid through, or the trial.
     *
     * @return the key, or a failure with {@link IllegalStateException} when the license is neither active nor a trial,
     *         or the cluster was released
     */
    public Future<IssuedKey> issueKey(License license, LicensedCluster cluster) {
        return claims(license, cluster, keyExpiry(license)).compose(claims -> sign(claims, license.getId(), cluster.getId()));
    }

    /**
     * Issues a key for the cluster when the license is paid through a later date than the cluster's newest key covers,
     * or the cluster has none, as when a payment extends the license. Repeated calls issue one key for each extension.
     *
     * @return the key issued, or {@code null} when the cluster's newest key already covers the paid-through date or the
     *         trial; a failure with {@link IllegalStateException} when the license is neither active nor a trial, or the
     *         cluster was released
     */
    public Future<IssuedKey> issueKeyIfExtended(License license, LicensedCluster cluster) {
        Instant coverage = keyExpiry(license);
        return issuedKeyRepository.findLatestByClusterId(cluster.getId())
                                  .compose(newest -> newest == null || newest.getExpiresAt().isBefore(coverage)
                                          ? issueKey(license, cluster)
                                          : Future.succeededFuture());
    }

    /**
     * Issues each seated cluster of the license the key covering the date it is paid through, where its newest key
     * does not, as when a payment extends it.
     *
     * @return how many keys were issued
     */
    public Future<Integer> issueExtendedKeys(License license) {
        return clusterRepository.findSeated(license.getId()).compose(clusters -> {
            Future<Integer> ret = Future.succeededFuture(0);
            for (LicensedCluster cluster : clusters) {
                ret = ret.compose(count -> issueKeyIfExtended(license, cluster).map(key -> key != null ? count + 1 : count));
            }
            return ret;
        });
    }

    /**
     * Starts a free license's next license year. The year starts at an anniversary of the license: when the current year
     * ends, or the latest anniversary since when that is more than a year past. The caller issues its clusters' keys.
     *
     * @param requestId the approved free license request the new year completes, or {@code null} for none
     * @return the license, or a failure with {@link IllegalStateException} when the license is not active, or changed
     *         since the caller read it
     */
    public Future<License> renewFreeTerm(License license, String requestId) {
        Instant now = Instant.now();
        Instant start = license.getTermEnd();
        while (!termEnd(start).isAfter(now)) {
            start = termEnd(start);
        }
        return startFreeTerm(license, start, requestId);
    }

    /**
     * Tells the licensee's contacts the license is ready for its clusters, as a purchase, trial or approval makes it.
     */
    public Future<Void> emailLicenseReady(License license) {
        return emailContacts(license, (contact, organization) -> emailService.sendLicenseReady(contact, organization, license));
    }

    /**
     * Tells the licensee's contacts that a payment, or a free license's new year, extended the license, and that its
     * clusters' new keys are issued.
     */
    public Future<Void> emailRenewed(License license) {
        return emailContacts(license, (contact, organization) -> emailService.sendLicenseRenewed(contact, organization, license));
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
     * Warns the licensee's contacts that a cluster's newest key expires with no newer one issued.
     */
    public Future<Void> emailKeyExpiring(License license, LicensedCluster cluster, IssuedKey key) {
        return clusterRepository.findSeated(license.getId()).compose(seated -> {
            boolean beyondSeats = seated.stream().noneMatch(held -> held.getId().equals(cluster.getId()));
            return emailContacts(license, (contact, organization) -> emailService.sendKeyExpiring(contact, organization, license, cluster, key,
                                                                                                  beyondSeats));
        });
    }

    /**
     * Tells the licensee's contacts the trial started and is ready for its cluster.
     */
    public Future<Void> emailTrialStarted(License license) {
        return emailContacts(license, (contact, organization) -> emailService.sendTrialStarted(contact, organization, license));
    }

    /**
     * Tells the licensee's contacts the trial ended without its plan being bought.
     */
    public Future<Void> emailTrialExpired(License license) {
        return emailContacts(license, (contact, organization) -> emailService.sendTrialExpired(contact, organization, license));
    }

    /**
     * Tells the licensee's contacts their free license, or its renewal, is approved.
     */
    public Future<Void> emailFreeApproval(License license) {
        return emailContacts(license, (contact, organization) -> emailService.sendFreeRequestApproved(contact, organization, license));
    }

    /**
     * Tells the licensee's contacts a payment failed and no renewal key is issued until one succeeds.
     */
    public Future<Void> emailPastDue(License license) {
        return emailContacts(license, (contact, organization) -> emailService.sendPastDue(contact, organization, license));
    }

    /**
     * Revokes the license: no further key is issued for it.
     */
    public Future<License> revoke(License license) {
        return licenseRepository.revoke(license.getId());
    }

    /**
     * Each license with its clusters, their keys, its yearly confirmations and the anniversary its figures are due for,
     * in the order given.
     */
    public Future<List<LicenseWithKeys>> withKeys(List<License> licenses) {
        Future<List<LicenseWithKeys>> ret = Future.succeededFuture(new ArrayList<>());
        for (License license : licenses) {
            ret = ret.compose(list -> clusterRepository.findByLicenseId(license.getId()).compose(clusters -> keysOf(license.getId()).compose(keys -> confirmationRepository.findByLicenseId(license.getId()).map(confirmations -> {
                list.add(new LicenseWithKeys().setLicense(license)
                                              .setClusters(clusters)
                                              .setKeys(keys)
                                              .setPaidThrough(paidThrough(license))
                                              .setConfirmations(confirmations)
                                              .setConfirmationDueFor(confirmationDueFor(license, confirmations)));
                return list;
            }))));
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
     * The keys of the license's clusters, newest first.
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
     * its license year for Free; {@code null} for a trial.
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
     * When a key issued for the license now expires, in whole seconds as the key carries it: {@link #KEY_GRACE} after the
     * date it is paid through, or when its trial ends.
     */
    public static Instant keyExpiry(License license) {
        Instant paidThrough = paidThrough(license);
        Instant ret;
        // only a trial, or a trial that expired, is paid through no date
        if (paidThrough == null) {
            ret = license.getTermEnd().truncatedTo(ChronoUnit.SECONDS);
        } else {
            ret = keyExpiry(paidThrough);
        }
        return ret;
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
                .setSeats(subscription.seats())
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

    // The year moves only if the license is active and nobody moved its year meanwhile
    private Future<License> startFreeTerm(License license, Instant termStart, String requestId) {
        return licenseRepository.startTerm(license, termStart, termEnd(termStart), requestId).map(moved -> {
            if (moved == null) {
                throw new IllegalStateException("The license " + license.getId() + " is not active, or changed while its year was moved");
            }
            return moved;
        });
    }

    // A key names its licensee by the customer's legal name, which Kinotic shows on its License page, and the one
    // cluster it works on
    private Future<LicenseKeyClaims> claims(License license, LicensedCluster cluster, Instant expiresAt) {
        return customerRepository.findById(license.getCustomerId())
                                 .map(customer -> new LicenseKeyClaims(customer.getLegalName(), UUID.randomUUID().toString(),
                                                                       cluster.getClusterId(), Instant.now(), expiresAt));
    }

    private Future<IssuedKey> sign(LicenseKeyClaims claims, String licenseId, String licensedClusterId) {
        return keyIssuer.issue(claims).compose(key -> issuedKeyRepository.append(new IssuedKey()
                                                                                         .setJti(claims.keyId())
                                                                                         .setLicenseId(licenseId)
                                                                                         .setLicensedClusterId(licensedClusterId)
                                                                                         .setKey(key)
                                                                                         .setIssuedAt(claims.issuedAt())
                                                                                         .setExpiresAt(claims.expiresAt())))
                        .map(issued -> {
                            if (issued == null) {
                                throw new IllegalStateException("The license " + licenseId + " is neither active nor a trial, or its cluster "
                                                                        + licensedClusterId + " was released, so it is issued no key");
                            }
                            log.info("Issued key {} for cluster {} of license {}", issued.getJti(), licensedClusterId, licenseId);
                            return issued;
                        });
    }

    // every contact of the licensee gets the email; a failure to send fails the caller, which retries or reports it
    private Future<Void> emailContacts(License license, BiFunction<CustomerContact, String, Future<Void>> send) {
        return customerService.emailContacts(license.getCustomerId(), send);
    }
}
