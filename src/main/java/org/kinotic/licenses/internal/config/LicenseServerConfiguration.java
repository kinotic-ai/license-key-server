package org.kinotic.licenses.internal.config;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.discovery.DiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.flywaydb.core.Flyway;
import org.kinotic.core.api.event.ZonePartitioningService;
import org.kinotic.core.api.security.Participant;
import org.kinotic.licenses.api.config.DatabaseProperties;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.security.LicenseZones;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;

import java.util.List;
import java.util.Set;

/**
 * Wires the server's infrastructure: the zones it serves, an Ignite node confined to this host, the Postgres
 * pool behind a migrated schema, and the Jackson binding of this server's participant.
 */
@Slf4j
@Configuration
public class LicenseServerConfiguration {

    private static final String SERVER_NAME = "licenses";
    private static final String LOOPBACK = "127.0.0.1";

    /**
     * This server hosts and reaches the staff and customer zones and nothing else, and names itself to the cluster
     * and the session cookie as {@code licenses}.
     */
    @Bean
    public ZonePartitioningService zonePartitioningService() {
        Set<String> zones = Set.of(LicenseZones.STAFF, LicenseZones.CUSTOMER);
        return ZonePartitioningService.of(SERVER_NAME, zones, zones);
    }

    /**
     * Discovery that can only ever find this node: bound to the loopback interface on a port of its own, with no
     * port range to scan and itself as the only address. A Kinotic OS node cannot reach a loopback socket, and this
     * node never looks anywhere else, so the two clusters cannot merge.
     */
    @Bean
    public DiscoverySpi loopbackDiscoverySpi(KinoticLicensesProperties properties) {
        int port = properties.getIgnite().getDiscoveryPort();
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        ipFinder.setAddresses(List.of(LOOPBACK + ":" + port));
        TcpDiscoverySpi ret = new TcpDiscoverySpi();
        ret.setIpFinder(ipFinder);
        ret.setLocalAddress(LOOPBACK);
        ret.setLocalPort(port);
        ret.setLocalPortRange(0);
        ret.setJoinTimeout(properties.getIgnite().getJoinTimeoutMs());
        return ret;
    }

    /**
     * Node communication bound to the loopback interface, as discovery is.
     */
    @Bean
    public TcpCommunicationSpi loopbackCommunicationSpi(KinoticLicensesProperties properties) {
        TcpCommunicationSpi ret = new TcpCommunicationSpi();
        ret.setLocalAddress(LOOPBACK);
        ret.setLocalPort(properties.getIgnite().getCommunicationPort());
        return ret;
    }

    /**
     * The Postgres connection pool, built once Flyway has brought the schema to the current version.
     */
    @Bean(destroyMethod = "close")
    public Pool pgPool(Vertx vertx, KinoticLicensesProperties properties) {
        DatabaseProperties database = properties.getLicenses().getDatabase();
        migrate(database);
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(database.getHost())
                .setPort(database.getPort())
                .setDatabase(database.getName())
                .setUser(database.getUser())
                .setPassword(database.getPassword());
        return PgBuilder.pool()
                        .with(new PoolOptions().setMaxSize(database.getMaxPoolSize()))
                        .connectingTo(connectOptions)
                        .using(vertx)
                        .build();
    }

    /**
     * Binds {@link Participant} to this server's one implementation, so the gateway's session store and the event
     * bus marshal participants without a type discriminator.
     */
    @Bean
    public SimpleModule licenseParticipantModule() {
        SimpleModule ret = new SimpleModule("LicenseParticipantModule");
        ret.addAbstractTypeMapping(Participant.class, LicenseParticipant.class);
        return ret;
    }

    private static void migrate(DatabaseProperties database) {
        log.info("Migrating the database at {}", database.jdbcUrl());
        Flyway.configure()
              .dataSource(database.jdbcUrl(), database.getUser(), database.getPassword())
              .locations("classpath:db/migration")
              .load()
              .migrate();
    }
}
