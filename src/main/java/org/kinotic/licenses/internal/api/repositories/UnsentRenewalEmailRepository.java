package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The licenses a payment extended whose renewal email has not gone out yet, by license id.
 */
@Repository
@RequiredArgsConstructor
public class UnsentRenewalEmailRepository {

    private final Pool pool;

    public Future<Void> add(String licenseId) {
        return pool.preparedQuery("INSERT INTO unsent_renewal_email (license_id) VALUES ($1) ON CONFLICT (license_id) DO NOTHING")
                   .execute(Tuple.of(licenseId))
                   .mapEmpty();
    }

    /**
     * The ids of the licenses whose renewal email is still to send, oldest first.
     */
    public Future<List<String>> findAll() {
        return pool.query("SELECT license_id FROM unsent_renewal_email ORDER BY created_at")
                   .execute()
                   .map(rows -> PgValues.map(rows, row -> row.getString("license_id")));
    }

    public Future<Void> remove(String licenseId) {
        return pool.preparedQuery("DELETE FROM unsent_renewal_email WHERE license_id = $1")
                   .execute(Tuple.of(licenseId))
                   .mapEmpty();
    }
}
