package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.Feature;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Stores licenses.
 */
@Repository
@RequiredArgsConstructor
public class LicenseRepository {

    private static final String COLUMNS = "id, customer_id, plan, status, features, stripe_subscription_id, checkout_session_id,"
            + " current_period_end, term_start, term_end, last_payment_at, created_at";

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
     * Writes the mutable state of a license: its plan, status, features, billing period, term and last payment.
     */
    public Future<License> update(License license) {
        return pool.preparedQuery("UPDATE license SET plan = $2, status = $3, features = $4, current_period_end = $5,"
                                          + " term_start = $6, term_end = $7, last_payment_at = $8 WHERE id = $1 RETURNING " + COLUMNS)
                   .execute(Tuple.of(license.getId(), PgValues.name(license.getPlan()), PgValues.name(license.getStatus()),
                                     featureNames(license.getFeatures()), PgValues.timestamp(license.getCurrentPeriodEnd()),
                                     PgValues.timestamp(license.getTermStart()), PgValues.timestamp(license.getTermEnd()),
                                     PgValues.timestamp(license.getLastPaymentAt())))
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
     * The paid licenses in good standing whose term ends before {@code termEndsBefore}, which are due a renewal key.
     * A free license renews only through a re-certification staff approve.
     */
    public Future<List<License>> findPaidRenewalsDue(Instant termEndsBefore) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM license WHERE status = $1 AND plan <> $2 AND term_end < $3 ORDER BY term_end")
                   .execute(Tuple.of(LicenseStatus.ACTIVE.name(), Plan.FREE.name(), PgValues.timestamp(termEndsBefore)))
                   .map(rows -> PgValues.map(rows, LicenseRepository::toLicense));
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
                .setTermStart(PgValues.instant(row, "term_start"))
                .setTermEnd(PgValues.instant(row, "term_end"))
                .setLastPaymentAt(PgValues.instant(row, "last_payment_at"))
                .setCreatedAt(PgValues.instant(row, "created_at"));
    }
}
