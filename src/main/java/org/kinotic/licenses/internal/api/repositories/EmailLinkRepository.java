package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.internal.api.model.EmailLink;
import org.kinotic.licenses.internal.api.model.EmailLinkType;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Stores the hashes of the one-time email links' tokens.
 */
@Repository
@RequiredArgsConstructor
public class EmailLinkRepository {

    private static final String COLUMNS = "id, type, email, token_hash, created_at, expires_at, used_at";

    private final Pool pool;

    public Future<Void> save(EmailLink link) {
        return pool.preparedQuery("INSERT INTO email_link (id, type, email, token_hash, expires_at) VALUES ($1, $2, $3, $4, $5)")
                   .execute(Tuple.of(link.getId(), PgValues.name(link.getType()), link.getEmail(), link.getTokenHash(),
                                     PgValues.timestamp(link.getExpiresAt())))
                   .mapEmpty();
    }

    /**
     * Marks the link of the type with the token hash used, which only succeeds for a link neither used nor expired at
     * {@code now}.
     *
     * @return the link this call used, or {@code null} when there is no usable one
     */
    public Future<EmailLink> use(EmailLinkType type, String tokenHash, Instant now) {
        return pool.preparedQuery("UPDATE email_link SET used_at = $3 WHERE type = $1 AND token_hash = $2 AND used_at IS NULL"
                                          + " AND expires_at > $3 RETURNING " + COLUMNS)
                   .execute(Tuple.of(PgValues.name(type), tokenHash, PgValues.timestamp(now)))
                   .map(rows -> PgValues.first(rows, EmailLinkRepository::toLink));
    }

    private static EmailLink toLink(Row row) {
        return new EmailLink()
                .setId(row.getString("id"))
                .setType(PgValues.enumValue(row, "type", EmailLinkType.class))
                .setEmail(row.getString("email"))
                .setTokenHash(row.getString("token_hash"))
                .setCreatedAt(PgValues.instant(row, "created_at"))
                .setExpiresAt(PgValues.instant(row, "expires_at"))
                .setUsedAt(PgValues.instant(row, "used_at"));
    }
}
