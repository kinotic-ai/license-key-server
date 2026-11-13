package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.CustomerSummary;
import org.kinotic.licenses.api.model.LicenseFlag;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Stores customers.
 */
@Repository
@RequiredArgsConstructor
public class CustomerRepository {

    private static final String COLUMNS = "id, legal_name, stripe_customer_id, created_at";

    // one row per customer: its newest license, the clusters holding its seats, and that license's newest key
    private static final String SUMMARY_SQL = """
            SELECT c.id, c.legal_name, c.stripe_customer_id, c.created_at,
                   l.id AS license_id, l.plan, l.status, l.billing_period, l.price_list, l.current_period_end, l.term_end, l.last_payment_at, l.flags, l.seats,
                   (SELECT count(*) FROM licensed_cluster WHERE license_id = l.id AND released_at IS NULL) AS clusters,
                   k.jti AS latest_key_jti, k.expires_at AS latest_key_expires_at,
                   (SELECT email FROM customer_contact WHERE customer_id = c.id AND role = 'BILLING' LIMIT 1) AS billing_email
            FROM customer c
            LEFT JOIN LATERAL (SELECT * FROM license WHERE customer_id = c.id ORDER BY created_at DESC LIMIT 1) l ON true
            LEFT JOIN LATERAL (SELECT * FROM issued_key WHERE license_id = l.id ORDER BY issued_at DESC LIMIT 1) k ON true
            ORDER BY c.created_at DESC
            """;

    private final Pool pool;

    public Future<Customer> save(Customer customer) {
        return pool.preparedQuery("INSERT INTO customer (id, legal_name, stripe_customer_id) VALUES ($1, $2, $3)")
                   .execute(Tuple.of(customer.getId(), customer.getLegalName(), customer.getStripeCustomerId()))
                   .compose(rows -> findById(customer.getId()));
    }

    public Future<Customer> findById(String id) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM customer WHERE id = $1")
                   .execute(Tuple.of(id))
                   .map(rows -> PgValues.first(rows, CustomerRepository::toCustomer));
    }

    public Future<Customer> findByStripeCustomerId(String stripeCustomerId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM customer WHERE stripe_customer_id = $1")
                   .execute(Tuple.of(stripeCustomerId))
                   .map(rows -> PgValues.first(rows, CustomerRepository::toCustomer));
    }

    /**
     * Removes a customer that nothing refers to yet, as a sign-up refused after the customer was stored leaves.
     */
    public Future<Void> delete(String id) {
        return pool.preparedQuery("DELETE FROM customer WHERE id = $1")
                   .execute(Tuple.of(id))
                   .mapEmpty();
    }

    public Future<Void> updateStripeCustomerId(String id, String stripeCustomerId) {
        return pool.preparedQuery("UPDATE customer SET stripe_customer_id = $2 WHERE id = $1")
                   .execute(Tuple.of(id, stripeCustomerId))
                   .mapEmpty();
    }

    /**
     * Every customer with the state of its newest license and that license's newest key, newest customer first.
     */
    public Future<List<CustomerSummary>> findSummaries() {
        return pool.query(SUMMARY_SQL)
                   .execute()
                   .map(rows -> PgValues.map(rows, CustomerRepository::toSummary));
    }

    private static Customer toCustomer(Row row) {
        return new Customer()
                .setId(row.getString("id"))
                .setLegalName(row.getString("legal_name"))
                .setStripeCustomerId(row.getString("stripe_customer_id"))
                .setCreatedAt(PgValues.instant(row, "created_at"));
    }

    private static CustomerSummary toSummary(Row row) {
        return new CustomerSummary()
                .setId(row.getString("id"))
                .setLegalName(row.getString("legal_name"))
                .setBillingEmail(row.getString("billing_email"))
                .setCreatedAt(PgValues.instant(row, "created_at"))
                .setLicenseId(row.getString("license_id"))
                .setPlan(PgValues.enumValue(row, "plan", Plan.class))
                .setStatus(PgValues.enumValue(row, "status", LicenseStatus.class))
                .setBillingPeriod(PgValues.enumValue(row, "billing_period", BillingPeriod.class))
                .setPriceList(row.getInteger("price_list"))
                .setCurrentPeriodEnd(PgValues.instant(row, "current_period_end"))
                .setTermEnd(PgValues.instant(row, "term_end"))
                .setLastPaymentAt(PgValues.instant(row, "last_payment_at"))
                .setFlags(PgValues.enumSet(row, "flags", LicenseFlag.class))
                .setSeats(row.getInteger("seats"))
                .setClusters(row.getString("license_id") != null ? Math.toIntExact(row.getLong("clusters")) : null)
                .setLatestKeyJti(row.getString("latest_key_jti"))
                .setLatestKeyExpiresAt(PgValues.instant(row, "latest_key_expires_at"));
    }
}
