package org.kinotic.licenses;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kinotic.client.api.ConnectOptions;
import org.kinotic.client.api.KinoticConnection;
import org.kinotic.client.api.KinoticConnectionFactory;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.services.CustomerAccountService;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicensedClusterRepository;
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
 * A Kinotic cluster renews its key with kinotic-client, as Kinotic will: it connects to the gateway with the key it
 * holds in the {@code license-key} header and calls the renewal service by the contract's namespace, name, zone and
 * version. It may call nothing else, a key whose license ended or whose cluster moved to another license cannot connect, any key
 * of a cluster renews to the cluster's newest, and a cluster beyond the license's seats is told why no newer key comes.
 */
class LicenseRenewalTest extends ServerTest {

    @Autowired
    private KinoticConnectionFactory connectionFactory;
    @Autowired
    private LicenseService licenseService;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private IssuedKeyRepository issuedKeyRepository;
    @Autowired
    private LicensedClusterRepository clusterRepository;

    private final List<KinoticConnection> connections = new ArrayList<>();

    @AfterEach
    void closeConnections() throws Exception {
        for (KinoticConnection connection : connections) {
            await(connection.close());
        }
        connections.clear();
    }

    @Test
    void aClusterRenewsItsKeyAfterAPayment() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(),
                                                                                          periodEnd)));
        LicensedCluster cluster = addCluster(license);
        String first = newestKey(cluster);
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
        assertThat(renewed).isNotEqualTo(first).isEqualTo(newestKey(cluster));
        assertThat(await(licenseService.keysOf(license.getId())).getFirst().getExpiresAt()).isEqualTo(nextPeriodEnd.plus(Duration.ofDays(7)));

        // a license paid further than the cluster's newest key covers, as when the key the payment issued was lost, gets it now
        sql("UPDATE license SET current_period_end = current_period_end + interval '30 days' WHERE id = $1", license.getId());
        String issuedByRenewal = renew(renewal);
        assertThat(issuedByRenewal).isNotEqualTo(renewed).isEqualTo(newestKey(cluster));
        assertThat(renew(renewal)).isEqualTo(issuedByRenewal);
        assertThat(await(clusterRepository.findById(cluster.getId())).getLastRenewedAt()).isNotNull();
    }

    @Test
    void aLicenseeMayCallTheRenewalAlone() throws Exception {
        License license = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        LicensedCluster cluster = addCluster(license);
        KinoticConnection connection = connect(newestKey(cluster));
        CustomerAccountService account = connection.serviceProxy(CustomerAccountService.class).getService();
        assertThatThrownBy(() -> await(account.getAccount())).hasMessageContaining("Not Authorized");
        assertThat(connection.connectedInfo().getParticipant().getId()).isEqualTo(cluster.getId());
    }

    @Test
    void aKeyWhoseLicenseEndedOrThatThisServerNeverIssuedCannotConnect() throws Exception {
        License revoked = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        String key = newestKey(addCluster(revoked));
        await(licenseService.revoke(revoked));
        assertThatThrownBy(() -> connect(key)).hasCauseInstanceOf(AuthenticationException.class);

        // a key whose signature does not match its payload
        License other = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        String[] parts = newestKey(addCluster(other)).split("\\.");
        assertThatThrownBy(() -> connect(parts[0] + "." + parts[1] + "x." + parts[2])).hasCauseInstanceOf(AuthenticationException.class);

        // a key expired more than 30 days ago
        License expired = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        LicensedCluster expiredCluster = addCluster(expired);
        sql("UPDATE license SET term_end = now() - interval '40 days' WHERE id = $1", expired.getId());
        IssuedKey stale = await(licenseService.issueKey(await(licenseRepository.findById(expired.getId())), expiredCluster));
        assertThatThrownBy(() -> connect(stale.getKey())).hasCauseInstanceOf(AuthenticationException.class);
    }

    @Test
    void aKeyOfAClusterThatMovedToAnotherLicenseCannotConnect() throws Exception {
        License license = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        LicensedCluster cluster = addCluster(license);
        String key = newestKey(cluster);
        KinoticLicenseRenewalService open = renewal(connect(key));
        // the license lapses, the cluster is added to another license, and the first license is paid up again
        sql("UPDATE license SET status = 'LAPSED' WHERE id = $1", license.getId());
        License next = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        CustomerContact contact = await(contactRepository.findByCustomerId(next.getCustomerId())).getFirst();
        asCustomer(contact, () -> customerAccount.addCluster(next.getId(), cluster.getClusterId(), "production"));
        sql("UPDATE license SET status = 'ACTIVE' WHERE id = $1", license.getId());

        assertThatThrownBy(() -> connect(key)).hasCauseInstanceOf(AuthenticationException.class);
        // a session opened before the move renews nothing
        assertThatThrownBy(() -> renew(open)).hasMessageContaining("another license");
    }

    @Test
    void anOlderKeyOfAClusterRenewsToItsNewest() throws Exception {
        License license = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        LicensedCluster cluster = addCluster(license);
        String older = newestKey(cluster);
        String newer = await(licenseService.issueKey(license, cluster)).getKey();
        // another cluster's newer key is not this cluster's
        sql("UPDATE license SET seats = 2 WHERE id = $1", license.getId());
        String otherClusters = newestKey(addCluster(license));

        assertThat(renew(renewal(connect(older)))).isEqualTo(newer).isNotEqualTo(otherClusters);
    }

    @Test
    void aClusterBeyondTheSeatsIsToldWhyNoNewerKeyComes() throws Exception {
        String subscriptionId = "sub_" + UUID.randomUUID();
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, subscriptionId, "cs_" + UUID.randomUUID(),
                                                                                          Instant.now().plus(Duration.ofDays(30)))));
        sql("UPDATE license SET seats = 2 WHERE id = $1", license.getId());
        LicensedCluster oldest = addCluster(license);
        LicensedCluster newest = addCluster(license);
        // the customer lowered the subscription's quantity to one seat
        sql("UPDATE license SET seats = 1, current_period_end = current_period_end + interval '30 days' WHERE id = $1", license.getId());

        assertThat(renew(renewal(connect(newestKey(oldest))))).isEqualTo(newestKey(oldest));
        assertThatThrownBy(() -> renew(renewal(connect(newestKey(newest))))).hasMessageContaining("seat");
        assertThat(await(issuedKeyRepository.findLatestByClusterId(newest.getId())).getExpiresAt())
                .isBefore(await(issuedKeyRepository.findLatestByClusterId(oldest.getId())).getExpiresAt());
    }

    @Test
    void aPastDueLicenseGetsItsNewestKeyOrIsToldWhyNoNewerComes() throws Exception {
        License license = await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                                                          Instant.now().plus(Duration.ofDays(30)))));
        LicensedCluster cluster = addCluster(license);
        String older = newestKey(cluster);
        String newest = await(licenseService.issueKey(license, cluster)).getKey();
        sql("UPDATE license SET status = 'PAST_DUE', past_due_since = now() - interval '3 days' WHERE id = $1", license.getId());

        assertThat(renew(renewal(connect(older)))).isEqualTo(newest);
        assertThatThrownBy(() -> renew(renewal(connect(newest)))).hasMessageContaining("past due");
    }

    @Test
    void aTrialRenewsToTheKeyCoveringIt() throws Exception {
        License trial = await(licenseService.createTrial(seedCustomer(), Plan.MEDIUM, StripeStandIn.CURRENT_PRICE_LIST));
        String key = newestKey(addCluster(trial));

        assertThat(renew(renewal(connect(key)))).isEqualTo(key);
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

    private String newestKey(LicensedCluster cluster) throws Exception {
        return await(issuedKeyRepository.findLatestByClusterId(cluster.getId())).getKey();
    }
}
