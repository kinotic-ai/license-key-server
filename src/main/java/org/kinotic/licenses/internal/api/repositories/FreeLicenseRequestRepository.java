package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.FreeLicenseRequestStatus;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Stores free license requests. A customer has at most one open request: one that is pending, or approved with its
 * key not yet issued. Every decision is a single conditional statement, so two decisions on one request leave exactly
 * one recorded.
 */
@Repository
@RequiredArgsConstructor
public class FreeLicenseRequestRepository {

    /** Answers a request made while another of the customer's is still open. */
    public static final String ALREADY_OPEN = "A free license request of this account is already being reviewed";

    private static final String UNIQUE_VIOLATION = "23505";
    // how long an approval may take to issue its key before another approval may take it over
    private static final String CLAIM_LIFETIME = "5 minutes";

    // the organization and contact are the customer the request was made for and its billing contact; the rows come
    // from a table expression, so a statement that changes a request returns it in the same shape a query does
    private static final String SELECT = """
            SELECT r.id, r.customer_id, r.annual_revenue, r.capital_raised, r.intended_use, r.status, r.reviewed_by,
                   r.reviewed_at, r.created_at, c.legal_name,
                   (SELECT name FROM customer_contact WHERE customer_id = c.id AND role = 'BILLING' LIMIT 1) AS contact_name,
                   (SELECT email FROM customer_contact WHERE customer_id = c.id AND role = 'BILLING' LIMIT 1) AS contact_email
            FROM %s r JOIN customer c ON c.id = r.customer_id
            """;
    private static final String SELECT_ALL = SELECT.formatted("free_license_request");

    private final Pool pool;

    /**
     * Stores a new pending request.
     *
     * @return the request, or a failure with {@link AlreadyExistsException} when the customer has another open request
     */
    public Future<FreeLicenseRequest> save(FreeLicenseRequest request) {
        return changeOne("INSERT INTO free_license_request (id, customer_id, annual_revenue, capital_raised, intended_use, status)"
                                 + " VALUES ($1, $2, $3, $4, $5, $6)",
                         Tuple.of(request.getId(), request.getCustomerId(), request.getAnnualRevenue(),
                                  request.getCapitalRaised(), request.getIntendedUse(), PgValues.name(request.getStatus())))
                .recover(t -> Future.failedFuture(t instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState())
                                                          ? new AlreadyExistsException(ALREADY_OPEN, t)
                                                          : t));
    }

    public Future<FreeLicenseRequest> findById(String id) {
        return pool.preparedQuery(SELECT_ALL + " WHERE r.id = $1")
                   .execute(Tuple.of(id))
                   .map(rows -> PgValues.first(rows, FreeLicenseRequestRepository::toRequest));
    }

    /**
     * The requests awaiting a decision, oldest first: the pending ones, and the approvals that stopped before their key
     * was issued, which approving again completes.
     */
    public Future<List<FreeLicenseRequest>> findAwaitingDecision() {
        return pool.preparedQuery(SELECT_ALL + " WHERE r.status = $1 OR (" + stalledApproval("r.") + ") ORDER BY r.created_at")
                   .execute(Tuple.of(FreeLicenseRequestStatus.PENDING.name()))
                   .map(rows -> PgValues.map(rows, FreeLicenseRequestRepository::toRequest));
    }

    /**
     * The customer's requests, newest first.
     */
    public Future<List<FreeLicenseRequest>> findByCustomerId(String customerId) {
        return pool.preparedQuery(SELECT_ALL + " WHERE r.customer_id = $1 ORDER BY r.created_at DESC")
                   .execute(Tuple.of(customerId))
                   .map(rows -> PgValues.map(rows, FreeLicenseRequestRepository::toRequest));
    }

    /**
     * Takes the approval of a request for the caller to issue its key: a pending request is approved by
     * {@code reviewedBy}, and an approval that stopped before its key was issued is taken over, keeping its reviewer
     * and time.
     *
     * @return the approved request, or {@code null} when it was decided already, another approval is issuing its key,
     *         or no request has the id
     */
    public Future<FreeLicenseRequest> claimApproval(String id, String reviewedBy) {
        return changeOne("UPDATE free_license_request SET"
                                 + " reviewed_by = CASE WHEN status = 'PENDING' THEN $2 ELSE reviewed_by END,"
                                 + " reviewed_at = CASE WHEN status = 'PENDING' THEN now() ELSE reviewed_at END,"
                                 + " status = 'APPROVED', claimed_at = now()"
                                 + " WHERE id = $1 AND (status = 'PENDING' OR (" + stalledApproval("") + "))",
                         Tuple.of(id, reviewedBy));
    }

    /**
     * Completes an approval with the key it issued.
     */
    public Future<Void> markIssued(String id, String jti) {
        return pool.preparedQuery("UPDATE free_license_request SET issued_key_jti = $2 WHERE id = $1")
                   .execute(Tuple.of(id, jti))
                   .mapEmpty();
    }

    /**
     * Rejects a pending request.
     *
     * @return the rejected request, or {@code null} when it was not pending or no request has the id
     */
    public Future<FreeLicenseRequest> reject(String id, String reviewedBy) {
        return changeOne("UPDATE free_license_request SET status = 'REJECTED', reviewed_by = $2, reviewed_at = now()"
                                 + " WHERE id = $1 AND status = 'PENDING'",
                         Tuple.of(id, reviewedBy));
    }

    /**
     * Returns an approval whose key could not be issued to pending, with no reviewer.
     */
    public Future<Void> reopen(String id) {
        return pool.preparedQuery("UPDATE free_license_request SET status = 'PENDING', reviewed_by = NULL, reviewed_at = NULL,"
                                          + " claimed_at = NULL WHERE id = $1 AND issued_key_jti IS NULL")
                   .execute(Tuple.of(id))
                   .mapEmpty();
    }

    // an approval that claimed a request long enough ago and has still not recorded its key, the columns named with prefix
    private static String stalledApproval(String prefix) {
        return "%1$sstatus = 'APPROVED' AND %1$sissued_key_jti IS NULL AND %1$sclaimed_at < now() - interval '%2$s'"
                .formatted(prefix, CLAIM_LIFETIME);
    }

    // runs a statement that changes at most one request and answers with that request as a query would
    private Future<FreeLicenseRequest> changeOne(String statement, Tuple values) {
        return pool.preparedQuery("WITH changed AS (" + statement + " RETURNING *) " + SELECT.formatted("changed"))
                   .execute(values)
                   .map(rows -> PgValues.first(rows, FreeLicenseRequestRepository::toRequest));
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
