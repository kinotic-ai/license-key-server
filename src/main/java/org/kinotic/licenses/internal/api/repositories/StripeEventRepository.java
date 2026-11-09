package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * The Stripe events this server has handled or is handling, so a redelivered event is handled once.
 */
@Repository
@RequiredArgsConstructor
public class StripeEventRepository {

    /** How long a delivery may hold an event before another delivery may take it over. */
    private static final String CLAIM_LIFETIME = "5 minutes";

    private final Pool pool;

    /**
     * Claims the event for the calling delivery: a new event, or one whose earlier claim expired without it being
     * processed, as when the server stopped while handling it.
     *
     * @return {@code true} when the caller holds the claim and should handle the event, {@code false} when the event
     *         was processed already or another delivery is handling it
     */
    public Future<Boolean> claim(String eventId, String type) {
        return pool.preparedQuery("INSERT INTO stripe_event (id, type) VALUES ($1, $2)"
                                          + " ON CONFLICT (id) DO UPDATE SET claimed_at = now()"
                                          + " WHERE stripe_event.processed_at IS NULL"
                                          + " AND stripe_event.claimed_at < now() - interval '" + CLAIM_LIFETIME + "'")
                   .execute(Tuple.of(eventId, type))
                   .map(rows -> rows.rowCount() == 1);
    }

    /**
     * Marks a claimed event processed, so every later delivery of it is acknowledged without being handled.
     */
    public Future<Void> markProcessed(String eventId) {
        return pool.preparedQuery("UPDATE stripe_event SET processed_at = now() WHERE id = $1")
                   .execute(Tuple.of(eventId))
                   .mapEmpty();
    }

    /**
     * Releases a claim whose handling failed, so Stripe's redelivery handles the event afresh.
     */
    public Future<Void> release(String eventId) {
        return pool.preparedQuery("DELETE FROM stripe_event WHERE id = $1 AND processed_at IS NULL")
                   .execute(Tuple.of(eventId))
                   .mapEmpty();
    }
}
