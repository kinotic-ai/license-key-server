package org.kinotic.licenses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.CheckoutRequest;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseKeyClaims;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.LicenseWithKeys;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.TrialRequest;
import org.kinotic.licenses.api.services.CustomerAccountService;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.KeyEmailJob;
import org.kinotic.licenses.internal.api.services.LicenseKeyIssuer;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.internal.api.services.TrialExpiryJob;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeEvents;
import org.kinotic.licenses.support.StripeRequest;
import org.kinotic.licenses.support.StripeStandIn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A customer that has never held a paid license or a trial starts a three-month trial of the paid plan its figures set,
 * with no payment details. The trial's key expires when it ends; buying a plan before then moves the trial onto the
 * subscription, and a trial nobody bought expires.
 */
@ExtendWith(OutputCaptureExtension.class)
class TrialTest extends ServerTest {

    private static final CompanyFigures SMALL_FIGURES = figures("1500000");
    private static final CompanyFigures MEDIUM_FIGURES = figures("30000000");

    @Autowired
    private CustomerAccountService customerAccount;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private LicenseService licenseService;
    @Autowired
    private LicenseKeyIssuer keyIssuer;
    @Autowired
    private AgreementAcceptanceRepository acceptanceRepository;
    @Autowired
    private TrialExpiryJob trialExpiryJob;
    @Autowired
    private KeyEmailJob keyEmailJob;

