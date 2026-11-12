package org.kinotic.licenses;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.rest.StripeWebhookRoutes;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeEvents;
import org.kinotic.licenses.support.StripeStandIn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stripe's webhook events move a license between good standing, past due and cancelled, each handled once however
 * often it is delivered; a license whose payment has not cleared takes no cluster; and a free license's year moves only
 * while it is active.
 */
@ExtendWith(OutputCaptureExtension.class)
class BillingWebhookTest extends ServerTest {

    @Autowired
    private LicenseService licenseService;
    @Autowired
    private LicenseRepository licenseRepository;

    @Test
    void eventsMoveTheLicenseAndAreHandledOnce(CapturedOutput output) throws Exception {
        Customer customer = seedCustomer();
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(customer, subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(), periodEnd)));

        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "past_due", StripeStandIn.monthly(Plan.SMALL), periodEnd));
        JsonObject failed = StripeEvents.event("invoice.payment_failed", StripeEvents.invoice(subscriptionId, null));
        assertThat(deliver(failed).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.PAST_DUE);
        assertThat(output.getAll()).contains("Action needed: your Kinotic payment didn't go through");

        // the same event again changes nothing and sends nothing, and nor does Stripe's next failed retry
        int pastDueEmails = count(output.getAll(), "Action needed: your Kinotic payment didn't go through");
        assertThat(deliver(failed).statusCode()).isEqualTo(200);
        assertThat(deliver(StripeEvents.event("invoice.payment_failed", StripeEvents.invoice(subscriptionId, null))).statusCode()).isEqualTo(200);
        assertThat(count(output.getAll(), "Action needed: your Kinotic payment didn't go through")).isEqualTo(pastDueEmails);

        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd));
        // a retry that cleared after the license was bought, whose purchase is its first recorded payment
        Instant paidAt = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, paidAt))).statusCode()).isEqualTo(200);
        License paid = await(licenseRepository.findById(license.getId()));
        assertThat(paid.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(paid.getLastPaymentAt()).isEqualTo(paidAt);
        // an older invoice paid late leaves the latest payment the last
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, paidAt.minus(Duration.ofDays(30))))).statusCode())
                .isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getLastPaymentAt()).isEqualTo(paidAt);

        Instant nextPeriodEnd = Instant.now().plus(Duration.ofDays(60)).truncatedTo(ChronoUnit.SECONDS);
        JsonObject upgraded = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.MEDIUM), nextPeriodEnd));
        assertThat(deliver(StripeEvents.event("customer.subscription.updated", upgraded)).statusCode()).isEqualTo(200);
        License synced = await(licenseRepository.findById(license.getId()));
        assertThat(synced.getPlan()).isEqualTo(Plan.MEDIUM);
        assertThat(synced.getCurrentPeriodEnd()).isEqualTo(nextPeriodEnd);

        JsonObject deleted = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "canceled", StripeStandIn.monthly(Plan.MEDIUM), nextPeriodEnd));
        assertThat(deliver(StripeEvents.event("customer.subscription.deleted", deleted)).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.CANCELED);
    }

    @Test
    void anEventDeliveredLateLeavesTheLicenseAsStripeHasIt() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(20)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(), periodEnd)));
        // the customer scheduled the cancellation after moving to the business plan
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd).put("cancel_at_period_end", true));

        // the update from before both changes, delivered last
        JsonObject older = StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.MEDIUM), periodEnd);
        assertThat(deliver(StripeEvents.event("customer.subscription.updated", older)).statusCode()).isEqualTo(200);

        License stored = await(licenseRepository.findById(license.getId()));
        assertThat(stored.getCancelsAt()).isEqualTo(periodEnd);
        assertThat(stored.getPlan()).isEqualTo(Plan.SMALL);
    }

    @Test
    void aRevokedLicenseStaysRevokedWhateverStripeSays() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(60)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)))));
        await(licenseService.revoke(license));
        JsonObject active = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd));

        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.REVOKED);
        assertThat(deliver(StripeEvents.event("customer.subscription.updated", active)).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.REVOKED);
    }

    @Test
    void aCanceledLicenseStaysCanceledWhateverStripeSaysLater() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)))));
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        JsonObject canceled = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "canceled", StripeStandIn.monthly(Plan.SMALL), periodEnd));
        assertThat(deliver(StripeEvents.event("customer.subscription.deleted", canceled)).statusCode()).isEqualTo(200);

        // Stripe never reopens a canceled subscription, so a later report of it in good standing is not believed
        JsonObject active = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd));
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        assertThat(deliver(StripeEvents.event("customer.subscription.updated", active)).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.CANCELED);
    }

    @Test
    void anEventThatCannotBeHandledIsForgottenSoStripeCanRedeliverIt() throws Exception {
        // a data object of a kind the library cannot read fails before any handler runs
        JsonObject event = StripeEvents.event("invoice.paid", new JsonObject().put("id", "x").put("object", "no_such_object"));
        assertThat(deliver(event).statusCode()).isEqualTo(500);
        // the redelivery is handled again rather than acknowledged as already processed
        assertThat(deliver(event).statusCode()).isEqualTo(500);
    }

    @Test
    void anEventForAnUnknownSubscriptionIsAcknowledgedAndIgnored() throws Exception {
        JsonObject event = StripeEvents.event("invoice.paid", StripeEvents.invoice("sub_unknown_" + UUID.randomUUID(), Instant.now()));
        assertThat(deliver(event).statusCode()).isEqualTo(200);
    }

    @Test
    void aDeliveryWithABadSignatureIsRefused() throws Exception {
        String payload = StripeEvents.event("invoice.paid", StripeEvents.invoice("sub_x", Instant.now())).encode();
        HttpResponse<Buffer> response = await(client.post(StripeWebhookRoutes.PATH)
                                                    .putHeader("Stripe-Signature", StripeEvents.signature(payload, "whsec_wrong"))
                                                    .putHeader("Content-Type", "application/json")
                                                    .sendBuffer(Buffer.buffer(payload)));
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void aPaidCheckoutCreatesTheLicenseOnce(CapturedOutput output) throws Exception {
        Customer customer = seedCustomer();
        String subscriptionId = "sub_" + UUID.randomUUID();
        String sessionId = "cs_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.MEDIUM), periodEnd));
        JsonObject completed = StripeEvents.event("checkout.session.completed",
                                                  StripeEvents.checkoutSession(sessionId, subscriptionId, customer.getId(), "paid"));

        assertThat(deliver(completed).statusCode()).isEqualTo(200);
        License license = await(licenseRepository.findByCheckoutSessionId(sessionId));
        assertThat(license.getCustomerId()).isEqualTo(customer.getId());
        assertThat(license.getPlan()).isEqualTo(Plan.MEDIUM);
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(license.getCurrentPeriodEnd()).isEqualTo(periodEnd);
        assertThat(license.getSeats()).isEqualTo(1);
        int readyEmails = count(output.getAll(), "Your Kinotic Enterprise license is ready");
        assertThat(readyEmails).isPositive();

        assertThat(deliver(completed).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findByCustomerId(customer.getId()))).hasSize(1);
        assertThat(count(output.getAll(), "Your Kinotic Enterprise license is ready")).isEqualTo(readyEmails);
    }

    @Test
    void aCheckoutForSeveralClustersHoldsThatManySeats() throws Exception {
        Customer customer = seedCustomer();
        String subscriptionId = "sub_" + UUID.randomUUID();
        String sessionId = "cs_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        JsonObject subscription = StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd);
        subscription.getJsonObject("items").getJsonArray("data").getJsonObject(0).put("quantity", 3);
        StripeStandIn.report(subscription);

        assertThat(deliver(StripeEvents.event("checkout.session.completed",
                                              StripeEvents.checkoutSession(sessionId, subscriptionId, customer.getId(), "paid"))).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findByCheckoutSessionId(sessionId)).getSeats()).isEqualTo(3);
    }

    @Test
    void aBankDebitTakesClustersWhenThePaymentClears(CapturedOutput output) throws Exception {
        Customer customer = seedCustomer();
        String subscriptionId = "sub_" + UUID.randomUUID();
        String sessionId = "cs_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        // Checkout completes while the debit is still clearing
        JsonObject clearing = StripeEvents.subscription(subscriptionId, "incomplete", StripeStandIn.monthly(Plan.SMALL), periodEnd);
        clearing.getJsonObject("latest_invoice").put("status", "open");
        StripeStandIn.report(clearing);
        assertThat(deliver(StripeEvents.event("checkout.session.completed",
                                              StripeEvents.checkoutSession(sessionId, subscriptionId, customer.getId(), "unpaid"))).statusCode()).isEqualTo(200);
        License awaiting = await(licenseRepository.findByCheckoutSessionId(sessionId));
        assertThat(awaiting.getStatus()).isEqualTo(LicenseStatus.PAST_DUE);
        assertThatThrownBy(() -> addCluster(awaiting)).hasCauseInstanceOf(IllegalStateException.class);
        int readyEmails = count(output.getAll(), "Your Kinotic Enterprise license is ready");

        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd));
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);

        License paid = await(licenseRepository.findById(awaiting.getId()));
        assertThat(paid.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(count(output.getAll(), "Your Kinotic Enterprise license is ready")).isEqualTo(readyEmails + 1);
        addCluster(paid);
        List<IssuedKey> keys = await(licenseService.keysOf(awaiting.getId()));
        assertThat(keys).hasSize(1);
        assertThat(keys.getFirst().getExpiresAt()).isEqualTo(LicenseService.keyExpiry(periodEnd));

        // the next invoice of a license whose cluster has its key issues no other
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        assertThat(await(licenseService.keysOf(awaiting.getId()))).hasSize(1);
    }

    @Test
    void aDebitThatClearedBeforeItsCheckoutWasHandledIsActiveAtOnce() throws Exception {
        Customer customer = seedCustomer();
        String subscriptionId = "sub_" + UUID.randomUUID();
        String sessionId = "cs_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        // the invoice.paid came first and found no license; Stripe has the first invoice paid by now
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), periodEnd));

        assertThat(deliver(StripeEvents.event("checkout.session.completed",
                                              StripeEvents.checkoutSession(sessionId, subscriptionId, customer.getId(), "unpaid"))).statusCode()).isEqualTo(200);
        License license = await(licenseRepository.findByCheckoutSessionId(sessionId));
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
    }

    @Test
    void anEventWhoseDeliveryDiedIsHandledWhenStripeRedeliversIt() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)))));
        JsonObject event = StripeEvents.event("customer.subscription.deleted",
                                              StripeStandIn.report(StripeEvents.subscription(subscriptionId, "canceled", StripeStandIn.monthly(Plan.SMALL), Instant.now())));
        // a delivery that claimed the event and stopped before finishing it, long enough ago for its claim to expire
        sql("INSERT INTO stripe_event (id, type, claimed_at) VALUES ($1, $2, now() - interval '10 minutes')",
            event.getString("id"), event.getString("type"));

        assertThat(deliver(event).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.CANCELED);
    }

    @Test
    void aRevokedLicenseIsNotRenewed() throws Exception {
        License license = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        // the renewal read the license before staff revoked it
        await(licenseService.revoke(license));
        assertThatThrownBy(() -> await(licenseService.renewFreeTerm(license, null))).hasCauseInstanceOf(IllegalStateException.class);
        License stored = await(licenseRepository.findById(license.getId()));
        assertThat(stored.getStatus()).isEqualTo(LicenseStatus.REVOKED);
        assertThat(stored.getTermEnd()).isEqualTo(license.getTermEnd());
    }

    @Test
    void aRenewalOfALicenseThatChangedSinceItWasReadIsNotStored() throws Exception {
        License license = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        // the renewal read the license before another renewal moved its year
        sql("UPDATE license SET term_end = term_end + interval '1 day' WHERE id = $1", license.getId());
        assertThatThrownBy(() -> await(licenseService.renewFreeTerm(license, null))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(await(licenseRepository.findById(license.getId())).getTermEnd()).isEqualTo(license.getTermEnd().plus(Duration.ofDays(1)));
    }

    @Test
    void aRevokedLicenseIsIssuedNoKey() throws Exception {
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)))));
        LicensedCluster cluster = addCluster(license);
        await(licenseService.revoke(license));
        assertThatThrownBy(() -> await(licenseService.issueKey(license, cluster))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void aNewFreeYearIssuesEachSeatedClusterOneKey() throws Exception {
        License license = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        addCluster(license);
        sql("UPDATE license SET term_start = term_start - interval '1 year', term_end = date_trunc('second', now()) - interval '1 day' WHERE id = $1",
            license.getId());
        // the key the cluster holds covers the year that ended
        sql("UPDATE issued_key SET expires_at = date_trunc('second', now()) + interval '6 days' WHERE license_id = $1", license.getId());

        License renewed = await(licenseService.renewFreeTerm(await(licenseRepository.findById(license.getId())), null));
        assertThat(await(licenseService.issueExtendedKeys(renewed))).isEqualTo(1);
        assertThat(await(licenseService.issueExtendedKeys(renewed))).isZero();
        List<IssuedKey> keys = await(licenseService.keysOf(license.getId()));
        assertThat(keys).hasSize(2);
        assertThat(keys.getFirst().getExpiresAt()).isEqualTo(LicenseService.keyExpiry(renewed.getTermEnd()));
    }

    private static int count(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
