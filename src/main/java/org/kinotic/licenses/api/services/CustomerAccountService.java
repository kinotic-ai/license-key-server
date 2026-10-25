package org.kinotic.licenses.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.licenses.api.model.CustomerAccount;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.security.LicenseZones;

/**
 * The customer account: everything answers for the calling customer only.
 */
@Publish
@Version("1.0.0")
@Zone(LicenseZones.CUSTOMER)
public interface CustomerAccountService {

    /**
     * The calling customer's account: the customer, its contacts, and its licenses with every key issued.
     */
    Future<CustomerAccount> getAccount();

    /**
     * Opens a Stripe Customer Portal session for the calling customer, where it updates payment details and sees
     * receipts.
     *
     * @return the portal URL to send the browser to
     * @throws IllegalStateException when the customer has never bought a plan and so has no Stripe customer
     */
    Future<String> createPortalSession();

    /**
     * Requests the renewal of the calling customer's free license by re-certifying that it still qualifies. Staff
     * review the request as they did the first.
     *
     * @param certification the figures the customer certifies
     * @return the pending request
     * @throws IllegalStateException when the customer has no free license
     */
    Future<FreeLicenseRequest> requestFreeRenewal(FreeLicenseCertification certification);
}
