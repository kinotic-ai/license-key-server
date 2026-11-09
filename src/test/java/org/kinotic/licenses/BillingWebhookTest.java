package org.kinotic.licenses;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kinotic.licenses.api.model.CheckoutKey;
import org.kinotic.licenses.api.model.CheckoutKeyStatus;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.services.CustomerAccountService;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.rest.StripeWebhookRoutes;
import org.kinotic.licenses.internal.api.model.NewLicense;
import org.kinotic.licenses.internal.api.services.KeyRenewalJob;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeEvents;
import org.kinotic.licenses.support.StripeStandIn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired
    private CustomerAccountService customerAccount;

    @Test
    void eventsMoveTheLicenseAndAreHandledOnce(CapturedOutput output) throws Exception {
        Customer customer = seedCustomer();
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(customer, Plan.STARTUP, subscriptionId, "cs_" + UUID.randomUUID(), periodEnd)).license();

        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "past_due", Plan.STARTUP.lookupKey(), periodEnd));
        JsonObject failed = StripeEvents.event("invoice.payment_failed", StripeEvents.invoice(subscriptionId, null));
        assertThat(deliver(failed).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.PAST_DUE);
        assertThat(output.getAll()).contains("Action needed: your Kinotic payment didn't go through");

        // the same event again changes nothing and sends nothing, and nor does Stripe's next failed retry
        int pastDueEmails = count(output.getAll(), "Action needed: your Kinotic payment didn't go through");
        assertThat(deliver(failed).statusCode()).isEqualTo(200);
        assertThat(deliver(StripeEvents.event("invoice.payment_failed", StripeEvents.invoice(subscriptionId, null))).statusCode()).isEqualTo(200);
        assertThat(count(output.getAll(), "Action needed: your Kinotic payment didn't go through")).isEqualTo(pastDueEmails);

        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", Plan.STARTUP.lookupKey(), periodEnd));
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
        JsonObject upgraded = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", Plan.BUSINESS.lookupKey(), nextPeriodEnd));
        assertThat(deliver(StripeEvents.event("customer.subscription.updated", upgraded)).statusCode()).isEqualTo(200);
        License synced = await(licenseRepository.findById(license.getId()));
        assertThat(synced.getPlan()).isEqualTo(Plan.BUSINESS);
        assertThat(synced.getCurrentPeriodEnd()).isEqualTo(nextPeriodEnd);

        JsonObject deleted = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "canceled", Plan.BUSINESS.lookupKey(), nextPeriodEnd));
        assertThat(deliver(StripeEvents.event("customer.subscription.deleted", deleted)).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.CANCELED);
    }

    @Test
    void anEventDeliveredLateLeavesTheLicenseAsStripeHasIt() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(20)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, subscriptionId, "cs_" + UUID.randomUUID(), periodEnd)).license();
        // the customer scheduled the cancellation after moving to the business plan
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", Plan.STARTUP.lookupKey(), periodEnd).put("cancel_at_period_end", true));

        // the update from before both changes, delivered last
        JsonObject older = StripeEvents.subscription(subscriptionId, "active", Plan.BUSINESS.lookupKey(), periodEnd);
        assertThat(deliver(StripeEvents.event("customer.subscription.updated", older)).statusCode()).isEqualTo(200);

        License stored = await(licenseRepository.findById(license.getId()));
        assertThat(stored.getCancelsAt()).isEqualTo(periodEnd);
        assertThat(stored.getPlan()).isEqualTo(Plan.STARTUP);
    }

    @Test
    void aRevokedLicenseStaysRevokedWhateverStripeSays() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(60)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, subscriptionId, "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        await(licenseService.revoke(license));
        JsonObject active = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", Plan.STARTUP.lookupKey(), periodEnd));

        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.REVOKED);
        assertThat(deliver(StripeEvents.event("customer.subscription.updated", active)).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.REVOKED);
    }

    @Test
    void aCanceledLicenseStaysCanceledWhateverStripeSaysLater() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, subscriptionId, "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        JsonObject canceled = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "canceled", Plan.STARTUP.lookupKey(), periodEnd));
        assertThat(deliver(StripeEvents.event("customer.subscription.deleted", canceled)).statusCode()).isEqualTo(200);

        // Stripe never reopens a canceled subscription, so a later report of it in good standing is not believed
        JsonObject active = StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", Plan.STARTUP.lookupKey(), periodEnd));
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
    void theSuccessPageSeesTheBoughtKeyOnceAndOnlyTheBuyerSeesIt() throws Exception {
        Customer customer = seedCustomer();
        CustomerContact buyer = await(contactRepository.findByCustomerId(customer.getId())).getFirst();
        CustomerContact other = await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
        String sessionId = "cs_" + UUID.randomUUID();
        assertThat(checkoutKey(buyer, sessionId).getStatus()).isEqualTo(CheckoutKeyStatus.PENDING);

        License license = await(licenseService.createLicense(customer, Plan.STARTUP, "sub_" + UUID.randomUUID(), sessionId,
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        IssuedKey key = await(licenseService.keysOf(license.getId())).getFirst();

        // another customer that names the session is told nothing of it
        assertThat(checkoutKey(other, sessionId).getStatus()).isEqualTo(CheckoutKeyStatus.PENDING);
        CheckoutKey first = checkoutKey(buyer, sessionId);
        assertThat(first.getStatus()).isEqualTo(CheckoutKeyStatus.READY);
        assertThat(first.getKey()).isEqualTo(key.getKey());

        CheckoutKey second = checkoutKey(buyer, sessionId);
        assertThat(second.getStatus()).isEqualTo(CheckoutKeyStatus.SHOWN);
        assertThat(second.getKey()).isNull();
    }

    @Test
    void theRenewalJobIssuesTheNextKeyForPaidLicensesDue(CapturedOutput output) throws Exception {
        Customer dueCustomer = seedCustomer();
        License due = await(licenseService.createLicense(dueCustomer, Plan.STARTUP, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                         Instant.now().plus(Duration.ofDays(30)))).license();
        Instant termEnd = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
        setTermEnd(due.getId(), termEnd);

        License pastDue = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        setTermEnd(pastDue.getId(), termEnd);
        sql("UPDATE license SET status = 'PAST_DUE' WHERE id = $1", pastDue.getId());

        License free = await(licenseService.createLicense(seedCustomer(), Plan.FREE, null, null, null)).license();
        setTermEnd(free.getId(), termEnd);

        // a subscription the customer cancelled is not renewed
        String cancelledSubscription = "sub_" + UUID.randomUUID();
        License cancelled = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, cancelledSubscription, "cs_" + UUID.randomUUID(),
                                                               Instant.now().plus(Duration.ofDays(30)))).license();
        setTermEnd(cancelled.getId(), termEnd);
        Instant periodEnd = Instant.now().plus(Duration.ofDays(12)).truncatedTo(ChronoUnit.SECONDS);
        JsonObject cancelling = StripeStandIn.report(StripeEvents.subscription(cancelledSubscription, "active", Plan.STARTUP.lookupKey(), periodEnd)
                                                                 .put("cancel_at_period_end", true));
        assertThat(deliver(StripeEvents.event("customer.subscription.updated", cancelling)).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(cancelled.getId())).getCancelsAt()).isEqualTo(periodEnd);

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
        assertThat(await(licenseService.keysOf(cancelled.getId()))).hasSize(1);

        // a second run finds the renewed license no longer due
        await(renewalJob.run());
        assertThat(await(licenseService.keysOf(due.getId()))).hasSize(2);
    }

    @Test
    void aPaidCheckoutCreatesTheLicenseWithItsKeyOnce(CapturedOutput output) throws Exception {
        Customer customer = seedCustomer();
        String subscriptionId = "sub_" + UUID.randomUUID();
        String sessionId = "cs_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", Plan.BUSINESS.lookupKey(), periodEnd));
        JsonObject completed = StripeEvents.event("checkout.session.completed",
                                                  StripeEvents.checkoutSession(sessionId, subscriptionId, customer.getId(), "paid"));

        assertThat(deliver(completed).statusCode()).isEqualTo(200);
        License license = await(licenseRepository.findByCheckoutSessionId(sessionId));
        assertThat(license.getCustomerId()).isEqualTo(customer.getId());
        assertThat(license.getPlan()).isEqualTo(Plan.BUSINESS);
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(license.getCurrentPeriodEnd()).isEqualTo(periodEnd);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);

        assertThat(deliver(completed).statusCode()).isEqualTo(200);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void aBankDebitGetsItsFirstKeyWhenThePaymentClears() throws Exception {
        Customer customer = seedCustomer();
        CustomerContact buyer = await(contactRepository.findByCustomerId(customer.getId())).getFirst();
        String subscriptionId = "sub_" + UUID.randomUUID();
        String sessionId = "cs_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        // Checkout completes while the debit is still clearing
        JsonObject clearing = StripeEvents.subscription(subscriptionId, "incomplete", Plan.STARTUP.lookupKey(), periodEnd);
        clearing.getJsonObject("latest_invoice").put("status", "open");
        StripeStandIn.report(clearing);
        assertThat(deliver(StripeEvents.event("checkout.session.completed",
                                              StripeEvents.checkoutSession(sessionId, subscriptionId, customer.getId(), "unpaid"))).statusCode()).isEqualTo(200);
        License awaiting = await(licenseRepository.findByCheckoutSessionId(sessionId));
        assertThat(awaiting.getStatus()).isEqualTo(LicenseStatus.PAST_DUE);
        assertThat(await(licenseService.keysOf(awaiting.getId()))).isEmpty();
        assertThat(checkoutKey(buyer, sessionId).getStatus()).isEqualTo(CheckoutKeyStatus.PENDING);

        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", Plan.STARTUP.lookupKey(), periodEnd));
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);

        License paid = await(licenseRepository.findById(awaiting.getId()));
        assertThat(paid.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(paid.getTermStart()).isAfterOrEqualTo(before);
        List<IssuedKey> keys = await(licenseService.keysOf(awaiting.getId()));
        assertThat(keys).hasSize(1);
        assertThat(keys.getFirst().getExpiresAt()).isEqualTo(paid.getTermEnd().plus(LicenseService.KEY_BUFFER));
        assertThat(checkoutKey(buyer, sessionId).getStatus()).isEqualTo(CheckoutKeyStatus.READY);

        // the next invoice of a license that has its key issues no other
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        assertThat(await(licenseService.keysOf(awaiting.getId()))).hasSize(1);
    }

    @Test
    void aDebitThatClearedBeforeItsCheckoutWasHandledGetsItsKeyAtOnce() throws Exception {
        Customer customer = seedCustomer();
        String subscriptionId = "sub_" + UUID.randomUUID();
        String sessionId = "cs_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        // the invoice.paid came first and found no license; Stripe has the first invoice paid by now
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", Plan.STARTUP.lookupKey(), periodEnd));

        assertThat(deliver(StripeEvents.event("checkout.session.completed",
                                              StripeEvents.checkoutSession(sessionId, subscriptionId, customer.getId(), "unpaid"))).statusCode()).isEqualTo(200);
        License license = await(licenseRepository.findByCheckoutSessionId(sessionId));
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void anEventWhoseDeliveryDiedIsHandledWhenStripeRedeliversIt() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, subscriptionId, "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        JsonObject event = StripeEvents.event("customer.subscription.deleted",
                                              StripeStandIn.report(StripeEvents.subscription(subscriptionId, "canceled", Plan.STARTUP.lookupKey(), Instant.now())));
        // a delivery that claimed the event and stopped before finishing it, long enough ago for its claim to expire
        sql("INSERT INTO stripe_event (id, type, claimed_at) VALUES ($1, $2, now() - interval '10 minutes')",
            event.getString("id"), event.getString("type"));

        assertThat(deliver(event).statusCode()).isEqualTo(200);
        assertThat(await(licenseRepository.findById(license.getId())).getStatus()).isEqualTo(LicenseStatus.CANCELED);
    }

    @Test
    void aRevokedLicenseIsNotRenewed() throws Exception {
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        // the renewal read the license before staff revoked it
        await(licenseService.revoke(license));
        assertThatThrownBy(() -> await(licenseService.issueRenewalKey(license))).hasCauseInstanceOf(IllegalStateException.class);
        License stored = await(licenseRepository.findById(license.getId()));
        assertThat(stored.getStatus()).isEqualTo(LicenseStatus.REVOKED);
        assertThat(stored.getTermEnd()).isEqualTo(license.getTermEnd());
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void aRenewalOfALicenseThatChangedWhileItsKeyWasSignedIsNotStored() throws Exception {
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        // the renewal read the license before a payment failed
        sql("UPDATE license SET status = 'PAST_DUE' WHERE id = $1", license.getId());
        assertThatThrownBy(() -> await(licenseService.issueRenewalKey(license))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(await(licenseRepository.findById(license.getId())).getTermEnd()).isEqualTo(license.getTermEnd());
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void aRevokedLicenseIsIssuedNoReplacementKey() throws Exception {
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        await(licenseService.revoke(license));
        assertThatThrownBy(() -> await(licenseService.issueReplacementKey(license))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void aRenewalWhoseKeyIsNotStoredLeavesTheTermToRenewAgain() throws Exception {
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        // the database refuses this license's next key, as it would on a failure between moving the term and storing it
        String trigger = "refuse_key_" + license.getId().replace("-", "");
        sql("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " IF NEW.license_id = '" + license.getId() + "' THEN RAISE EXCEPTION 'key refused'; END IF; RETURN NEW; END $$");
        sql("CREATE TRIGGER " + trigger + " BEFORE INSERT ON issued_key FOR EACH ROW EXECUTE FUNCTION " + trigger + "()");
        try {
            assertThatThrownBy(() -> await(licenseService.issueRenewalKey(license))).hasMessageContaining("key refused");
        } finally {
            sql("DROP TRIGGER " + trigger + " ON issued_key");
            sql("DROP FUNCTION " + trigger + "()");
        }
        assertThat(await(licenseRepository.findById(license.getId())).getTermEnd()).isEqualTo(license.getTermEnd());
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);

        NewLicense renewed = await(licenseService.issueRenewalKey(license));
        assertThat(renewed.license().getTermStart()).isEqualTo(license.getTermEnd());
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(2);
    }

    @Test
    void aLapsedTermRenewsFromToday() throws Exception {
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                             Instant.now().plus(Duration.ofDays(30)))).license();
        setTermEnd(license.getId(), Instant.now().minus(Duration.ofDays(400)));

        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        await(renewalJob.run());

        License renewed = await(licenseRepository.findById(license.getId()));
        assertThat(renewed.getTermStart()).isAfterOrEqualTo(before);
        assertThat(renewed.getTermEnd()).isEqualTo(LicenseService.termEnd(renewed.getTermStart()));
        // renewed once, into a term that is not due again
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(2);
        await(renewalJob.run());
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(2);
    }

    private CheckoutKey checkoutKey(CustomerContact contact, String sessionId) throws Exception {
        return asCustomer(contact, () -> customerAccount.findCheckoutKey(sessionId));
    }

    private void setTermEnd(String licenseId, Instant termEnd) throws Exception {
        sql("UPDATE license SET term_end = $2 WHERE id = $1", licenseId, OffsetDateTime.ofInstant(termEnd, ZoneOffset.UTC));
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
