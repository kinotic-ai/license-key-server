package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.FreeLicenseRequestStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Stores free license requests.
 */
@Repository
@RequiredArgsConstructor
public class FreeLicenseRequestRepository {

    // the organization and contact are the customer the request was made for and its billing contact
    private static final String SELECT = """
            SELECT r.id, r.customer_id, r.annual_revenue, r.capital_raised, r.intended_use, r.status, r.reviewed_by,
                   r.reviewed_at, r.created_at, c.legal_name,
                   (SELECT name FROM customer_contact WHERE customer_id = c.id AND role = 'BILLING' LIMIT 1) AS contact_name,
                   (SELECT email FROM customer_contact WHERE customer_id = c.id AND role = 'BILLING' LIMIT 1) AS contact_email
            FROM free_license_request r JOIN customer c ON c.id = r.customer_id
            """;

    private final Pool pool;

    public Future<FreeLicenseRequest> save(FreeLicenseRequest request) {
        return pool.preparedQuery("INSERT INTO free_license_request (id, customer_id, annual_revenue, capital_raised, intended_use, status)"
                                          + " VALUES ($1, $2, $3, $4, $5, $6)")
                   .execute(Tuple.of(request.getId(), request.getCustomerId(), request.getAnnualRevenue(),
                                     request.getCapitalRaised(), request.getIntendedUse(), PgValues.name(request.getStatus())))
                   .compose(rows -> findById(request.getId()));
    }

    public Future<FreeLicenseRequest> findById(String id) {
        return pool.preparedQuery(SELECT + " WHERE r.id = $1")
                   .execute(Tuple.of(id))
                   .map(rows -> PgValues.first(rows, FreeLicenseRequestRepository::toRequest));
    }

    /**
     * Every request with the status, oldest first.
     */
    public Future<List<FreeLicenseRequest>> findByStatus(FreeLicenseRequestStatus status) {
        return pool.preparedQuery(SELECT + " WHERE r.status = $1 ORDER BY r.created_at")
                   .execute(Tuple.of(status.name()))
                   .map(rows -> PgValues.map(rows, FreeLicenseRequestRepository::toRequest));
    }

    /**
     * The customer's requests, newest first.
     */
    public Future<List<FreeLicenseRequest>> findByCustomerId(String customerId) {
        return pool.preparedQuery(SELECT + " WHERE r.customer_id = $1 ORDER BY r.created_at DESC")
                   .execute(Tuple.of(customerId))
                   .map(rows -> PgValues.map(rows, FreeLicenseRequestRepository::toRequest));
    }

    /**
     * Records the decision on a pending request. The update is conditional on the request still being pending, so two
     * decisions on one request leave exactly one recorded.
     *
     * @return the reviewed request, or {@code null} when the request was not pending or does not exist
     */
    public Future<FreeLicenseRequest> review(String id, FreeLicenseRequestStatus status, String reviewedBy, Instant reviewedAt) {
        return pool.preparedQuery("UPDATE free_license_request SET status = $2, reviewed_by = $3, reviewed_at = $4"
                                          + " WHERE id = $1 AND status = $5")
                   .execute(Tuple.of(id, status.name(), reviewedBy, PgValues.timestamp(reviewedAt), FreeLicenseRequestStatus.PENDING.name()))
                   .compose(rows -> rows.rowCount() == 1 ? findById(id) : Future.succeededFuture(null));
    }

    /**
     * Returns a decided request to pending, with no reviewer, as when issuing after an approval failed.
     */
    public Future<Void> reopen(String id) {
        return pool.preparedQuery("UPDATE free_license_request SET status = $2, reviewed_by = NULL, reviewed_at = NULL WHERE id = $1")
                   .execute(Tuple.of(id, FreeLicenseRequestStatus.PENDING.name()))
                   .mapEmpty();
    }

    private static FreeLicenseRequest toRequest(Row row) {
        return new FreeLicenseRequest()
                .setId(row.getString("id"))
                .setCustomerId(row.getString("customer_id"))
                .setOrganization(row.getString("legal_name"))
                .setContactName(row.getString("contact_name"))
                .setContactEmail(row.getString("contact_email"))
                .setAnnualRevenue(row.getBigDecimal("annual_revenue"))
                .setCapitalRaised(row.getBigDecimal("capital_raised"))
                .setIntendedUse(row.getString("intended_use"))
                .setStatus(PgValues.enumValue(row, "status", FreeLicenseRequestStatus.class))
                .setReviewedBy(row.getString("reviewed_by"))
                .setReviewedAt(PgValues.instant(row, "reviewed_at"))
                .setCreatedAt(PgValues.instant(row, "created_at"));
    }
}
