package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.AgreementAcceptance;
import org.kinotic.licenses.api.model.PublicityConsent;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Stores agreement acceptances.
 */
@Repository
@RequiredArgsConstructor
public class AgreementAcceptanceRepository {

    private static final String COLUMNS = "id, customer_id, terms_version, accepted_by_name, accepted_by_email, publicity_consent, accepted_at";

    private final Pool pool;

    public Future<AgreementAcceptance> save(AgreementAcceptance acceptance) {
        return pool.preparedQuery("INSERT INTO agreement_acceptance (id, customer_id, terms_version, accepted_by_name, accepted_by_email,"
                                          + " publicity_consent) VALUES ($1, $2, $3, $4, $5, $6) RETURNING " + COLUMNS)
                   .execute(Tuple.of(acceptance.getId(), acceptance.getCustomerId(), acceptance.getTermsVersion(),
                                     acceptance.getAcceptedByName(), acceptance.getAcceptedByEmail(),
                                     PgValues.name(acceptance.getPublicityConsent())))
                   .map(rows -> PgValues.first(rows, AgreementAcceptanceRepository::toAcceptance));
    }

    public Future<List<AgreementAcceptance>> findByCustomerId(String customerId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM agreement_acceptance WHERE customer_id = $1 ORDER BY accepted_at DESC")
                   .execute(Tuple.of(customerId))
                   .map(rows -> PgValues.map(rows, AgreementAcceptanceRepository::toAcceptance));
    }

    private static AgreementAcceptance toAcceptance(Row row) {
        return new AgreementAcceptance()
                .setId(row.getString("id"))
                .setCustomerId(row.getString("customer_id"))
                .setTermsVersion(row.getString("terms_version"))
                .setAcceptedByName(row.getString("accepted_by_name"))
                .setAcceptedByEmail(row.getString("accepted_by_email"))
                .setPublicityConsent(PgValues.enumValue(row, "publicity_consent", PublicityConsent.class))
                .setAcceptedAt(PgValues.instant(row, "accepted_at"));
    }
}
