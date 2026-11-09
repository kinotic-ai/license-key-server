package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Stores licenses. Every change writes only the columns it is about, in one statement, so changes made at the same
 * time by different callers never overwrite each other, and a revoked license, or one whose subscription ended, stays so
 * whatever Stripe reports later.
 */
@Repository
@RequiredArgsConstructor
public class LicenseRepository {

    private static final String COLUMNS = "id, customer_id, plan, status, billing_period, price_list, terms_version, stripe_subscription_id,"
            + " checkout_session_id, current_period_end, cancels_at, past_due_since, term_start, term_end, last_payment_at, created_at";
    // the statuses a billing change never moves a license out of
    private static final String ENDED = "('REVOKED', 'CANCELED', 'LAPSED')";
    // The status a billing change sets, unless staff revoked the license or its subscription ended; Stripe never
    // reopens a canceled subscription, so a later write is an event delivered out of order. A license entering
    // PAST_DUE records when, and keeps that time through further failures until it leaves PAST_DUE.
    private static final String STATUS_UNLESS_ENDED = "status = CASE WHEN status IN " + ENDED + " THEN status ELSE %1$s END,"
            + " past_due_since = CASE WHEN status IN " + ENDED + " THEN past_due_since"
            + " WHEN %1$s = 'PAST_DUE' THEN COALESCE(past_due_since, now()) END";

    private final Pool pool;

    public Future<License> save(License license) {
        return pool.preparedQuery("INSERT INTO license (id, customer_id, plan, status, billing_period, price_list, terms_version,"
                                          + " stripe_subscription_id, checkout_session_id, current_period_end, term_start, term_end,"
                                          + " last_payment_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13) RETURNING " + COLUMNS)
                   .execute(Tuple.from(Arrays.asList(license.getId(), license.getCustomerId(), PgValues.name(license.getPlan()),
                                                     PgValues.name(license.getStatus()), PgValues.name(license.getBillingPeriod()),
                                                     license.getPriceList(), license.getTermsVersion(),
                                                     license.getStripeSubscriptionId(), license.getCheckoutSessionId(),
                                                     PgValues.timestamp(license.getCurrentPeriodEnd()), PgValues.timestamp(license.getTermStart()),
                                                     PgValues.timestamp(license.getTermEnd()), PgValues.timestamp(license.getLastPaymentAt()))))
                   .map(rows -> PgValues.first(rows, LicenseRepository::toLicense));
    }

    /**
     * Revokes the license.
     *
     * @return the license, or {@code null} when none has the id
     */
    public Future<License> revoke(String id) {
        return updateOne("status = 'REVOKED'", id);
    }

    /**
     * Records a payment, which is the license's last payment unless a later one is recorded already.
     *
     * @return the license as stored, or {@code null} when none has the id
     */
    public Future<License> recordPayment(String id, Instant paidAt) {
        return updateOne("last_payment_at = GREATEST(last_payment_at, $2)", id, PgValues.timestamp(paidAt));
    }

    /**
     * Writes what Stripe reports about the subscription: its plan and billing period, the end of the billing period,
     * when it is set to end, and its status, unless the license is revoked, lapsed or canceled.
     *
     * @return the license as stored, or {@code null} when none has the id
     */
    public Future<License> syncSubscription(String id, Plan plan, BillingPeriod billingPeriod, LicenseStatus status,
                                            Instant currentPeriodEnd, Instant cancelsAt) {
        return updateOne("plan = $2, billing_period = $3, current_period_end = $4, cancels_at = $5, "
                                 + STATUS_UNLESS_ENDED.formatted("$6::text"),
                         id, PgValues.name(plan), PgValues.name(billingPeriod), PgValues.timestamp(currentPeriodEnd),
                         PgValues.timestamp(cancelsAt), PgValues.name(status));
    }

    /**
     * Moves an active free license onto the paid subscription its customer bought, keeping its price list, terms and
     * license year.
     *
     * @return the license as stored, or {@code null} when it is no longer an active free license
     */
    public Future<License> attachSubscription(String id, Plan plan, BillingPeriod billingPeriod, String stripeSubscriptionId,
                                              String checkoutSessionId, Instant currentPeriodEnd) {
        return pool.preparedQuery("UPDATE license SET plan = $2, billing_period = $3, stripe_subscription_id = $4,"
                                          + " checkout_session_id = $5, current_period_end = $6"
                                          + " WHERE id = $1 AND plan = 'FREE' AND status = 'ACTIVE' RETURNING " + COLUMNS)
                   .execute(Tuple.of(id, PgValues.name(plan), PgValues.name(billingPeriod), stripeSubscriptionId,
                                     checkoutSessionId, PgValues.timestamp(currentPeriodEnd)))
                   .map(rows -> PgValues.first(rows, LicenseRepository::toLicense));
    }

    /**
     * Lapses a license that is still past due.
     *
     * @return the license as stored, or {@code null} when it is not past due
     */
    public Future<License> lapse(String id) {
        return pool.preparedQuery("UPDATE license SET status = 'LAPSED', past_due_since = NULL WHERE id = $1 AND status = 'PAST_DUE'"
                                          + " RETURNING " + COLUMNS)
                   .execute(Tuple.of(id))
                   .map(rows -> PgValues.first(rows, LicenseRepository::toLicense));
    }

