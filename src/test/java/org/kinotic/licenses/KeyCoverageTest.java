package org.kinotic.licenses;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.KeyEmailJob;
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
 * Keys follow the date a license is paid through: each expires seven days after it, a paid invoice that extends it
 * issues and emails the key covering the new date once, a license that is not active gets no key, and the contacts of
 * a license whose newest key runs out with no newer one coming by itself are warned once.
 */
@ExtendWith(OutputCaptureExtension.class)
class KeyCoverageTest extends ServerTest {

    private static final String RENEWED = "Your renewed Kinotic Enterprise license key";
    private static final String EXPIRING = "Your Kinotic Enterprise license key expires soon";

    @Autowired
    private LicenseService licenseService;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private KeyEmailJob keyEmailJob;

    @Test
    void aKeyExpiresSevenDaysAfterTheDateItsLicenseIsPaidThrough() throws Exception {
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        License paid = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, "sub_" + UUID.randomUUID(),
                                                                                       "cs_" + UUID.randomUUID(), periodEnd))).license();
        assertThat(await(licenseService.keysOf(paid.getId())).getFirst().getExpiresAt()).isEqualTo(periodEnd.plus(Duration.ofDays(7)));

        License free = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST)).license();
        assertThat(await(licenseService.keysOf(free.getId())).getFirst().getExpiresAt()).isEqualTo(LicenseService.keyExpiry(free.getTermEnd()));
    }

    @Test
    void aPaymentThatExtendsTheLicenseIssuesTheKeyCoveringItOnce(CapturedOutput output) throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant firstPeriodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(),
                                                                                          firstPeriodEnd))).license();

        // the next month's invoice is paid, and Stripe reports the period it paid for
        Instant nextPeriodEnd = firstPeriodEnd.plus(Duration.ofDays(30));
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), nextPeriodEnd));
        JsonObject paid = StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now().plusSeconds(1)));
        int renewedEmails = count(output.getAll(), RENEWED);
        assertThat(deliver(paid).statusCode()).isEqualTo(200);

        List<IssuedKey> keys = await(licenseService.keysOf(license.getId()));
        assertThat(keys).hasSize(2);
        assertThat(keys.getFirst().getExpiresAt()).isEqualTo(nextPeriodEnd.plus(Duration.ofDays(7)));
        assertThat(count(output.getAll(), RENEWED)).isEqualTo(renewedEmails + 1);

        // a redelivery, and another event about the same period, issue nothing more
        assertThat(deliver(paid).statusCode()).isEqualTo(200);
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now()))).statusCode()).isEqualTo(200);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(2);
    }

    @Test
    void aLicenseThatIsNotActiveGetsNoKey() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(),
                                                                                          periodEnd))).license();
        for (String status : List.of("PAST_DUE", "LAPSED", "CANCELED", "REVOKED")) {
            sql("UPDATE license SET status = $2 WHERE id = $1", license.getId(), status);
            License stored = await(licenseRepository.findById(license.getId()));
            assertThatThrownBy(() -> await(licenseService.issueKey(stored))).as(status).hasCauseInstanceOf(IllegalStateException.class);
        }
        // a payment failure Stripe reports with a later period issues nothing either
        sql("UPDATE license SET status = 'ACTIVE' WHERE id = $1", license.getId());
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "past_due", StripeStandIn.monthly(Plan.SMALL), periodEnd.plus(Duration.ofDays(30))));
        assertThat(deliver(StripeEvents.event("invoice.payment_failed", StripeEvents.invoice(subscriptionId, null))).statusCode()).isEqualTo(200);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void theContactsOfAKeyRunningOutWithNoNewerOneComingAreWarnedOnce(CapturedOutput output) throws Exception {
        // a free license whose year ends in ten days, which staff renew only once its figures are confirmed
        License free = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST)).license();
        sql("UPDATE issued_key SET expires_at = now() + interval '10 days' WHERE license_id = $1", free.getId());
        // a monthly subscription renewing as usual, whose next invoice issues its next key
        Instant periodEnd = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS);
        License renewing = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, "sub_" + UUID.randomUUID(),
                                                                                           "cs_" + UUID.randomUUID(), periodEnd))).license();
        String freeKey = await(licenseService.keysOf(free.getId())).getFirst().getJti();

        int before = count(output.getAll(), EXPIRING);
        await(keyEmailJob.run());
        assertThat(count(output.getAll(), EXPIRING)).isGreaterThan(before);
        assertThat(output.getAll()).contains("free license's year is ending");
        int afterFirst = count(output.getAll(), EXPIRING);

        await(keyEmailJob.run());
        assertThat(count(output.getAll(), EXPIRING)).isEqualTo(afterFirst);
        assertThat(await(licenseService.keysOf(renewing.getId()))).hasSize(1);
        assertThat(freeKey).isNotNull();
    }

    private static int count(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
