package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.api.model.CaseStudyStatus;
import org.kinotic.licenses.api.model.DesignPartner;
import org.kinotic.licenses.api.model.DesignPartnerApplication;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.services.StaffDesignPartnerService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The staff dashboard's design partners, acted on as the calling staff member.
 */
@Component
@RequiredArgsConstructor
public class DefaultStaffDesignPartnerService implements StaffDesignPartnerService {

    private final SecurityContext securityContext;
    private final DesignPartnerService designPartnerService;

    @Override
    public Future<List<DesignPartnerApplication>> findPendingApplications() {
        return designPartnerService.findPending();
    }

    @Override
    public Future<DesignPartner> approveApplication(String applicationId) {
        return designPartnerService.approve(applicationId, staff());
    }

    @Override
    public Future<DesignPartnerApplication> rejectApplication(String applicationId) {
        return designPartnerService.reject(applicationId, staff());
    }

    @Override
    public Future<List<DesignPartner>> findDesignPartners() {
        return designPartnerService.findDesignPartners();
    }

    @Override
    public Future<DesignPartner> approvePublication(String publicationId, String approvedBy) {
        return designPartnerService.approvePublication(publicationId, approvedBy, staff());
    }

    @Override
    public Future<DesignPartner> setCaseStudyStatus(String designPartnerId, CaseStudyStatus status) {
        return designPartnerService.setCaseStudyStatus(designPartnerId, status, staff());
    }

    private String staff() {
        return securityContext.requireParticipant(LicenseParticipant.class).getEmail();
    }
}
