package org.kinotic.licenses;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerAccount;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.CustomerSummary;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.FreeLicenseRequestStatus;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseFlag;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.RevenueConfirmation;
import org.kinotic.licenses.api.services.StaffCustomerService;
import org.kinotic.licenses.api.services.StaffFreeLicenseRequestService;
import org.kinotic.licenses.internal.api.repositories.FreeLicenseRequestRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.AnniversaryJob;
import org.kinotic.licenses.internal.api.services.LicenseService;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The yearly confirmation of a customer's figures: asked for once 30 days before the anniversary, it moves a paid
 * license to the plan the figures set at the anniversary through a Subscription Schedule on the license's own price
 * list, ends a paid subscription whose figures set Free so the license continues as Free, asks staff to renew a Free
 * license that still qualifies, and leaves a Free license that crossed a limit to buy its plan. An anniversary passed
 * unconfirmed flags the license for staff and changes nothing else.
 */
@ExtendWith(OutputCaptureExtension.class)
class YearlyConfirmationTest extends ServerTest {

    private static final String CONFIRMATION_DUE = "Confirm your figures for your Kinotic license";
    private static final String PLAN_CHANGING = "Your Kinotic plan is changing";

    @Autowired
    private StaffCustomerService staffCustomers;
    @Autowired
    private StaffFreeLicenseRequestService staffRequests;
    @Autowired
    private LicenseService licenseService;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private FreeLicenseRequestRepository requestRepository;
    @Autowired
    private AnniversaryJob anniversaryJob;

