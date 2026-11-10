package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.RevenueConfirmation;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/**
 * Stores yearly revenue confirmations, one per license and anniversary.
 */
@Repository
@RequiredArgsConstructor
public class RevenueConfirmationRepository {

    /** Answers a second confirmation for the same anniversary. */
    public static final String ALREADY_CONFIRMED = "Your figures are already confirmed for this anniversary";

    private static final String COLUMNS = "id, license_id, anniversary, annual_revenue, capital_raised, plan, license_plan, applies_at,"
            + " confirmed_by_name, confirmed_by_email, confirmed_at";
    private static final String UNIQUE_VIOLATION = "23505";

    private final Pool pool;

    /**
     * Stores a confirmation.
     *
     * @return the confirmation, or a failure with {@link AlreadyExistsException} when the license has one for the
     *         anniversary
     */
    public Future<RevenueConfirmation> save(RevenueConfirmation confirmation) {
        return pool.preparedQuery("INSERT INTO revenue_confirmation (id, license_id, anniversary, annual_revenue, capital_raised, plan,"
                                          + " license_plan, applies_at, confirmed_by_name, confirmed_by_email) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)"
                                          + " RETURNING " + COLUMNS)
                   .execute(Tuple.from(Arrays.asList(confirmation.getId(), confirmation.getLicenseId(),
                                                     PgValues.timestamp(confirmation.getAnniversary()),
                                                     confirmation.getFigures().getAnnualRevenue(), confirmation.getFigures().getCapitalRaised(),
                                                     PgValues.name(confirmation.getPlan()), PgValues.name(confirmation.getLicensePlan()),
                                                     PgValues.timestamp(confirmation.getAppliesAt()),
                                                     confirmation.getConfirmedByName(), confirmation.getConfirmedByEmail())))
                   .map(rows -> PgValues.first(rows, RevenueConfirmationRepository::toConfirmation))
                   .recover(t -> Future.failedFuture(t instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState())
                                                             ? new AlreadyExistsException(ALREADY_CONFIRMED, t)
                                                             : t));
    }

    /**
     * The license's confirmation for the anniversary, or {@code null} when it has none.
     */
    public Future<RevenueConfirmation> find(String licenseId, Instant anniversary) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM revenue_confirmation WHERE license_id = $1 AND anniversary = $2")
                   .execute(Tuple.of(licenseId, PgValues.timestamp(anniversary)))
                   .map(rows -> PgValues.first(rows, RevenueConfirmationRepository::toConfirmation));
    }

    /**
     * The newest confirmation of the license whose plan takes effect at the anniversary {@code at}, to the second, or
     * {@code null} when none does.
     */
    public Future<RevenueConfirmation> findApplyingAt(String licenseId, Instant at) {
        // to the second, as Stripe reports the cancel_at a move to Free set to the anniversary
        Instant second = at.truncatedTo(ChronoUnit.SECONDS);
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM revenue_confirmation WHERE license_id = $1"
                                          + " AND applies_at >= $2 AND applies_at < $3 ORDER BY confirmed_at DESC LIMIT 1")
                   .execute(Tuple.of(licenseId, PgValues.timestamp(second), PgValues.timestamp(second.plusSeconds(1))))
                   .map(rows -> PgValues.first(rows, RevenueConfirmationRepository::toConfirmation));
    }

    /**
     * The license's confirmations, newest anniversary first.
     */
    public Future<List<RevenueConfirmation>> findByLicenseId(String licenseId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM revenue_confirmation WHERE license_id = $1 ORDER BY anniversary DESC")
                   .execute(Tuple.of(licenseId))
                   .map(rows -> PgValues.map(rows, RevenueConfirmationRepository::toConfirmation));
    }

    private static RevenueConfirmation toConfirmation(Row row) {
        return new RevenueConfirmation()
                .setId(row.getString("id"))
                .setLicenseId(row.getString("license_id"))
                .setAnniversary(PgValues.instant(row, "anniversary"))
                .setFigures(new CompanyFigures().setAnnualRevenue(row.getBigDecimal("annual_revenue"))
                                                .setCapitalRaised(row.getBigDecimal("capital_raised")))
                .setPlan(PgValues.enumValue(row, "plan", Plan.class))
                .setLicensePlan(PgValues.enumValue(row, "license_plan", Plan.class))
                .setAppliesAt(PgValues.instant(row, "applies_at"))
                .setConfirmedByName(row.getString("confirmed_by_name"))
                .setConfirmedByEmail(row.getString("confirmed_by_email"))
                .setConfirmedAt(PgValues.instant(row, "confirmed_at"));
    }
}
