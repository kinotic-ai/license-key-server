package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.services.StaffFreeLicenseRequestService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The staff queue of free license requests, reviewed as the calling staff member.
 */
@Component
@RequiredArgsConstructor
public class DefaultStaffFreeLicenseRequestService implements StaffFreeLicenseRequestService {

    private final SecurityContext securityContext;
    private final FreeLicenseService freeLicenseService;

    @Override
    public Future<List<FreeLicenseRequest>> findPending() {
        return freeLicenseService.findPending();
    }

    @Override
    public Future<FreeLicenseRequest> approve(String requestId) {
        return freeLicenseService.approve(requestId, staff());
    }

    @Override
    public Future<FreeLicenseRequest> reject(String requestId) {
        return freeLicenseService.reject(requestId, staff());
    }

    private String staff() {
        return securityContext.requireParticipant(LicenseParticipant.class).getEmail();
    }
}
