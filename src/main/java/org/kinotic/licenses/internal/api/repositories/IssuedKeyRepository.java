package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.LicensePurpose;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The append-only log of every key issued.
 */
@Repository
@RequiredArgsConstructor
public class IssuedKeyRepository {

    private static final String COLUMNS = "jti, license_id, purpose, key, issued_at, expires_at";

    private final Pool pool;

    public Future<IssuedKey> append(IssuedKey key) {
        return pool.preparedQuery("INSERT INTO issued_key (jti, license_id, purpose, key, issued_at, expires_at)"
                                          + " VALUES ($1, $2, $3, $4, $5, $6) RETURNING " + COLUMNS)
                   .execute(Tuple.of(key.getJti(), key.getLicenseId(), PgValues.name(key.getPurpose()), key.getKey(),
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

    public Future<IssuedKey> findByJti(String jti) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM issued_key WHERE jti = $1")
                   .execute(Tuple.of(jti))
                   .map(rows -> PgValues.first(rows, IssuedKeyRepository::toKey));
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
                .setPurpose(PgValues.enumValue(row, "purpose", LicensePurpose.class))
                .setKey(row.getString("key"))
                .setIssuedAt(PgValues.instant(row, "issued_at"))
                .setExpiresAt(PgValues.instant(row, "expires_at"));
    }
}
