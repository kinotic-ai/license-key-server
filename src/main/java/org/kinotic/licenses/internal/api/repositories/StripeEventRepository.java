package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * The ids of the Stripe events already processed, so a redelivered event is handled once.
 */
@Repository
@RequiredArgsConstructor
public class StripeEventRepository {

    private final Pool pool;

    /**
     * Records the event as processed.
     *
     * @return {@code true} when the event was new, {@code false} when it had already been recorded
     */
    public Future<Boolean> recordIfNew(String eventId, String type) {
        return pool.preparedQuery("INSERT INTO stripe_event (id, type) VALUES ($1, $2) ON CONFLICT (id) DO NOTHING")
                   .execute(Tuple.of(eventId, type))
                   .map(rows -> rows.rowCount() == 1);
    }

    /**
     * Forgets the event, so a delivery whose handling failed is handled again when Stripe redelivers it.
     */
    public Future<Void> forget(String eventId) {
        return pool.preparedQuery("DELETE FROM stripe_event WHERE id = $1")
                   .execute(Tuple.of(eventId))
                   .mapEmpty();
    }
}
