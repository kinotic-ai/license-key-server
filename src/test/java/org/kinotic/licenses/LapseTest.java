package org.kinotic.licenses;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.LicenseLapseJob;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeEvents;
import org.kinotic.licenses.support.StripeStandIn;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A license past due records when it fell past due, clears it once paid, and lapses after 30 days unpaid: the lapse job
 * cancels its subscription, and Stripe's later report of the cancellation leaves it lapsed.
 */
class LapseTest extends ServerTest {

    @Autowired
    private LicenseService licenseService;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private LicenseLapseJob lapseJob;

    @Test
    void aFailedPaymentRecordsWhenTheLicenseFellPastDueUntilItIsPaid() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = paidLicense(subscriptionId);

        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "past_due", StripeStandIn.monthly(Plan.SMALL), periodEnd()));
        assertThat(deliver(StripeEvents.event("invoice.payment_failed", StripeEvents.invoice(subscriptionId, null))).statusCode()).isEqualTo(200);
        Instant since = await(licenseRepository.findById(license.getId())).getPastDueSince();
        assertThat(since).isNotNull();

        // Stripe's next failed retry keeps the time the license first fell past due
        assertThat(deliver(StripeEvents.event("invoice.payment_failed", StripeEvents.invoice(subscriptionId, null))).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getPastDueSince()).isEqualTo(since);

        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd()));
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        License paid = await(licenseRepository.findById(license.getId()));
        assertThat(paid.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(paid.getPastDueSince()).isNull();
    }

    @Test
    void aLicensePastDueForThirtyDaysLapsesAndItsSubscriptionIsCancelled() throws Exception {
        String overdueSubscription = "sub_" + UUID.randomUUID();
        License overdue = pastDue(overdueSubscription, 31);
        String recentSubscription = "sub_" + UUID.randomUUID();
        License recent = pastDue(recentSubscription, 10);
        // settled with Stripe, whose event has not arrived yet
        String settledSubscription = "sub_" + UUID.randomUUID();
        License settled = pastDue(settledSubscription, 31);
        StripeStandIn.report(StripeEvents.subscription(settledSubscription, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd()));

        await(lapseJob.run());

        assertThat(await(licenseRepository.findById(overdue.getId())).getStatus()).isEqualTo(LicenseStatus.LAPSED);
        assertThat(StripeStandIn.subscription(overdueSubscription).getString("status")).isEqualTo("canceled");
        assertThat(await(licenseRepository.findById(recent.getId())).getStatus()).isEqualTo(LicenseStatus.PAST_DUE);
        assertThat(StripeStandIn.subscription(recentSubscription).getString("status")).isEqualTo("past_due");
        assertThat(await(licenseRepository.findById(settled.getId())).getStatus()).isEqualTo(LicenseStatus.PAST_DUE);
        assertThat(StripeStandIn.subscription(settledSubscription).getString("status")).isEqualTo("active");

        // Stripe reports the cancellation, and later a payment of the cancelled subscription: the license stays lapsed
        JsonObject canceled = StripeStandIn.subscription(overdueSubscription);
        assertThat(deliver(StripeEvents.event("customer.subscription.deleted", canceled)).statusCode()).isEqualTo(200);
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(overdueSubscription, Instant.now()))).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(overdue.getId())).getStatus()).isEqualTo(LicenseStatus.LAPSED);
    }

    private License pastDue(String subscriptionId, int days) throws Exception {
        License license = paidLicense(subscriptionId);
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "past_due", StripeStandIn.monthly(Plan.SMALL), periodEnd()));
        sql("UPDATE license SET status = 'PAST_DUE', past_due_since = now() - make_interval(days => $2) WHERE id = $1",
            license.getId(), days);
        return license;
    }

    private License paidLicense(String subscriptionId) throws Exception {
        return await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(),
                                                                               periodEnd()))).license();
    }

    private static Instant periodEnd() {
        return Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
    }
}