    @Test
    void theContactsAreAskedOnceToConfirmBeforeTheAnniversary(CapturedOutput output) throws Exception {
        License license = paidLicense(seedCustomer(), "sub_" + UUID.randomUUID());
        CustomerContact contact = contactOf(license);
        assertThatThrownBy(() -> confirm(contact, license, figures("3000000", null))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(account(contact).getLicenses().getFirst().getConfirmationDueFor()).isNull();

        sql("UPDATE license SET term_end = now() + interval '10 days' WHERE id = $1", license.getId());
        int before = count(output.getAll(), CONFIRMATION_DUE);
        await(anniversaryJob.run());
        assertThat(count(output.getAll(), CONFIRMATION_DUE)).isGreaterThan(before);
        int asked = count(output.getAll(), CONFIRMATION_DUE);
        await(anniversaryJob.run());
        assertThat(count(output.getAll(), CONFIRMATION_DUE)).isEqualTo(asked);
        License stored = await(licenseRepository.findById(license.getId()));
        assertThat(account(contact).getLicenses().getFirst().getConfirmationDueFor()).isEqualTo(stored.getTermEnd());
    }

    @Test
    void figuresThatSetAnotherPaidPlanMoveTheLicenseAtItsAnniversaryOnItsOwnPriceList(CapturedOutput output) throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = paidLicense(seedCustomer(), subscriptionId);
        sql("UPDATE license SET term_end = now() + interval '10 days', price_list = $2 WHERE id = $1", license.getId(), StripeStandIn.OLDER_PRICE_LIST);
        License due = await(licenseRepository.findById(license.getId()));
        CustomerContact contact = contactOf(license);

        RevenueConfirmation confirmed = confirm(contact, due, figures("30000000", null));
        assertThat(confirmed.getPlan()).isEqualTo(Plan.MEDIUM);
        assertThat(confirmed.getLicensePlan()).isEqualTo(Plan.SMALL);
        assertThat(confirmed.getAnniversary()).isEqualTo(due.getTermEnd());
        assertThat(confirmed.getFigures().getCapitalRaised()).isNull();
        assertThat(output.getAll()).contains(PLAN_CHANGING);

        StripeRequest update = StripeStandIn.requests("POST", "/v1/subscription_schedules/").getLast();
        assertThat(update.params().get("phases[0][end_date]")).isEqualTo(Long.toString(due.getTermEnd().getEpochSecond()));
        assertThat(update.params().get("phases[0][items][0][price]")).isEqualTo(StripeStandIn.priceId(StripeStandIn.monthly(Plan.SMALL)));
        assertThat(update.params().get("phases[1][items][0][price]"))
                .isEqualTo(StripeStandIn.priceId(StripeStandIn.lookupKey(StripeStandIn.OLDER_PRICE_LIST, Plan.MEDIUM, BillingPeriod.MONTHLY)));
        assertThat(update.params().get("end_behavior")).isEqualTo("release");

        // the figures for an anniversary are confirmed once
        assertThatThrownBy(() -> confirm(contact, due, figures("30000000", null))).hasCauseInstanceOf(AlreadyExistsException.class);

        // at the anniversary Stripe moves the subscription, and reports it
        String mediumOnOwnList = StripeStandIn.lookupKey(StripeStandIn.OLDER_PRICE_LIST, Plan.MEDIUM, BillingPeriod.MONTHLY);
        assertThat(deliver(StripeEvents.event("customer.subscription.updated",
                                              StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", mediumOnOwnList, periodEnd()))))
                           .statusCode()).isEqualTo(200);
        License moved = await(licenseRepository.findById(license.getId()));
        assertThat(moved.getPlan()).isEqualTo(Plan.MEDIUM);
        assertThat(moved.getPriceList()).isEqualTo(StripeStandIn.OLDER_PRICE_LIST);
    }

    @Test
    void figuresThatSetFreeEndThePaidSubscriptionAndTheLicenseContinuesAsFree() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = paidLicense(seedCustomer(), subscriptionId);
        sql("UPDATE license SET term_end = now() + interval '1 hour' WHERE id = $1", license.getId());
        License due = await(licenseRepository.findById(license.getId()));

        RevenueConfirmation confirmed = confirm(contactOf(license), due, figures("400000", "1000000"));
        assertThat(confirmed.getPlan()).isEqualTo(Plan.FREE);
        assertThat(StripeStandIn.subscription(subscriptionId).getLong("cancel_at")).isEqualTo(due.getTermEnd().getEpochSecond());

        // Stripe ends the subscription at the anniversary, the cancel_at it was given
        assertThat(deliver(StripeEvents.event("customer.subscription.deleted", endAt(subscriptionId))).statusCode()).isEqualTo(200);
        License free = await(licenseRepository.findById(license.getId()));
        assertThat(free.getPlan()).isEqualTo(Plan.FREE);
        assertThat(free.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(free.getStripeSubscriptionId()).isNull();
        assertThat(free.getBillingPeriod()).isNull();

        // once the anniversary has passed, its next year starts as Free with the key covering it
        shiftAnniversary(license.getId());
        License passed = await(licenseRepository.findById(license.getId()));
        await(anniversaryJob.run());
        License renewed = await(licenseRepository.findById(license.getId()));
        assertThat(renewed.getTermEnd()).isAfter(Instant.now().plus(Duration.ofDays(300)));
        assertThat(await(licenseService.keysOf(license.getId())).getFirst().getExpiresAt()).isEqualTo(LicenseService.keyExpiry(renewed.getTermEnd()));
        assertThat(renewed.getTermStart()).isEqualTo(passed.getTermEnd());
    }

    @Test
    void aFreeLicenseThatStillQualifiesAsksStaffToRenewIt() throws Exception {
        License license = freeLicense(seedCustomer());
        // the request the license was approved on
        FreeLicenseRequest first = await(requestRepository.save(new FreeLicenseRequest().setId(UUID.randomUUID().toString()).setCustomerId(license.getCustomerId())
                                                                                        .setCertification(new FreeLicenseCertification()
                                                                                                                  .setIntendedUse("Our scheduling app")
                                                                                                                  .setFigures(figures("100000", "0")))
                                                                                        .setStatus(FreeLicenseRequestStatus.PENDING)));
        sql("UPDATE free_license_request SET status = 'APPROVED', license_id = $2"
                    + " WHERE id = $1", first.getId(), license.getId());
        sql("UPDATE license SET term_end = now() + interval '10 days' WHERE id = $1", license.getId());
        License due = await(licenseRepository.findById(license.getId()));

        assertThatThrownBy(() -> confirm(contactOf(license), due, figures("200000", null))).hasCauseInstanceOf(IllegalArgumentException.class);
        RevenueConfirmation confirmed = confirm(contactOf(license), due, figures("200000", "1500000"));
        assertThat(confirmed.getPlan()).isEqualTo(Plan.FREE);
        assertThat(confirmed.getFigures().getCapitalRaised()).isEqualByComparingTo(new BigDecimal("1500000"));

        FreeLicenseRequest renewal = await(staffRequests.findPending()).stream()
                                                                      .filter(request -> request.getCustomerId().equals(license.getCustomerId()))
                                                                      .findFirst().orElseThrow();
        assertThat(renewal.getCertification().getIntendedUse()).isEqualTo("Our scheduling app");
        // the anniversary waits for staff
        shiftAnniversary(license.getId());
        await(anniversaryJob.run());
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);

        asStaff(() -> staffRequests.approve(renewal.getId()));
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(2);
    }

    @Test
    void aFreeLicenseWhoseCapitalRaisedReachesFiveMillionMustBuyAPaidPlan(CapturedOutput output) throws Exception {
        License license = freeLicense(seedCustomer());
        sql("UPDATE license SET term_end = now() + interval '10 days' WHERE id = $1", license.getId());
        License due = await(licenseRepository.findById(license.getId()));

        RevenueConfirmation confirmed = confirm(contactOf(license), due, figures("600000", "5000000"));
        assertThat(confirmed.getPlan()).isEqualTo(Plan.SMALL);
        assertThat(output.getAll()).contains("now set the Small plan");
        assertThat(await(requestRepository.findByCustomerId(license.getCustomerId()))).isEmpty();

        // the anniversary passes without the plan bought: the license gets no next year
        shiftAnniversary(license.getId());
        await(anniversaryJob.run());
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
        assertThat(await(licenseRepository.findById(license.getId())).getFlags()).doesNotContain(LicenseFlag.REVENUE_UNCONFIRMED);
    }

    @Test
    void anAnniversaryPassedUnconfirmedFlagsTheLicenseUntilTheFiguresAreConfirmed() throws Exception {
        Customer customer = seedCustomer();
        License license = paidLicense(customer, "sub_" + UUID.randomUUID());
        sql("UPDATE license SET term_end = now() - interval '1 hour' WHERE id = $1", license.getId());
        License passed = await(licenseRepository.findById(license.getId()));

        await(anniversaryJob.run());

        License flagged = await(licenseRepository.findById(license.getId()));
        assertThat(flagged.getFlags()).contains(LicenseFlag.REVENUE_UNCONFIRMED);
        assertThat(flagged.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(flagged.getPlan()).isEqualTo(Plan.SMALL);
        assertThat(flagged.getTermStart()).isEqualTo(passed.getTermEnd());
        List<CustomerSummary> queue = asStaff(staffCustomers::findRevenueUnconfirmed);
        assertThat(queue).extracting(CustomerSummary::getId).contains(customer.getId());

        // confirmed late, for the anniversary it missed
        RevenueConfirmation confirmed = confirm(contactOf(license), flagged, figures("2000000", null));
        assertThat(confirmed.getAnniversary()).isEqualTo(passed.getTermEnd());
        assertThat(await(licenseRepository.findById(license.getId())).getFlags()).doesNotContain(LicenseFlag.REVENUE_UNCONFIRMED);
        assertThat(asStaff(staffCustomers::findRevenueUnconfirmed)).extracting(CustomerSummary::getId).doesNotContain(customer.getId());
    }

    @Test
    void aLateConfirmationThatSetsFreeEndsTheSubscriptionAtTheNextAnniversaryWhicheverComesFirst() throws Exception {
        for (boolean webhookFirst : new boolean[]{true, false}) {
            String subscriptionId = "sub_" + UUID.randomUUID();
            License license = paidLicense(seedCustomer(), subscriptionId);
            // the anniversary passed unconfirmed, so the license was flagged and its next year started
            sql("UPDATE license SET term_end = now() - interval '1 minute' WHERE id = $1", license.getId());
            await(anniversaryJob.run());
            License flagged = await(licenseRepository.findById(license.getId()));
            assertThat(flagged.getFlags()).contains(LicenseFlag.REVENUE_UNCONFIRMED);

            RevenueConfirmation late = confirm(contactOf(license), flagged, figures("400000", "1000000"));
            assertThat(late.getAnniversary()).isEqualTo(flagged.getTermStart());
            assertThat(late.getAppliesAt()).isEqualTo(flagged.getTermEnd());
            assertThat(StripeStandIn.subscription(subscriptionId).getLong("cancel_at")).isEqualTo(flagged.getTermEnd().getEpochSecond());

            // the next anniversary arrives, and with it the end of the subscription
            shiftAnniversary(license.getId());
            if (webhookFirst) {
                assertThat(deliver(StripeEvents.event("customer.subscription.deleted", endAt(subscriptionId))).statusCode()).isEqualTo(200);
                await(anniversaryJob.run());
            } else {
                await(anniversaryJob.run());
                assertThat(deliver(StripeEvents.event("customer.subscription.deleted", endAt(subscriptionId))).statusCode()).isEqualTo(200);
            }
            License free = await(licenseRepository.findById(license.getId()));
            assertThat(free.getPlan()).as("webhook first: %s", webhookFirst).isEqualTo(Plan.FREE);
            assertThat(free.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
            assertThat(free.getTermEnd()).isAfter(Instant.now().plus(Duration.ofDays(300)));
        }
    }

    @Test
    void aLateConfirmationThatSetsAPaidPlanLeavesAFreeLicenseToBuyItByTheNextAnniversary() throws Exception {
        License license = freeLicense(seedCustomer());
        sql("UPDATE license SET term_end = now() - interval '1 minute' WHERE id = $1", license.getId());
        keysCoverTheYear(license.getId());
        await(anniversaryJob.run());
        License flagged = await(licenseRepository.findById(license.getId()));
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(2);

        RevenueConfirmation late = confirm(contactOf(license), flagged, figures("1500000", "0"));
        assertThat(late.getPlan()).isEqualTo(Plan.SMALL);

        // the next anniversary passes with the plan not bought: no next year, though it is unconfirmed again
        shiftAnniversary(license.getId());
        await(anniversaryJob.run());
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(2);
        assertThat(await(licenseRepository.findById(license.getId())).getTermEnd()).isBefore(Instant.now());
    }

    @Test
    void newerFiguresThatKeepThePlanUndoTheMoveEarlierFiguresSet() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = paidLicense(seedCustomer(), subscriptionId);
        sql("UPDATE license SET term_end = now() - interval '1 minute' WHERE id = $1", license.getId());
        await(anniversaryJob.run());
        License flagged = await(licenseRepository.findById(license.getId()));
        confirm(contactOf(license), flagged, figures("400000", "1000000"));
        assertThat(StripeStandIn.subscription(subscriptionId).getLong("cancel_at")).isNotNull();

        // the next anniversary's window opens, and that year's figures keep the Small plan
        sql("WITH old AS (SELECT term_end FROM license WHERE id = $1),"
                    + " moved AS (UPDATE license SET term_end = date_trunc('second', now()) + interval '10 days' WHERE id = $1 RETURNING term_end)"
                    + " UPDATE revenue_confirmation SET applies_at = (SELECT term_end FROM moved)"
                    + " WHERE license_id = $1 AND applies_at = (SELECT term_end FROM old)", license.getId());
        License due = await(licenseRepository.findById(license.getId()));
        RevenueConfirmation kept = confirm(contactOf(license), due, figures("2000000", null));
        assertThat(kept.getPlan()).isEqualTo(Plan.SMALL);
        assertThat(StripeStandIn.subscription(subscriptionId).getValue("cancel_at")).isNull();

        // at the anniversary the license goes on paid, its subscription kept
        shiftAnniversary(license.getId());
        await(anniversaryJob.run());
        License renewed = await(licenseRepository.findById(license.getId()));
        assertThat(renewed.getPlan()).isEqualTo(Plan.SMALL);
        assertThat(renewed.getStripeSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(renewed.getTermEnd()).isAfter(Instant.now());
    }

    @Test
    void aSubscriptionResumedAfterFiguresSetFreeStaysPaid() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = paidLicense(seedCustomer(), subscriptionId);
        sql("UPDATE license SET term_end = now() + interval '1 hour' WHERE id = $1", license.getId());
        confirm(contactOf(license), await(licenseRepository.findById(license.getId())), figures("400000", "1000000"));
        // the customer resumed the subscription in the Customer Portal
        StripeStandIn.report(StripeStandIn.subscription(subscriptionId).copy().putNull("cancel_at"));

        shiftAnniversary(license.getId());
        await(anniversaryJob.run());
        License stillPaid = await(licenseRepository.findById(license.getId()));
        assertThat(stillPaid.getPlan()).isEqualTo(Plan.SMALL);
        assertThat(stillPaid.getStripeSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(stillPaid.getTermEnd()).isAfter(Instant.now());
    }

    // Moves the license's anniversary to just past, with the confirmations for it and the plans that apply at it; a
    // subscription set to end at it ends at the new time.
    // the keys of a free license expire as those issued for its current year would
    private void keysCoverTheYear(String licenseId) throws Exception {
        sql("UPDATE issued_key k SET expires_at = date_trunc('second', l.term_end) + interval '7 days' FROM license l"
                    + " WHERE l.id = $1 AND k.license_id = l.id", licenseId);
    }

    private void shiftAnniversary(String licenseId) throws Exception {
        sql("WITH old AS (SELECT term_end FROM license WHERE id = $1),"
                    + " moved AS (UPDATE license SET term_end = date_trunc('second', now()) - interval '1 minute' WHERE id = $1 RETURNING term_end)"
                    + " UPDATE revenue_confirmation SET"
                    + " anniversary = CASE WHEN anniversary = (SELECT term_end FROM old) THEN (SELECT term_end FROM moved) ELSE anniversary END,"
                    + " applies_at = CASE WHEN applies_at = (SELECT term_end FROM old) THEN (SELECT term_end FROM moved) ELSE applies_at END"
                    + " WHERE license_id = $1 AND (anniversary = (SELECT term_end FROM old) OR applies_at = (SELECT term_end FROM old))", licenseId);
        License moved = await(licenseRepository.findById(licenseId));
        if (moved.getPlan() == Plan.FREE) {
            keysCoverTheYear(licenseId);
        }
        if (moved.getStripeSubscriptionId() != null && StripeStandIn.subscription(moved.getStripeSubscriptionId()).getValue("cancel_at") != null) {
            StripeStandIn.report(StripeStandIn.subscription(moved.getStripeSubscriptionId()).copy().put("cancel_at", moved.getTermEnd().getEpochSecond()));
        }
    }

    // the subscription as Stripe has it once it ended at its cancel_at
    private static JsonObject endAt(String subscriptionId) {
        return StripeStandIn.report(StripeStandIn.subscription(subscriptionId).copy().put("status", "canceled"));
    }

    private RevenueConfirmation confirm(CustomerContact contact, License license, CompanyFigures figures) throws Exception {
        return asCustomer(contact, () -> customerAccount.confirmRevenue(license.getId(), figures));
    }

    private CustomerAccount account(CustomerContact contact) throws Exception {
        return asCustomer(contact, customerAccount::getAccount);
    }

    private CustomerContact contactOf(License license) throws Exception {
        return await(contactRepository.findByCustomerId(license.getCustomerId())).getFirst();
    }

    // a paid license with one cluster
    private License paidLicense(Customer customer, String subscriptionId) throws Exception {
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd()));
        License ret = await(licenseService.createLicense(customer, subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(), periodEnd())));
        addCluster(ret);
        return ret;
    }

    // a free license with one cluster
    private License freeLicense(Customer customer) throws Exception {
        License ret = await(licenseService.createFreeLicense(customer, StripeStandIn.CURRENT_PRICE_LIST, null));
        addCluster(ret);
        return ret;
    }

    private static CompanyFigures figures(String revenue, String raised) {
        return new CompanyFigures().setAnnualRevenue(new BigDecimal(revenue)).setCapitalRaised(raised != null ? new BigDecimal(raised) : null);
    }

    private static Instant periodEnd() {
        return Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
    }

    private static int count(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
