package org.kinotic.licenses.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.licenses.api.model.CaseStudyStatus;
import org.kinotic.licenses.api.model.DesignPartner;
import org.kinotic.licenses.api.model.DesignPartnerApplication;
import org.kinotic.licenses.api.security.LicenseZones;

import java.util.List;

/**
 * The staff dashboard's design partners: the queue of applications, and each design partner with what may be published
 * about it.
 */
@Publish
@Version("1.0.0")
@Zone(LicenseZones.STAFF)
public interface StaffDesignPartnerService {

    /**
     * The applications awaiting review, oldest first.
     */
    Future<List<DesignPartnerApplication>> findPendingApplications();

    /**
     * Approves an application: the customer becomes a design partner and its contacts are emailed the link that starts
     * its subscription at half off for 36 months.
     *
     * @throws IllegalStateException when the application is not pending
     */
    Future<DesignPartner> approveApplication(String applicationId);

    /**
     * Rejects an application and tells the customer's contacts.
     *
     * @throws IllegalStateException when the application is not pending
     */
    Future<DesignPartnerApplication> rejectApplication(String applicationId);

    /**
     * Every design partner with its publications, newest first.
     */
    Future<List<DesignPartner>> findDesignPartners();

    /**
     * Records that the customer approved a publication, so it may go out.
     *
     * @param publicationId the publication
     * @param approvedBy    the person at the customer who approved it
     * @throws IllegalArgumentException when no publication has the id
     */
    Future<DesignPartner> approvePublication(String publicationId, String approvedBy);

    /**
     * Sets where a design partner's case study stands.
     *
     * @throws IllegalStateException when the status is {@link CaseStudyStatus#PUBLISHED} and the customer has not
     *                               approved the case study
     */
    Future<DesignPartner> setCaseStudyStatus(String designPartnerId, CaseStudyStatus status);
}
