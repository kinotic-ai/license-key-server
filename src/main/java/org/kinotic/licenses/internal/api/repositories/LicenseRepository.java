package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.Feature;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Stores licenses. Every change writes only the columns it is about, in one statement, so changes made at the same
 * time by different callers never overwrite each other, and a revoked license, or one whose subscription ended, stays so
 * whatever Stripe reports later.
 */
@Repository
@RequiredArgsConstructor
public class LicenseRepository {

    private static final String COLUMNS = "id, customer_id, plan, status, features, stripe_subscription_id, checkout_session_id,"
            + " current_period_end, cancels_at, term_start, term_end, last_payment_at, created_at";
    // the status a billing change sets, unless staff revoked the license or its subscription ended; Stripe never
    // reopens a canceled subscription, so a later write is an event delivered out of order
    private static final String STATUS_UNLESS_ENDED = "status = CASE WHEN status IN ('REVOKED', 'CANCELED') THEN status ELSE %s END";

    private final Pool pool;

    public Future<License> save(License license) {
        return pool.preparedQuery("INSERT INTO license (id, customer_id, plan, status, features, stripe_subscription_id,"
                                          + " checkout_session_id, current_period_end, term_start, term_end, last_payment_at)"
                                          + " VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) RETURNING " + COLUMNS)
                   .execute(Tuple.of(license.getId(), license.getCustomerId(), PgValues.name(license.getPlan()),
                                     PgValues.name(license.getStatus()), featureNames(license.getFeatures()),
                                     license.getStripeSubscriptionId(), license.getCheckoutSessionId(),
                                     PgValues.timestamp(license.getCurrentPeriodEnd()), PgValues.timestamp(license.getTermStart()),
                                     PgValues.timestamp(license.getTermEnd()), PgValues.timestamp(license.getLastPaymentAt())))
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
     * Writes what Stripe reports about the subscription: its plan and features, the end of the billing period, when it
     * is set to end, and its status, unless the license is revoked or canceled.
     *
     * @return the license as stored, or {@code null} when none has the id
     */
    public Future<License> syncSubscription(String id, Plan plan, LicenseStatus status, Instant currentPeriodEnd, Instant cancelsAt) {
        return updateOne("plan = $2, features = $3, current_period_end = $4, cancels_at = $5, " + STATUS_UNLESS_ENDED.formatted("$6"),
                         id, PgValues.name(plan), featureNames(plan.features()), PgValues.timestamp(currentPeriodEnd),
                         PgValues.timestamp(cancelsAt), PgValues.name(status));
    }

    /**
     * Moves the license to a new term and stores that term's key, provided the license is not revoked and is as it was
     * read: the same term end, status, plan and scheduled cancellation. So two callers moving the same term leave it
     * moved once, and a key is stored only for the standing it was signed for. The term moves only together with its key.
     *
     * @param read the license as the caller read it
     * @param key  the new term's key, which belongs to the license
     * @return the license as stored, or {@code null} when it is revoked, or changed since it was read
     */
    public Future<License> startTerm(License read, Instant termStart, Instant termEnd, IssuedKey key) {
        // one statement, so a failed key insert also leaves the term where it was
        return pool.preparedQuery("WITH moved AS (UPDATE license SET term_start = $3, term_end = $4"
                                          + " WHERE id = $1 AND term_end = $2 AND status <> 'REVOKED' AND status = $10 AND plan = $11"
                                          + " AND cancels_at IS NOT DISTINCT FROM $12 RETURNING " + COLUMNS + "),"
                                          + " stored AS (INSERT INTO issued_key (jti, license_id, purpose, key, issued_at, expires_at)"
                                          + " SELECT $5, id, $6, $7, $8, $9 FROM moved)"
                                          + " SELECT " + COLUMNS + " FROM moved")
                   .execute(Tuple.from(Arrays.asList(read.getId(), PgValues.timestamp(read.getTermEnd()), PgValues.timestamp(termStart),
                                                     PgValues.timestamp(termEnd), key.getJti(), PgValues.name(key.getPurpose()),
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

    /**
     * The paid licenses in good standing whose subscription renews and whose term ends before {@code termEndsBefore},
     * which are due a renewal key. A free license renews only through a re-certification staff approve.
     */
    public Future<List<License>> findPaidRenewalsDue(Instant termEndsBefore) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM license WHERE status = $1 AND plan <> $2 AND cancels_at IS NULL"
                                          + " AND term_end < $3 ORDER BY term_end")
                   .execute(Tuple.of(LicenseStatus.ACTIVE.name(), Plan.FREE.name(), PgValues.timestamp(termEndsBefore)))
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

    private static String[] featureNames(Set<Feature> features) {
        return features.stream().map(Feature::name).toArray(String[]::new);
    }

    private static License toLicense(Row row) {
        Set<Feature> features = EnumSet.noneOf(Feature.class);
        for (String name : row.getArrayOfStrings("features")) {
            features.add(Feature.valueOf(name));
        }
        return new License()
                .setId(row.getString("id"))
                .setCustomerId(row.getString("customer_id"))
                .setPlan(PgValues.enumValue(row, "plan", Plan.class))
                .setStatus(PgValues.enumValue(row, "status", LicenseStatus.class))
                .setFeatures(features)
                .setStripeSubscriptionId(row.getString("stripe_subscription_id"))
                .setCheckoutSessionId(row.getString("checkout_session_id"))
                .setCurrentPeriodEnd(PgValues.instant(row, "current_period_end"))
                .setCancelsAt(PgValues.instant(row, "cancels_at"))
                .setTermStart(PgValues.instant(row, "term_start"))
                .setTermEnd(PgValues.instant(row, "term_end"))
                .setLastPaymentAt(PgValues.instant(row, "last_payment_at"))
                .setCreatedAt(PgValues.instant(row, "created_at"));
    }
}
