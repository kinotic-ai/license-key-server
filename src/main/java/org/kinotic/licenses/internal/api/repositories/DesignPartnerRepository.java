package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.CaseStudyStatus;
import org.kinotic.licenses.api.model.DesignPartner;
import org.kinotic.licenses.api.model.DesignPartnerPublication;
import org.kinotic.licenses.api.model.PublicationType;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stores design partners with their publications, one per {@link PublicationType}.
 */
@Repository
@RequiredArgsConstructor
public class DesignPartnerRepository {

    // a design partner per row group, joined to its publications and its customer's name
    private static final String SELECT = """
            SELECT d.id, d.customer_id, d.application_id, d.license_id, d.approved_at, d.discount_ends_at, d.case_study_status,
                   c.legal_name, p.id AS publication_id, p.type, p.approved_by, p.approved_at AS publication_approved_at
            FROM design_partner d
            JOIN customer c ON c.id = d.customer_id
            LEFT JOIN design_partner_publication p ON p.design_partner_id = d.id
            """;

    private final Pool pool;

    /**
     * Creates the design partner of an approved application, with each of its publications awaiting the customer's
     * approval.
     */
    public Future<DesignPartner> create(String customerId, String applicationId, Instant approvedAt) {
        String id = UUID.randomUUID().toString();
        List<String> publicationIds = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (PublicationType type : PublicationType.values()) {
            publicationIds.add(UUID.randomUUID().toString());
            types.add(type.name());
        }
        // one statement, so a design partner always has its publications
        return pool.preparedQuery("WITH partner AS (INSERT INTO design_partner (id, customer_id, application_id, approved_at, case_study_status)"
                                          + " VALUES ($1, $2, $3, $4, $5) RETURNING id)"
                                          + " INSERT INTO design_partner_publication (id, design_partner_id, type)"
                                          + " SELECT p.id, partner.id, p.type FROM partner, unnest($6::text[], $7::text[]) AS p(id, type)")
                   .execute(Tuple.of(id, customerId, applicationId, PgValues.timestamp(approvedAt), CaseStudyStatus.NOT_STARTED.name(),
                                     publicationIds.toArray(String[]::new), types.toArray(String[]::new)))
                   .compose(rows -> findOne("d.id = $1", id));
    }

    /**
     * Records the license the design partner's discounted subscription bought and when its discount ends.
     *
     * @return the design partner, or {@code null} when no design partner has the application
     */
    public Future<DesignPartner> attachLicense(String applicationId, String licenseId, Instant discountEndsAt) {
        return pool.preparedQuery("UPDATE design_partner SET license_id = $2, discount_ends_at = $3 WHERE application_id = $1 RETURNING id")
                   .execute(Tuple.of(applicationId, licenseId, PgValues.timestamp(discountEndsAt)))
                   .compose(rows -> rows.size() == 0 ? Future.succeededFuture() : findOne("d.application_id = $1", applicationId));
    }

    /**
     * Records that the customer approved a publication.
     *
     * @param approvedBy the person at the customer who approved it
     * @return the design partner, or {@code null} when no publication has the id
     */
    public Future<DesignPartner> approvePublication(String publicationId, String approvedBy) {
        return pool.preparedQuery("UPDATE design_partner_publication SET approved_by = $2, approved_at = now() WHERE id = $1"
                                          + " RETURNING design_partner_id")
                   .execute(Tuple.of(publicationId, approvedBy))
                   .compose(rows -> rows.size() == 0
                           ? Future.succeededFuture()
                           : findOne("d.id = $1", rows.iterator().next().getString("design_partner_id")));
    }

    /**
     * Sets the case study's status, provided a {@link CaseStudyStatus#PUBLISHED} one has its {@link PublicationType#CASE_STUDY}
     * publication approved.
     *
     * @return the design partner, or {@code null} when it is published unapproved or no design partner has the id
     */
    public Future<DesignPartner> setCaseStudyStatus(String id, CaseStudyStatus status) {
        // the approval check and the update are one statement, so a published case study was approved when it was set
        return pool.preparedQuery("UPDATE design_partner d SET case_study_status = $2 WHERE d.id = $1 AND ($2 <> 'PUBLISHED' OR EXISTS"
                                          + " (SELECT 1 FROM design_partner_publication p WHERE p.design_partner_id = d.id"
                                          + " AND p.type = 'CASE_STUDY' AND p.approved_at IS NOT NULL)) RETURNING d.id")
                   .execute(Tuple.of(id, status.name()))
                   .compose(rows -> rows.size() == 0 ? Future.succeededFuture() : findOne("d.id = $1", id));
    }

    /**
     * Every design partner, newest approval first.
     */
    public Future<List<DesignPartner>> findAll() {
        return pool.query(SELECT + " ORDER BY d.approved_at DESC, p.type")
                   .execute()
                   .map(DesignPartnerRepository::toPartners);
    }

    /**
     * The customer's design partner, or {@code null} when it is none.
     */
    public Future<DesignPartner> findByCustomerId(String customerId) {
        return findOne("d.customer_id = $1", customerId);
    }

    private Future<DesignPartner> findOne(String where, String value) {
        return pool.preparedQuery(SELECT + " WHERE " + where + " ORDER BY d.approved_at DESC, p.type")
                   .execute(Tuple.of(value))
                   .map(rows -> {
                       List<DesignPartner> partners = toPartners(rows);
                       return partners.isEmpty() ? null : partners.getFirst();
                   });
    }

    // each design partner with its publications, in the order its rows come
    private static List<DesignPartner> toPartners(RowSet<Row> rows) {
        Map<String, DesignPartner> partners = new LinkedHashMap<>();
        for (Row row : rows) {
            DesignPartner partner = partners.computeIfAbsent(row.getString("id"), id -> new DesignPartner()
                    .setId(id)
                    .setCustomerId(row.getString("customer_id"))
                    .setOrganization(row.getString("legal_name"))
                    .setApplicationId(row.getString("application_id"))
                    .setLicenseId(row.getString("license_id"))
                    .setApprovedAt(PgValues.instant(row, "approved_at"))
                    .setDiscountEndsAt(PgValues.instant(row, "discount_ends_at"))
                    .setCaseStudyStatus(PgValues.enumValue(row, "case_study_status", CaseStudyStatus.class))
                    .setPublications(new ArrayList<>()));
            if (row.getString("publication_id") != null) {
                partner.getPublications().add(new DesignPartnerPublication()
                                                      .setId(row.getString("publication_id"))
                                                      .setDesignPartnerId(partner.getId())
                                                      .setType(PgValues.enumValue(row, "type", PublicationType.class))
                                                      .setApprovedBy(row.getString("approved_by"))
                                                      .setApprovedAt(PgValues.instant(row, "publication_approved_at")));
            }
        }
        return new ArrayList<>(partners.values());
    }
}