    @Test
    void aTrialRunsThreeMonthsWithAKeyThatExpiresWhenItEnds(CapturedOutput output) throws Exception {
        CustomerContact contact = contactOf();
        assertThat(asCustomer(contact, customerAccount::getAccount).isTrialAvailable()).isTrue();

        License trial = startTrial(contact, Plan.MEDIUM, MEDIUM_FIGURES);

        assertThat(trial.getStatus()).isEqualTo(LicenseStatus.TRIAL);
        assertThat(trial.getPlan()).isEqualTo(Plan.MEDIUM);
        assertThat(trial.getStripeSubscriptionId()).isNull();
        assertThat(trial.getPriceList()).isEqualTo(StripeStandIn.CURRENT_PRICE_LIST);
        assertThat(trial.getTermEnd()).isEqualTo(trial.getTermStart().atZone(ZoneOffset.UTC).plusMonths(3).toInstant());
        LicenseWithKeys inAccount = asCustomer(contact, customerAccount::getAccount).getLicenses().getFirst();
        assertThat(inAccount.getPaidThrough()).isNull();
        IssuedKey key = inAccount.getKeys().getFirst();
        assertThat(key.getExpiresAt()).isEqualTo(trial.getTermEnd().truncatedTo(ChronoUnit.SECONDS));
        LicenseKeyClaims claims = keyIssuer.verify(key.getKey());
        assertThat(claims.expiresAt()).isEqualTo(key.getExpiresAt());
        assertThat(output.getAll()).contains("Your Kinotic Enterprise trial license key");
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId())))
                .singleElement()
                .satisfies(acceptance -> assertThat(acceptance.getPublicityConsent()).isNull());
    }

    @Test
    void aCustomerHasOneTrial() throws Exception {
        CustomerContact contact = contactOf();
        License trial = startTrial(contact, Plan.SMALL, SMALL_FIGURES);

        assertThatThrownBy(() -> startTrial(contact, Plan.SMALL, SMALL_FIGURES))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never had a trial or a paid plan");

        sql("UPDATE license SET status = 'EXPIRED' WHERE id = $1", trial.getId());
        assertThatThrownBy(() -> startTrial(contact, Plan.SMALL, SMALL_FIGURES)).hasMessageContaining("never had a trial or a paid plan");
        assertThat(asCustomer(contact, customerAccount::getAccount).isTrialAvailable()).isFalse();
    }

    @Test
    void twoTrialsStartedTogetherLeaveOne() throws Exception {
        CustomerContact contact = contactOf();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<CompletableFuture<License>> starts = List.of(startTrialAsync(contact, go, executor), startTrialAsync(contact, go, executor));
            go.countDown();
            long started = starts.stream().filter(start -> start.handle((trial, t) -> t == null).join()).count();

            assertThat(started).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(await(licenseRepository.findByCustomerId(contact.getCustomerId()))).hasSize(1);
    }

    @Test
    void aCustomerThatHeldAPaidLicenseOrHoldsAFreeOneGetsNoTrial() throws Exception {
        CustomerContact paid = contactOf();
        await(licenseService.createLicense(await(customerRepository.findById(paid.getCustomerId())),
                                           subscription(Plan.SMALL, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                        Instant.now().plus(Duration.ofDays(30)))));
        assertThatThrownBy(() -> startTrial(paid, Plan.SMALL, SMALL_FIGURES)).hasMessageContaining("never had a trial or a paid plan");

        CustomerContact free = contactOf();
        await(licenseService.createFreeLicense(await(customerRepository.findById(free.getCustomerId())), StripeStandIn.CURRENT_PRICE_LIST));
        assertThatThrownBy(() -> startTrial(free, Plan.SMALL, SMALL_FIGURES)).hasMessageContaining("no active free license");
        assertThat(asCustomer(free, customerAccount::getAccount).isTrialAvailable()).isFalse();
    }

    @Test
    void theTrialIsOfThePaidPlanTheFiguresSet() throws Exception {
        CustomerContact contact = contactOf();

        assertThatThrownBy(() -> startTrial(contact, Plan.SMALL, MEDIUM_FIGURES))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fit the MEDIUM plan");
        assertThatThrownBy(() -> startTrial(contact, Plan.FREE, figures("100000").setCapitalRaised(BigDecimal.ZERO)))
                .hasMessageContaining("A paid plan is required");
        assertThat(await(licenseRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();
    }

    @Test
    void buyingDuringTheTrialMovesItOntoThePlanAndStartsItsFirstYear() throws Exception {
        CustomerContact contact = contactOf();
        License trial = startTrial(contact, Plan.SMALL, SMALL_FIGURES);
        sql("UPDATE license SET price_list = $2 WHERE id = $1", trial.getId(), StripeStandIn.OLDER_PRICE_LIST);
        String lookupKey = StripeStandIn.lookupKey(StripeStandIn.OLDER_PRICE_LIST, Plan.SMALL, BillingPeriod.ANNUAL);

        String url = asCustomer(contact, () -> customerAccount.startCheckout(new CheckoutRequest().setPlan(Plan.SMALL)
                                                                                                   .setBillingPeriod(BillingPeriod.ANNUAL)
                                                                                                   .setFigures(SMALL_FIGURES),
                                                                             AgreementTerms.VERSION));
        assertThat(url).startsWith("https://checkout.stripe.test/");
        StripeRequest created = StripeStandIn.requests("POST", "/v1/checkout/sessions").stream()
                                             .filter(request -> contact.getCustomerId().equals(request.params().get("client_reference_id")))
                                             .reduce((first, second) -> second)
                                             .orElseThrow();
        assertThat(created.params().get("metadata[kinoticLicenseId]")).isEqualTo(trial.getId());
        assertThat(created.params().get("line_items[0][price]")).isEqualTo(StripeStandIn.priceId(lookupKey));

        Instant boughtAt = Instant.now();
        String subscriptionId = "sub_" + UUID.randomUUID();
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", lookupKey,
                                                       Instant.now().plus(Duration.ofDays(365)).truncatedTo(ChronoUnit.SECONDS)));
        assertThat(deliver(StripeEvents.event("checkout.session.completed", StripeEvents.completedSession(created, subscriptionId, "paid")))
                           .statusCode()).isEqualTo(200);

        License moved = await(licenseRepository.findById(trial.getId()));
        assertThat(moved.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        List<IssuedKey> keys = await(licenseService.keysOf(trial.getId()));
        assertThat(keys).hasSize(2);
        assertThat(keys.getFirst().getExpiresAt()).isEqualTo(LicenseService.keyExpiry(moved.getCurrentPeriodEnd()));
        assertThat(moved.getStripeSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(moved.getBillingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(moved.getPriceList()).isEqualTo(StripeStandIn.OLDER_PRICE_LIST);
        assertThat(moved.getTermStart()).isAfterOrEqualTo(boughtAt.truncatedTo(ChronoUnit.MILLIS));
        assertThat(moved.getTermEnd()).isEqualTo(LicenseService.termEnd(moved.getTermStart()));
        assertThat(await(licenseRepository.findByCustomerId(contact.getCustomerId()))).hasSize(1);
    }

    @Test
    void aTrialNobodyBoughtExpires(CapturedOutput output) throws Exception {
        CustomerContact contact = contactOf();
        License trial = startTrial(contact, Plan.SMALL, SMALL_FIGURES);
        sql("UPDATE license SET term_end = now() - interval '1 minute' WHERE id = $1", trial.getId());

        assertThat(await(trialExpiryJob.run())).isGreaterThanOrEqualTo(1);

        License expired = await(licenseRepository.findById(trial.getId()));
        assertThat(expired.getStatus()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(output.getAll()).contains("Your Kinotic Enterprise trial has ended");
        assertThatThrownBy(() -> await(licenseService.issueKey(expired))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(await(licenseRepository.findMovable(contact.getCustomerId()))).isNull();
    }

    @Test
    void theContactsAreWarnedBeforeTheTrialEnds(CapturedOutput output) throws Exception {
        CustomerContact contact = contactOf();
        License trial = startTrial(contact, Plan.SMALL, SMALL_FIGURES);
        sql("UPDATE license SET term_end = now() + interval '10 days' WHERE id = $1", trial.getId());
        sql("UPDATE issued_key SET expires_at = now() + interval '10 days' WHERE license_id = $1", trial.getId());

        await(keyEmailJob.run());

        assertThat(output.getAll()).contains("Your Kinotic Enterprise license key expires soon")
                                   .contains("your trial of the Small plan ends on");
    }

    private License startTrial(CustomerContact contact, Plan plan, CompanyFigures figures) throws Exception {
        return asCustomer(contact, () -> customerAccount.startTrial(new TrialRequest().setPlan(plan).setFigures(figures), AgreementTerms.VERSION));
    }

    // a start on its own thread that waits for the latch, so two of them begin together
    private CompletableFuture<License> startTrialAsync(CustomerContact contact, CountDownLatch go, ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                go.await();
                return startTrial(contact, Plan.SMALL, SMALL_FIGURES);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private CustomerContact contactOf() throws Exception {
        return await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
    }

    private static CompanyFigures figures(String revenue) {
        return new CompanyFigures().setAnnualRevenue(new BigDecimal(revenue));
    }
}
