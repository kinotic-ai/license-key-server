package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.DesignPartnerApplication;
import org.kinotic.licenses.api.model.DesignPartnerApplicationStatus;
import org.kinotic.licenses.api.model.Plan;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;

/**
 * Stores design partner applications. A customer has at most one pending, and every decision is one conditional
 * statement, so two decisions on one application leave exactly one recorded.
 */
@Repository
@RequiredArgsConstructor
public class DesignPartnerApplicationRepository {

    /** Answers an application made while another of the customer's is pending. */
    public static final String ALREADY_PENDING = "An application of this account to be a design partner is already being reviewed";

    private static final String UNIQUE_VIOLATION = "23505";
    // the organization and contact are the customer and its billing contact; the rows come from a table expression, so
    // a statement that changes an application returns it in the same shape a query does
    private static final String SELECT = """
            SELECT a.id, a.customer_id, a.plan, a.billing_period, a.annual_revenue, a.capital_raised, a.intended_use, a.status,
                   a.reviewed_by, a.reviewed_at, a.created_at, c.legal_name,
                   (SELECT name FROM customer_contact WHERE customer_id = c.id AND role = 'BILLING' LIMIT 1) AS contact_name,
                   (SELECT email FROM customer_contact WHERE customer_id = c.id AND role = 'BILLING' LIMIT 1) AS contact_email
            FROM %s a JOIN customer c ON c.id = a.customer_id
            """;
    private static final String SELECT_ALL = SELECT.formatted("design_partner_application");

    private final Pool pool;

    /**
     * Stores a new pending application.
     *
     * @return the application, or a failure with {@link AlreadyExistsException} when the customer has one pending
     */
    public Future<DesignPartnerApplication> save(DesignPartnerApplication application) {
        return changeOne("INSERT INTO design_partner_application (id, customer_id, plan, billing_period, annual_revenue, capital_raised,"
                                 + " intended_use, status) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)",
                         Tuple.from(Arrays.asList(application.getId(), application.getCustomerId(), PgValues.name(application.getPlan()),
                                                  PgValues.name(application.getBillingPeriod()), application.getFigures().getAnnualRevenue(),
                                                  application.getFigures().getCapitalRaised(), application.getIntendedUse(),
                                                  PgValues.name(application.getStatus()))))
                .recover(t -> Future.failedFuture(t instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState())
                                                          ? new AlreadyExistsException(ALREADY_PENDING, t)
                                                          : t));
    }

    public Future<DesignPartnerApplication> findById(String id) {
        return pool.preparedQuery(SELECT_ALL + " WHERE a.id = $1")
                   .execute(Tuple.of(id))
                   .map(rows -> PgValues.first(rows, DesignPartnerApplicationRepository::toApplication));
    }

    /**
     * The applications awaiting review, oldest first.
     */
    public Future<List<DesignPartnerApplication>> findPending() {
        return pool.preparedQuery(SELECT_ALL + " WHERE a.status = $1 ORDER BY a.created_at")
                   .execute(Tuple.of(DesignPartnerApplicationStatus.PENDING.name()))
                   .map(rows -> PgValues.map(rows, DesignPartnerApplicationRepository::toApplication));
    }

    /**
     * The customer's applications, newest first.
     */
    public Future<List<DesignPartnerApplication>> findByCustomerId(String customerId) {
        return pool.preparedQuery(SELECT_ALL + " WHERE a.customer_id = $1 ORDER BY a.created_at DESC")
                   .execute(Tuple.of(customerId))
                   .map(rows -> PgValues.map(rows, DesignPartnerApplicationRepository::toApplication));
    }

    /**
     * Approves or rejects a pending application.
     *
     * @return the application decided, or {@code null} when it was not pending or none has the id
     */
    public Future<DesignPartnerApplication> decide(String id, DesignPartnerApplicationStatus status, String reviewedBy) {
        return changeOne("UPDATE design_partner_application SET status = $2, reviewed_by = $3, reviewed_at = now()"
                                 + " WHERE id = $1 AND status = 'PENDING'",
                         Tuple.of(id, status.name(), reviewedBy));
    }

    private Future<DesignPartnerApplication> changeOne(String statement, Tuple values) {
        return pool.preparedQuery("WITH changed AS (" + statement + " RETURNING *) " + SELECT.formatted("changed"))
                   .execute(values)
                   .map(rows -> PgValues.first(rows, DesignPartnerApplicationRepository::toApplication));
    }

    private static DesignPartnerApplication toApplication(Row row) {
        return new DesignPartnerApplication()
                .setId(row.getString("id"))
                .setCustomerId(row.getString("customer_id"))
                .setOrganization(row.getString("legal_name"))
                .setContactName(row.getString("contact_name"))
                .setContactEmail(row.getString("contact_email"))
                .setPlan(PgValues.enumValue(row, "plan", Plan.class))
                .setBillingPeriod(PgValues.enumValue(row, "billing_period", BillingPeriod.class))
                .setFigures(new CompanyFigures().setAnnualRevenue(row.getBigDecimal("annual_revenue"))
                                                .setCapitalRaised(row.getBigDecimal("capital_raised")))
                .setIntendedUse(row.getString("intended_use"))
                .setStatus(PgValues.enumValue(row, "status", DesignPartnerApplicationStatus.class))
                .setReviewedBy(row.getString("reviewed_by"))
                .setReviewedAt(PgValues.instant(row, "reviewed_at"))
                .setCreatedAt(PgValues.instant(row, "created_at"));
    }
}
