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
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.rest.StripeWebhookRoutes;
import org.kinotic.licenses.internal.api.services.KeyRenewalJob;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeEvents;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stripe's webhook events move a license between good standing, past due and cancelled, each handled once however
 * often it is delivered; the success page gets one look at a bought key; and the renewal job issues the next term's
 * key for the paid licenses due.
 */
@ExtendWith(OutputCaptureExtension.class)
class BillingWebhookTest extends ServerTest {

    @Autowired
    private LicenseService licenseService;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private KeyRenewalJob renewalJob;

    @Test
    void eventsMoveTheLicenseAndAreHandledOnce(CapturedOutput output) throws Exception {
        Customer customer = seedCustomer();
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = await(licenseService.createLicense(customer, Plan.STARTUP, subscriptionId, "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();

        JsonObject failed = StripeEvents.event("invoice.payment_failed", StripeEvents.invoice(subscriptionId, null));
        assertThat(deliver(failed).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.PAST_DUE);
        assertThat(output.getAll()).contains("Action needed: your Kinotic payment didn't go through");

        // the same event again changes nothing and sends nothing
        int pastDueEmails = count(output.getAll(), "Action needed: your Kinotic payment didn't go through");
        assertThat(deliver(failed).statusCode()).isEqualTo(200);
        assertThat(deliver(StripeEvents.event("invoice.payment_failed", StripeEvents.invoice(subscriptionId, null))).statusCode()).isEqualTo(200);
        assertThat(count(output.getAll(), "Action needed: your Kinotic payment didn't go through")).isEqualTo(pastDueEmails);

        Instant paidAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, paidAt))).statusCode()).isEqualTo(200);
        License paid = await(licenseRepository.findById(license.getId()));
        assertThat(paid.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(paid.getLastPaymentAt()).isEqualTo(paidAt);

        Instant periodEnd = Instant.now().plus(Duration.ofDays(60)).truncatedTo(ChronoUnit.SECONDS);
        JsonObject updated = StripeEvents.event("customer.subscription.updated",
                                                StripeEvents.subscription(subscriptionId, "active", Plan.BUSINESS.lookupKey(), periodEnd));
        assertThat(deliver(updated).statusCode()).isEqualTo(200);
        License synced = await(licenseRepository.findById(license.getId()));
        assertThat(synced.getPlan()).isEqualTo(Plan.BUSINESS);
        assertThat(synced.getCurrentPeriodEnd()).isEqualTo(periodEnd);

        JsonObject deleted = StripeEvents.event("customer.subscription.deleted",
                                                StripeEvents.subscription(subscriptionId, "canceled", Plan.BUSINESS.lookupKey(), periodEnd));
        assertThat(deliver(deleted).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.CANCELED);
    }

    @Test
    void aRevokedLicenseStaysRevokedWhateverStripeSays() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, subscriptionId, "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        await(licenseService.revoke(license));

        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.REVOKED);
        Instant periodEnd = Instant.now().plus(Duration.ofDays(60)).truncatedTo(ChronoUnit.SECONDS);
        assertThat(deliver(StripeEvents.event("customer.subscription.updated",
                                              StripeEvents.subscription(subscriptionId, "active", Plan.STARTUP.lookupKey(), periodEnd))).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.REVOKED);
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
    void theSuccessPageSeesTheBoughtKeyOnce() throws Exception {
        Customer customer = seedCustomer();
        String sessionId = "cs_" + UUID.randomUUID();
        assertThat(get("/api/checkout/" + sessionId + "/key", null).bodyAsJsonObject().getString("status")).isEqualTo("PENDING");

        License license = await(licenseService.createLicense(customer, Plan.STARTUP, "sub_" + UUID.randomUUID(), sessionId,
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        IssuedKey key = await(licenseService.keysOf(license.getId())).getFirst();

        JsonObject first = get("/api/checkout/" + sessionId + "/key", null).bodyAsJsonObject();
        assertThat(first.getString("status")).isEqualTo("READY");
        assertThat(first.getString("key")).isEqualTo(key.getKey());

        JsonObject second = get("/api/checkout/" + sessionId + "/key", null).bodyAsJsonObject();
        assertThat(second.getString("status")).isEqualTo("SHOWN");
        assertThat(second.containsKey("key")).isFalse();
    }

    @Test
    void theRenewalJobIssuesTheNextKeyForPaidLicensesDue(CapturedOutput output) throws Exception {
        Customer dueCustomer = seedCustomer();
        License due = await(licenseService.createLicense(dueCustomer, Plan.STARTUP, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                         Instant.now().plus(Duration.ofDays(30)))).license();
        Instant termEnd = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
        await(licenseRepository.update(due.setTermEnd(termEnd)));

        License pastDue = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        await(licenseRepository.update(pastDue.setTermEnd(termEnd).setStatus(LicenseStatus.PAST_DUE)));

        License free = await(licenseService.createLicense(seedCustomer(), Plan.FREE, null, null, null)).license();
        await(licenseRepository.update(free.setTermEnd(termEnd)));

        await(renewalJob.run());

        List<IssuedKey> dueKeys = await(licenseService.keysOf(due.getId()));
        assertThat(dueKeys).hasSize(2);
        License renewed = await(licenseRepository.findById(due.getId()));
        assertThat(renewed.getTermStart()).isEqualTo(termEnd);
        assertThat(renewed.getTermEnd()).isEqualTo(LicenseService.termEnd(termEnd));
        assertThat(dueKeys.getFirst().getExpiresAt()).isEqualTo(renewed.getTermEnd().plus(LicenseService.KEY_BUFFER));
        assertThat(output.getAll()).contains("Your renewed Kinotic Enterprise license key");

        assertThat(await(licenseService.keysOf(pastDue.getId()))).hasSize(1);
        assertThat(await(licenseService.keysOf(free.getId()))).hasSize(1);

        // a second run finds the renewed license no longer due
        await(renewalJob.run());
        assertThat(await(licenseService.keysOf(due.getId()))).hasSize(2);
    }

    private static HttpResponse<Buffer> deliver(JsonObject event) throws Exception {
        String payload = event.encode();
        return await(client.post(StripeWebhookRoutes.PATH)
                           .putHeader("Stripe-Signature", StripeEvents.signature(payload))
                           .putHeader("Content-Type", "application/json")
                           .sendBuffer(Buffer.buffer(payload)));
    }

    private static int count(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
