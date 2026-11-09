package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The keys a payment extended a license with whose email has not gone out yet, by key id.
 */
@Repository
@RequiredArgsConstructor
public class UnsentKeyEmailRepository {

    private final Pool pool;

    public Future<Void> add(String jti, String licenseId) {
        return pool.preparedQuery("INSERT INTO unsent_key_email (jti, license_id) VALUES ($1, $2) ON CONFLICT (jti) DO NOTHING")
                   .execute(Tuple.of(jti, licenseId))
                   .mapEmpty();
    }

    /**
     * The ids of the keys still to email, oldest first.
     */
    public Future<List<String>> findAll() {
        return pool.query("SELECT jti FROM unsent_key_email ORDER BY created_at")
                   .execute()
                   .map(rows -> PgValues.map(rows, row -> row.getString("jti")));
    }

    public Future<Void> remove(String jti) {
        return pool.preparedQuery("DELETE FROM unsent_key_email WHERE jti = $1")
                   .execute(Tuple.of(jti))
                   .mapEmpty();
    }
}