    /**
     * The licenses past due since before {@code pastDueBefore}, oldest first.
     */
    public Future<List<License>> findPastDueSince(Instant pastDueBefore) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM license WHERE status = 'PAST_DUE' AND past_due_since < $1"
                                          + " ORDER BY past_due_since")
                   .execute(Tuple.of(PgValues.timestamp(pastDueBefore)))
                   .map(rows -> PgValues.map(rows, LicenseRepository::toLicense));
    }

    /**
     * Moves the license to a new license year and stores that year's key, provided the license is active and is as it was
     * read: the same term end, status, plan and scheduled cancellation. So two callers moving the same term leave it
     * moved once, and a key is stored only for the standing it was signed for. The term moves only together with its key.
     *
     * @param read the license as the caller read it
     * @param key  the new term's key, which belongs to the license
     * @return the license as stored, or {@code null} when it is not active, or changed since it was read
     */
    public Future<License> startTerm(License read, Instant termStart, Instant termEnd, IssuedKey key) {
        // one statement, so a failed key insert also leaves the term where it was
        return pool.preparedQuery("WITH moved AS (UPDATE license SET term_start = $3, term_end = $4"
                                          + " WHERE id = $1 AND term_end = $2 AND status = 'ACTIVE' AND status = $9 AND plan = $10"
                                          + " AND cancels_at IS NOT DISTINCT FROM $11 RETURNING " + COLUMNS + "),"
                                          + " stored AS (INSERT INTO issued_key (jti, license_id, key, issued_at, expires_at)"
                                          + " SELECT $5, id, $6, $7, $8 FROM moved)"
                                          + " SELECT " + COLUMNS + " FROM moved")
                   .execute(Tuple.from(Arrays.asList(read.getId(), PgValues.timestamp(read.getTermEnd()), PgValues.timestamp(termStart),
                                                     PgValues.timestamp(termEnd), key.getJti(),
                                                     key.getKey(), PgValues.timestamp(key.getIssuedAt()),
                                                     PgValues.timestamp(key.getExpiresAt()), PgValues.name(read.getStatus()),
                                                     PgValues.name(read.getPlan()), PgValues.timestamp(read.getCancelsAt()))))
                   .map(rows -> PgValues.first(rows, LicenseRepository::toLicense));
    }

    public Future<License> findById(String id) {
        return findOne("id = $1", id);
    }

    public Future<License> findByStripeSubscriptionId(String stripeSubscriptionId) {
        return findOne("stripe_subscription_id = $1", stripeSubscriptionId);
    }

    public Future<License> findByCheckoutSessionId(String checkoutSessionId) {
        return findOne("checkout_session_id = $1", checkoutSessionId);
    }

    /**
     * The customer's free license, or {@code null} when it has none.
     */
    public Future<License> findFreeLicense(String customerId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM license WHERE customer_id = $1 AND plan = $2 ORDER BY created_at DESC LIMIT 1")
                   .execute(Tuple.of(customerId, Plan.FREE.name()))
                   .map(rows -> PgValues.first(rows, LicenseRepository::toLicense));
    }

    public Future<List<License>> findByCustomerId(String customerId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM license WHERE customer_id = $1 ORDER BY created_at DESC")
                   .execute(Tuple.of(customerId))
                   .map(rows -> PgValues.map(rows, LicenseRepository::toLicense));
    }

    private Future<License> updateOne(String assignments, String id, Object... values) {
        Tuple tuple = Tuple.of(id);
        for (Object value : values) {
            tuple.addValue(value);
        }
        return pool.preparedQuery("UPDATE license SET " + assignments + " WHERE id = $1 RETURNING " + COLUMNS)
                   .execute(tuple)
                   .map(rows -> PgValues.first(rows, LicenseRepository::toLicense));
    }

    private Future<License> findOne(String where, String value) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM license WHERE " + where)
                   .execute(Tuple.of(value))
                   .map(rows -> PgValues.first(rows, LicenseRepository::toLicense));
    }

    private static License toLicense(Row row) {
        return new License()
                .setId(row.getString("id"))
                .setCustomerId(row.getString("customer_id"))
                .setPlan(PgValues.enumValue(row, "plan", Plan.class))
                .setStatus(PgValues.enumValue(row, "status", LicenseStatus.class))
                .setBillingPeriod(PgValues.enumValue(row, "billing_period", BillingPeriod.class))
                .setPriceList(row.getInteger("price_list"))
                .setTermsVersion(row.getString("terms_version"))
                .setStripeSubscriptionId(row.getString("stripe_subscription_id"))
                .setCheckoutSessionId(row.getString("checkout_session_id"))
                .setCurrentPeriodEnd(PgValues.instant(row, "current_period_end"))
                .setCancelsAt(PgValues.instant(row, "cancels_at"))
                .setPastDueSince(PgValues.instant(row, "past_due_since"))
                .setTermStart(PgValues.instant(row, "term_start"))
                .setTermEnd(PgValues.instant(row, "term_end"))
                .setLastPaymentAt(PgValues.instant(row, "last_payment_at"))
                .setCreatedAt(PgValues.instant(row, "created_at"));
    }
}
