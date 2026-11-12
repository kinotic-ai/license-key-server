package org.kinotic.licenses;

import org.junit.jupiter.api.Test;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseKeyClaims;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.internal.api.repositories.LicensedClusterRepository;
import org.kinotic.licenses.internal.api.services.LicenseKeyIssuer;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeStandIn;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A customer adds a cluster to a license from its account by the id the cluster's system console shows, and the
 * cluster gets a key bound to it; a license holds as many clusters as it has seats, a cluster holds one seat at a time,
 * and releasing a cluster frees its seat.
 */
class LicensedClusterTest extends ServerTest {

    @Autowired
    private LicenseService licenseService;
    @Autowired
    private LicenseKeyIssuer keyIssuer;
    @Autowired
    private LicensedClusterRepository licensedClusterRepository;

    @Test
    void anAddedClusterGetsAKeyBoundToIt() throws Exception {
        License license = paidLicense();
        String clusterId = UUID.randomUUID().toString();

        LicensedCluster cluster = add(license, "  " + clusterId.toUpperCase() + " ", " production ");

        assertThat(cluster.getClusterId()).isEqualTo(clusterId);
        assertThat(cluster.getName()).isEqualTo("production");
        assertThat(cluster.getAddedAt()).isNotNull();
        List<IssuedKey> keys = await(licenseService.keysOf(license.getId()));
        assertThat(keys).singleElement().satisfies(key -> {
            assertThat(key.getLicensedClusterId()).isEqualTo(cluster.getId());
            assertThat(key.getExpiresAt()).isEqualTo(LicenseService.keyExpiry(license));
        });
        LicenseKeyClaims claims = keyIssuer.verify(keys.getFirst().getKey());
        assertThat(claims.clusterId()).isEqualTo(clusterId);
    }

    @Test
    void aLicenseHoldsAsManyClustersAsItHasSeats() throws Exception {
        License license = paidLicense();
        sql("UPDATE license SET seats = 2 WHERE id = $1", license.getId());
        LicensedCluster first = addCluster(license);
        addCluster(license);

        assertThatThrownBy(() -> addCluster(license)).hasCauseInstanceOf(IllegalStateException.class).hasMessageContaining("seat");

        LicensedCluster released = asCustomer(contactOf(license), () -> customerAccount.releaseCluster(license.getId(), first.getId()));
        assertThat(released.getReleasedAt()).isNotNull();
        addCluster(license);
        // a cluster released already is not released again
        assertThatThrownBy(() -> asCustomer(contactOf(license), () -> customerAccount.releaseCluster(license.getId(), first.getId())))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoClustersAddedTogetherTakeNoMoreSeatsThanTheLicenseHas() throws Exception {
        License license = paidLicense();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<CompletableFuture<LicensedCluster>> adds = List.of(addAsync(license, go, executor), addAsync(license, go, executor));
            go.countDown();
            long added = adds.stream().filter(add -> add.handle((cluster, t) -> t == null).join()).count();

            assertThat(added).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aClusterHoldsOneSeatAtATime() throws Exception {
        License first = paidLicense();
        License second = paidLicense();
        String clusterId = UUID.randomUUID().toString();
        LicensedCluster cluster = add(first, clusterId, "production");

        assertThatThrownBy(() -> add(second, clusterId, "production")).hasCauseInstanceOf(AlreadyExistsException.class);

        asCustomer(contactOf(first), () -> customerAccount.releaseCluster(first.getId(), cluster.getId()));
        assertThat(add(second, clusterId, "production").getLicenseId()).isEqualTo(second.getId());
    }

    @Test
    void aLicenseThatEndedGivesUpItsClusters() throws Exception {
        License ended = paidLicense();
        String clusterId = UUID.randomUUID().toString();
        add(ended, clusterId, "production");
        await(licenseService.revoke(ended));

        License next = paidLicense();
        assertThat(add(next, clusterId, "production").getLicenseId()).isEqualTo(next.getId());
        assertThat(await(licensedClusterRepository.findByLicenseId(ended.getId()))).singleElement()
                .satisfies(cluster -> assertThat(cluster.getReleasedAt()).isNotNull());
    }

    @Test
    void aCustomerAddsAndReleasesClustersOfItsOwnLicensesAlone() throws Exception {
        License license = paidLicense();
        LicensedCluster cluster = addCluster(license);
        CustomerContact other = await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();

        assertThatThrownBy(() -> asCustomer(other, () -> customerAccount.addCluster(license.getId(), UUID.randomUUID().toString(), "theirs")))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> asCustomer(other, () -> customerAccount.releaseCluster(license.getId(), cluster.getId())))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
    }

    @Test
    void aClusterNeedsTheIdItsConsoleShowsAndAName() throws Exception {
        License license = paidLicense();

        assertThatThrownBy(() -> add(license, "kube-system", "production")).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> add(license, UUID.randomUUID().toString(), " ")).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> add(license, UUID.randomUUID().toString(), "x".repeat(101))).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(await(licenseService.keysOf(license.getId()))).isEmpty();
    }

    @Test
    void aLicenseThatIsNotActiveTakesNoCluster() throws Exception {
        License license = await(licenseService.createFreeLicense(seedCustomer(), StripeStandIn.CURRENT_PRICE_LIST, null));
        await(licenseService.revoke(license));

        assertThatThrownBy(() -> addCluster(license)).hasCauseInstanceOf(IllegalStateException.class);
    }

    private License paidLicense() throws Exception {
        return await(licenseService.createLicense(seedCustomer(), subscription(Plan.SMALL, "sub_" + UUID.randomUUID(), "cs_" + UUID.randomUUID(),
                                                                               Instant.now().plus(Duration.ofDays(30)))));
    }

    private LicensedCluster add(License license, String clusterId, String name) throws Exception {
        return asCustomer(contactOf(license), () -> customerAccount.addCluster(license.getId(), clusterId, name));
    }

    // an add on its own thread that waits for the latch, so two of them begin together
    private CompletableFuture<LicensedCluster> addAsync(License license, CountDownLatch go, ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                go.await();
                return addCluster(license);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private CustomerContact contactOf(License license) throws Exception {
        return await(contactRepository.findByCustomerId(license.getCustomerId())).getFirst();
    }
}
