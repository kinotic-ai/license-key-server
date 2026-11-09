package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.internal.api.model.SignInCode;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Stores the hashes of the one-time sign-in codes.
 */
@Repository
@RequiredArgsConstructor
public class SignInCodeRepository {

    private static final String COLUMNS = "id, email, code_hash, created_at, expires_at, used_at";

    private final Pool pool;

    public Future<Void> save(SignInCode code) {
        return pool.preparedQuery("INSERT INTO sign_in_code (id, email, code_hash, expires_at) VALUES ($1, $2, $3, $4)")
                   .execute(Tuple.of(code.getId(), code.getEmail(), code.getCodeHash(), PgValues.timestamp(code.getExpiresAt())))
                   .mapEmpty();
    }

    /**
     * The codes issued to the address that are neither used nor expired at {@code now}.
     */
    public Future<List<SignInCode>> findUsable(String email, Instant now) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM sign_in_code WHERE lower(email) = lower($1) AND used_at IS NULL AND expires_at > $2")
                   .execute(Tuple.of(email, PgValues.timestamp(now)))
                   .map(rows -> PgValues.map(rows, SignInCodeRepository::toCode));
    }

    /**
     * Marks the code used, which only succeeds for a code not yet used.
     *
     * @return {@code true} when this call used the code
     */
    public Future<Boolean> markUsed(String id, Instant usedAt) {
        return pool.preparedQuery("UPDATE sign_in_code SET used_at = $2 WHERE id = $1 AND used_at IS NULL")
                   .execute(Tuple.of(id, PgValues.timestamp(usedAt)))
                   .map(rows -> rows.rowCount() == 1);
    }

    private static SignInCode toCode(Row row) {
        return new SignInCode()
                .setId(row.getString("id"))
                .setEmail(row.getString("email"))
                .setCodeHash(row.getString("code_hash"))
                .setCreatedAt(PgValues.instant(row, "created_at"))
                .setExpiresAt(PgValues.instant(row, "expires_at"))
                .setUsedAt(PgValues.instant(row, "used_at"));
    }
}
