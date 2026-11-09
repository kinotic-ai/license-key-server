package org.kinotic.licenses;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kinotic.client.api.ConnectOptions;
import org.kinotic.client.api.KinoticConnection;
import org.kinotic.client.api.KinoticConnectionFactory;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseFlag;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.services.CustomerAccountService;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.KinoticLicenseRenewalService;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeEvents;
import org.kinotic.licenses.support.StripeStandIn;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Kinotic installation renews its key with kinotic-client, as Kinotic will: it connects to the gateway with the key
 * it holds in the {@code license-key} header and calls the renewal service by the contract's namespace, name, zone and
 * version. It may call nothing else, a key whose license ended cannot connect, and an older key still renews while
 * flagging the license.
 */
class LicenseRenewalTest extends ServerTest {

    @Autowired
    private KinoticConnectionFactory connectionFactory;
    @Autowired
    private LicenseService licenseService;
    @Autowired
    private LicenseRepository licenseRepository;

    private final List<KinoticConnection> connections = new ArrayList<>();

    @AfterEach
    void closeConnections() throws Exception {
        for (KinoticConnection connection : connections) {
            await(connection.close());
        }
        connections.clear();
    }

    @Test
    void anInstallationRenewsItsKeyAfterAPayment() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(),
                                                                                          periodEnd))).license();
        String first = newestKey(license);
        KinoticLicenseRenewalService renewal = renewal(connect(first));

        // nothing paid since: the same key, however often it is asked
        assertThat(renew(renewal)).isEqualTo(first);
        assertThat(renew(renewal)).isEqualTo(first);

        // a test-mode payment for the next month
        Instant nextPeriodEnd = periodEnd.plus(Duration.ofDays(30));
        StripeStandIn.report(StripeEvents.subscription(subscriptionId, "active", StripeStandIn.monthly(Plan.SMALL), nextPeriodEnd));
        assertThat(deliver(StripeEvents.event("invoice.paid", StripeEvents.invoice(subscriptionId, Instant.now().plusSeconds(1)))).statusCode())
                .isEqualTo(200);
        String renewed = renew(renewal);
        assertThat(renewed).isNotEqualTo(first).isEqualTo(newestKey(license));
        assertThat(await(licenseService.keysOf(license.getId())).getFirst().getExpiresAt()).isEqualTo(nextPeriodEnd.plus(Duration.ofDays(7)));

        // a license paid further than its newest key covers, as when the key the payment issued was lost, gets it now
        sql("UPDATE license SET current_period_end = current_period_end + interval '30 days' WHERE id = $1", license.getId());
        String issuedByRenewal = renew(renewal);
        assertThat(issuedByRenewal).isNotEqualTo(renewed).isEqualTo(newestKey(license));
        assertThat(renew(renewal)).isEqualTo(issuedByRenewal);
    }

    @Test
    void aLicenseeMayCallTheRenewalAlone() throws Exception {
        License license = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST)).license();
        KinoticConnection connection = connect(newestKey(license));
        CustomerAccountService account = connection.serviceProxy(CustomerAccountService.class).getService();
        assertThatThrownBy(() -> await(account.getAccount())).hasMessageContaining("Not Authorized");
        assertThat(connection.connectedInfo().getParticipant().getId()).isEqualTo(license.getId());
    }

    @Test
    void aKeyWhoseLicenseEndedOrThatThisServerNeverIssuedCannotConnect() throws Exception {
        License revoked = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST)).license();
        String key = newestKey(revoked);
        await(licenseService.revoke(revoked));
        assertThatThrownBy(() -> connect(key)).hasCauseInstanceOf(AuthenticationException.class);

        // a key whose signature does not match its payload
        License other = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST)).license();
        String[] parts = newestKey(other).split("\\.");
        assertThatThrownBy(() -> connect(parts[0] + "." + parts[1] + "x." + parts[2])).hasCauseInstanceOf(AuthenticationException.class);

        // a key expired more than 30 days ago
        License expired = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST)).license();
        sql("UPDATE license SET term_end = now() - interval '40 days' WHERE id = $1", expired.getId());
        IssuedKey stale = await(licenseService.issueKey(await(licenseRepository.findById(expired.getId()))));
        assertThatThrownBy(() -> connect(stale.getKey())).hasCauseInstanceOf(AuthenticationException.class);
    }

    @Test
    void anOlderKeyStillRenewsAndFlagsTheLicense() throws Exception {
        License license = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST)).license();
        String older = newestKey(license);
        String newer = await(licenseService.issueKey(license)).getKey();

        assertThat(renew(renewal(connect(older)))).isEqualTo(newer);
        assertThat(await(licenseRepository.findById(license.getId())).getFlags()).contains(LicenseFlag.POSSIBLY_COPIED);
    }

    @Test
    void aPastDueLicenseGetsItsNewestKeyOrIsToldWhyNoNewerComes() throws Exception {
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                                                          Instant.now().plus(Duration.ofDays(30))))).license();
        String older = newestKey(license);
        String newest = await(licenseService.issueKey(license)).getKey();
        sql("UPDATE license SET status = 'PAST_DUE', past_due_since = now() - interval '3 days' WHERE id = $1", license.getId());

        assertThat(renew(renewal(connect(older)))).isEqualTo(newest);
        assertThatThrownBy(() -> renew(renewal(connect(newest)))).hasMessageContaining("past due");
    }

    private KinoticConnection connect(String key) throws Exception {
        ConnectOptions options = new ConnectOptions().setServerUrl("ws://localhost:" + PORT)
                                                     .setAuthHeaders(Map.of("license-key", key));
        options.getReconnect().setMaxAttempts(1);
        KinoticConnection ret = await(connectionFactory.connect(options));
        connections.add(ret);
        return ret;
    }

    private static KinoticLicenseRenewalService renewal(KinoticConnection connection) {
        return connection.serviceProxy(KinoticLicenseRenewalService.class).getService();
    }

    private static String renew(KinoticLicenseRenewalService renewal) throws Exception {
        return renewal.renew().get(30, TimeUnit.SECONDS);
    }

    private String newestKey(License license) throws Exception {
        return await(licenseService.keysOf(license.getId())).getFirst().getKey();
    }
}
