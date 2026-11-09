package org.kinotic.licenses;

import org.junit.jupiter.api.Test;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.CheckoutRequest;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.PlanPrice;
import org.kinotic.licenses.api.services.CustomerAccountService;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeEvents;
import org.kinotic.licenses.support.StripeRequest;
import org.kinotic.licenses.support.StripeStandIn;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A signed-in customer buys a paid plan, monthly or annually, with figures that match it, at the price list it buys at:
 * the current one, or the one its free license keeps. The license the completed Checkout creates records its plan,
 * billing period, price list and terms.
 */
class PurchaseTest extends ServerTest {

    @Autowired
    private CustomerAccountService customerAccount;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private LicenseService licenseService;
    @Autowired
    private AgreementAcceptanceRepository acceptanceRepository;

    @Test
    void theFiguresSetThePlan() throws Exception {
        CustomerContact contact = contactOf();
        assertThat(planFor(contact, "999999.99", "4999999")).isEqualTo(Plan.FREE);
        // under $1M with $5M or more raised is Small, and the capital raised decides nothing above $1M
        assertThat(planFor(contact, "999999", "5000000")).isEqualTo(Plan.SMALL);
        assertThat(planFor(contact, "1000000", null)).isEqualTo(Plan.SMALL);
        assertThat(planFor(contact, "19999999", "0")).isEqualTo(Plan.SMALL);
        assertThat(planFor(contact, "20000000", null)).isEqualTo(Plan.MEDIUM);
        assertThat(planFor(contact, "100000000", null)).isEqualTo(Plan.MEDIUM);
        assertThat(planFor(contact, "100000000.01", null)).isEqualTo(Plan.LARGE);
        assertThatThrownBy(() -> planFor(contact, "500000", null)).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planFor(contact, "-1", "0")).hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMonthlyPurchaseCreatesAMonthlyLicenseOnTheCurrentPriceList() throws Exception {
        License license = buy(Plan.MEDIUM, BillingPeriod.MONTHLY, figures("45000000", null));
        assertThat(license.getPlan()).isEqualTo(Plan.MEDIUM);
        assertThat(license.getBillingPeriod()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(license.getPriceList()).isEqualTo(StripeStandIn.CURRENT_PRICE_LIST);
        assertThat(license.getTermsVersion()).isEqualTo(AgreementTerms.VERSION);
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void anAnnualPurchaseCreatesAnAnnualLicense() throws Exception {
        License license = buy(Plan.SMALL, BillingPeriod.ANNUAL, figures("3000000", null));
        assertThat(license.getPlan()).isEqualTo(Plan.SMALL);
        assertThat(license.getBillingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(license.getPriceList()).isEqualTo(StripeStandIn.CURRENT_PRICE_LIST);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void theCustomerSeesThePricesOfItsPriceList() throws Exception {
        List<PlanPrice> prices = asCustomer(contactOf(), customerAccount::findPrices);
        assertThat(prices).hasSize(6).allSatisfy(price -> assertThat(price.getPriceList()).isEqualTo(StripeStandIn.CURRENT_PRICE_LIST));
        assertThat(prices).anySatisfy(price -> {
            assertThat(price.getPlan()).isEqualTo(Plan.SMALL);
            assertThat(price.getBillingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
            // twelve months less 10%
            assertThat(price.getAmount()).isEqualByComparingTo(new BigDecimal("54000"));
        });
    }

    @Test
    void figuresThatSetAnotherPlanAreRefused() throws Exception {
        CustomerContact contact = contactOf();
        assertThatThrownBy(() -> checkout(contact, Plan.SMALL, BillingPeriod.MONTHLY, figures("30000000", null)))
                .hasCauseInstanceOf(IllegalArgumentException.class).hasMessageContaining("MEDIUM");
        assertThatThrownBy(() -> checkout(contact, Plan.SMALL, BillingPeriod.MONTHLY, figures("200000", "100000")))
                .hasCauseInstanceOf(IllegalArgumentException.class).hasMessageContaining("FREE");
        assertThatThrownBy(() -> checkout(contact, Plan.FREE, BillingPeriod.MONTHLY, figures("200000", "100000")))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkout(contact, Plan.SMALL, null, figures("3000000", null)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> asCustomer(contact, () -> customerAccount.startCheckout(request(Plan.SMALL, BillingPeriod.MONTHLY,
                                                                                                figures("3000000", null)), "2000-01-01")))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();
    }

    @Test
    void aCustomerHoldingAPaidSubscriptionIsNotSoldAnother() throws Exception {
        CustomerContact contact = contactOf();
        await(licenseService.createLicense(await(customerRepository.findById(contact.getCustomerId())),
                                           subscription(Plan.SMALL, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                        Instant.now().plus(Duration.ofDays(30)))));
        assertThatThrownBy(() -> checkout(contact, Plan.MEDIUM, BillingPeriod.MONTHLY, figures("30000000", null)))
                .hasCauseInstanceOf(AlreadyExistsException.class)
                .hasMessageContaining("already has a subscription");
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();
    }

    @Test
    void aFreeLicenseMovesOntoThePlanBoughtAtThePriceListItKeeps() throws Exception {
        CustomerContact contact = contactOf();
        License free = await(licenseService.createFreeLicense(await(customerRepository.findById(contact.getCustomerId())),
                                                              StripeStandIn.OLDER_PRICE_LIST)).license();

        StripeRequest created = checkout(contact, Plan.SMALL, BillingPeriod.ANNUAL, figures("1500000", null));
        assertThat(created.params().get("line_items[0][price]"))
                .isEqualTo(StripeStandIn.priceId(StripeStandIn.lookupKey(StripeStandIn.OLDER_PRICE_LIST, Plan.SMALL, BillingPeriod.ANNUAL)));

        License moved = complete(created, StripeStandIn.lookupKey(StripeStandIn.OLDER_PRICE_LIST, Plan.SMALL, BillingPeriod.ANNUAL));
        assertThat(moved.getId()).isEqualTo(free.getId());
        assertThat(moved.getPlan()).isEqualTo(Plan.SMALL);
        assertThat(moved.getBillingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(moved.getPriceList()).isEqualTo(StripeStandIn.OLDER_PRICE_LIST);
        assertThat(moved.getTermStart()).isEqualTo(free.getTermStart());
        assertThat(await(licenseRepository.findByCustomerId(contact.getCustomerId()))).hasSize(1);
    }

    @Test
    void aCustomerWhoseLicenseLapsedBuysOnTheCurrentPriceList() throws Exception {
        CustomerContact contact = contactOf();
        License old = await(licenseService.createLicense(await(customerRepository.findById(contact.getCustomerId())),
                                                         subscription(Plan.SMALL, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                                      Instant.now().plus(Duration.ofDays(30))))).license();
        sql("UPDATE license SET status = 'LAPSED', price_list = $2 WHERE id = $1", old.getId(), StripeStandIn.OLDER_PRICE_LIST);

        License bought = buy(contact, Plan.SMALL, BillingPeriod.MONTHLY, figures("1500000", null));
        assertThat(bought.getId()).isNotEqualTo(old.getId());
        assertThat(bought.getPriceList()).isEqualTo(StripeStandIn.CURRENT_PRICE_LIST);
    }

    private License buy(Plan plan, BillingPeriod period, CompanyFigures figures) throws Exception {
        return buy(contactOf(), plan, period, figures);
    }

    // the purchase as the browser and Stripe make it: the checkout, then the event of its completed Checkout Session
    private License buy(CustomerContact contact, Plan plan, BillingPeriod period, CompanyFigures figures) throws Exception {
        StripeRequest created = checkout(contact, plan, period, figures);
        String lookupKey = StripeStandIn.lookupKey(StripeStandIn.CURRENT_PRICE_LIST, plan, period);
        assertThat(created.params().get("line_items[0][price]")).isEqualTo(StripeStandIn.priceId(lookupKey));
        assertThat(created.params().get("cancel_url"))
                .endsWith("/buy?plan=" + plan.name().toLowerCase() + "&billing=" + period.name().toLowerCase());
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId())))
                .anySatisfy(acceptance -> {
                    assertThat(acceptance.getTermsVersion()).isEqualTo(AgreementTerms.VERSION);
                    // a purchase consents to no publicity
                    assertThat(acceptance.getPublicityConsent()).isNull();
                });
        return complete(created, lookupKey);
    }

    private StripeRequest checkout(CustomerContact contact, Plan plan, BillingPeriod period, CompanyFigures figures) throws Exception {
        String url = asCustomer(contact, () -> customerAccount.startCheckout(request(plan, period, figures), AgreementTerms.VERSION));
        assertThat(url).startsWith("https://checkout.stripe.test/");
        return StripeStandIn.requests("POST", "/v1/checkout/sessions").stream()
                            .filter(request -> contact.getCustomerId().equals(request.params().get("client_reference_id")))
                            .reduce((first, second) -> second)
                            .orElseThrow();
    }

    private License complete(StripeRequest created, String lookupKey) throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", lookupKey, periodEnd));
        assertThat(deliver(StripeEvents.event("checkout.session.completed", StripeEvents.completedSession(created, subscriptionId, "paid")))
                           .statusCode()).isEqualTo(200);
        return await(licenseRepository.findByStripeSubscriptionId(subscriptionId));
    }

    private Plan planFor(CustomerContact contact, String revenue, String raised) throws Exception {
        return asCustomer(contact, () -> customerAccount.findPlanFor(figures(revenue, raised)));
    }

    private CustomerContact contactOf() throws Exception {
        return await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
    }

    private static CheckoutRequest request(Plan plan, BillingPeriod period, CompanyFigures figures) {
        return new CheckoutRequest().setPlan(plan).setBillingPeriod(period).setFigures(figures);
    }

    private static CompanyFigures figures(String revenue, String raised) {
        return new CompanyFigures().setAnnualRevenue(new BigDecimal(revenue)).setCapitalRaised(raised != null ? new BigDecimal(raised) : null);
    }
}
