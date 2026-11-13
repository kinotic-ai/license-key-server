package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Stores the clusters of each license. A license's seated clusters are its oldest unreleased ones, as many as it has
 * seats: a license whose seats drop below its clusters keeps renewing the keys of the oldest.
 */
@Repository
@RequiredArgsConstructor
public class LicensedClusterRepository {

    /** Why a cluster that holds a seat already cannot be added again. */
    public static final String CLUSTER_TAKEN = "That cluster holds a seat of another license already";

    private static final String COLUMNS = "id, license_id, cluster_id, name, added_at, released_at, last_renewed_at";
    private static final String UNIQUE_VIOLATION = "23505";

    private final Pool pool;

    /**
     * Adds a cluster to a license that is active or a trial and has an open seat; two clusters added at once never take
     * more seats than it has. A license that has ended (expired, lapsed, canceled or revoked) gives up the cluster.
     *
     * @return the cluster as stored; a failure with {@link IllegalStateException} when the license is not active or a
     *         trial, or every seat is taken, and with {@link AlreadyExistsException} when the cluster holds a seat of
     *         another license that has not ended
     */
    public Future<LicensedCluster> add(LicensedCluster cluster) {
        // the license row is locked while its seats are counted
        return pool.withTransaction(connection -> connection
                .preparedQuery("SELECT seats FROM license WHERE id = $1 AND status IN ('ACTIVE', 'TRIAL') FOR UPDATE")
                .execute(Tuple.of(cluster.getLicenseId()))
                .compose(licenses -> {
                    if (licenses.size() == 0) {
                        throw new IllegalStateException("Clusters are added to a license that is active or a trial");
                    }
                    int seats = licenses.iterator().next().getInteger("seats");
                    return connection.preparedQuery("SELECT count(*) AS taken FROM licensed_cluster WHERE license_id = $1 AND released_at IS NULL")
                                     .execute(Tuple.of(cluster.getLicenseId()))
                                     .map(rows -> {
                                         long taken = rows.iterator().next().getLong("taken");
                                         if (taken >= seats) {
                                             throw new IllegalStateException("Every seat of the license is taken (" + seats
                                                     + "); add seats to your subscription");
                                         }
                                         return seats;
                                     });
                })
                .compose(seats -> connection.preparedQuery("UPDATE licensed_cluster c SET released_at = now() FROM license l"
                                                                   + " WHERE c.license_id = l.id AND c.cluster_id = $1 AND c.released_at IS NULL"
                                                                   + " AND l.status IN ('EXPIRED', 'LAPSED', 'CANCELED', 'REVOKED')")
                                            .execute(Tuple.of(cluster.getClusterId())))
                .compose(released -> connection.preparedQuery("INSERT INTO licensed_cluster (id, license_id, cluster_id, name)"
                                                                   + " VALUES ($1, $2, $3, $4) RETURNING " + COLUMNS)
                                            .execute(Tuple.of(cluster.getId(), cluster.getLicenseId(), cluster.getClusterId(), cluster.getName())))
                .map(rows -> PgValues.first(rows, LicensedClusterRepository::toCluster)))
                   .recover(t -> Future.failedFuture(t instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState())
                                                             ? new AlreadyExistsException(CLUSTER_TAKEN, t)
                                                             : t));
    }

    /**
     * Removes a cluster that was never issued a key, freeing its seat.
     */
    public Future<Void> remove(String id) {
        return pool.preparedQuery("DELETE FROM licensed_cluster WHERE id = $1")
                   .execute(Tuple.of(id))
                   .mapEmpty();
    }

    /**
     * Records that the cluster renewed its key.
     */
    public Future<Void> markRenewed(String id) {
        return pool.preparedQuery("UPDATE licensed_cluster SET last_renewed_at = now() WHERE id = $1")
                   .execute(Tuple.of(id))
                   .mapEmpty();
    }

    /**
     * The cluster with the id, or {@code null} when none has it.
     */
    public Future<LicensedCluster> findById(String id) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM licensed_cluster WHERE id = $1")
                   .execute(Tuple.of(id))
                   .map(rows -> PgValues.first(rows, LicensedClusterRepository::toCluster));
    }

    /**
     * Every cluster that holds or held a seat of the license, oldest first.
     */
    public Future<List<LicensedCluster>> findByLicenseId(String licenseId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM licensed_cluster WHERE license_id = $1 ORDER BY added_at, id")
                   .execute(Tuple.of(licenseId))
                   .map(rows -> PgValues.map(rows, LicensedClusterRepository::toCluster));
    }

    /**
     * The license's seated clusters: its oldest unreleased ones, as many as it has seats, oldest first.
     */
    public Future<List<LicensedCluster>> findSeated(String licenseId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM licensed_cluster WHERE license_id = $1 AND released_at IS NULL"
                                          + " ORDER BY added_at, id LIMIT (SELECT seats FROM license WHERE id = $1)")
                   .execute(Tuple.of(licenseId))
                   .map(rows -> PgValues.map(rows, LicensedClusterRepository::toCluster));
    }

    private static LicensedCluster toCluster(Row row) {
        return new LicensedCluster()
                .setId(row.getString("id"))
                .setLicenseId(row.getString("license_id"))
                .setClusterId(row.getString("cluster_id"))
                .setName(row.getString("name"))
                .setAddedAt(PgValues.instant(row, "added_at"))
                .setReleasedAt(PgValues.instant(row, "released_at"))
                .setLastRenewedAt(PgValues.instant(row, "last_renewed_at"));
    }
}
