package org.kinotic.licenses.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.security.LicenseZones;

import java.util.List;

/**
 * The staff queue of free license requests.
 */
@Publish
@Version("1.0.0")
@Zone(LicenseZones.STAFF)
public interface StaffFreeLicenseRequestService {

    /**
     * The requests awaiting review, oldest first.
     */
    Future<List<FreeLicenseRequest>> findPending();

    /**
     * Approves a request: a first request creates the free license and issues its key, a renewal request starts the
     * license's next term and issues that term's key; either emails the key.
     *
     * @throws IllegalArgumentException when no request has the id
     * @throws IllegalStateException    when the request is not pending
     */
    Future<FreeLicenseRequest> approve(String requestId);

    /**
     * Rejects a request and tells the contact.
     *
     * @throws IllegalArgumentException when no request has the id
     * @throws IllegalStateException    when the request is not pending
     */
    Future<FreeLicenseRequest> reject(String requestId);
}
