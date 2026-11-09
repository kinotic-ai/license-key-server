package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.IssuedKey;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * The append-only log of every key issued.
 */
@Repository
@RequiredArgsConstructor
public class IssuedKeyRepository {

    private static final String COLUMNS = "jti, license_id, key, issued_at, expires_at";

    private final Pool pool;

    /**
     * Stores a key, provided its license is active or a trial.
     *
     * @return the key as stored, or {@code null} when its license is neither
     */
    public Future<IssuedKey> append(IssuedKey key) {
        // the status check and the insert are one statement, so a revocation or lapse committed first always wins
        return pool.preparedQuery("INSERT INTO issued_key (jti, license_id, key, issued_at, expires_at)"
                                          + " SELECT $1, $2, $3, $4, $5"
                                          + " WHERE EXISTS (SELECT 1 FROM license WHERE id = $2 AND status IN ('ACTIVE', 'TRIAL'))"
                                          + " RETURNING " + COLUMNS)
                   .execute(Tuple.of(key.getJti(), key.getLicenseId(), key.getKey(),
                                     PgValues.timestamp(key.getIssuedAt()), PgValues.timestamp(key.getExpiresAt())))
                   .map(rows -> PgValues.first(rows, IssuedKeyRepository::toKey));
    }

    /**
     * The keys issued for a license, newest first.
     */
    public Future<List<IssuedKey>> findByLicenseId(String licenseId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM issued_key WHERE license_id = $1 ORDER BY issued_at DESC")
                   .execute(Tuple.of(licenseId))
                   .map(rows -> PgValues.map(rows, IssuedKeyRepository::toKey));
    }

    /**
     * The key with the id, or {@code null} when none has it.
     */
    public Future<IssuedKey> findByJti(String jti) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM issued_key WHERE jti = $1")
                   .execute(Tuple.of(jti))
                   .map(rows -> PgValues.first(rows, IssuedKeyRepository::toKey));
    }

    /**
     * The newest key of each license that expires before {@code expiresBefore} and after now, with no warning sent for
     * it, whose license will not replace it by itself: past due, set to end, a trial, or free, whose next year is
     * approved by staff.
     */
    public Future<List<IssuedKey>> findExpiringUnwarned(Instant expiresBefore) {
        return pool.preparedQuery("SELECT k.jti, k.license_id, k.key, k.issued_at, k.expires_at FROM license l"
                                          + " JOIN LATERAL (SELECT * FROM issued_key WHERE license_id = l.id ORDER BY issued_at DESC LIMIT 1) k ON true"
                                          + " WHERE k.expires_at > now() AND k.expires_at < $1"
                                          + " AND NOT EXISTS (SELECT 1 FROM key_expiry_warning w WHERE w.jti = k.jti)"
                                          + " AND (l.status IN ('PAST_DUE', 'TRIAL') OR (l.status = 'ACTIVE' AND (l.plan = 'FREE' OR l.cancels_at IS NOT NULL)))"
                                          + " ORDER BY k.expires_at")
                   .execute(Tuple.of(PgValues.timestamp(expiresBefore)))
                   .map(rows -> PgValues.map(rows, IssuedKeyRepository::toKey));
    }

    /**
     * Records that the key's contacts were warned it expires, so it is warned once.
     */
    public Future<Void> markWarned(String jti) {
        return pool.preparedQuery("INSERT INTO key_expiry_warning (jti) VALUES ($1) ON CONFLICT (jti) DO NOTHING")
                   .execute(Tuple.of(jti))
                   .mapEmpty();
    }

    public Future<IssuedKey> findLatestByLicenseId(String licenseId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM issued_key WHERE license_id = $1 ORDER BY issued_at DESC LIMIT 1")
                   .execute(Tuple.of(licenseId))
                   .map(rows -> PgValues.first(rows, IssuedKeyRepository::toKey));
    }

    private static IssuedKey toKey(Row row) {
        return new IssuedKey()
                .setJti(row.getString("jti"))
                .setLicenseId(row.getString("license_id"))
                .setKey(row.getString("key"))
                .setIssuedAt(PgValues.instant(row, "issued_at"))
                .setExpiresAt(PgValues.instant(row, "expires_at"));
    }
}
