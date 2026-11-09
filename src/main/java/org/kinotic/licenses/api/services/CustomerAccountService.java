package org.kinotic.licenses.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.licenses.api.model.CheckoutKey;
import org.kinotic.licenses.api.model.CustomerAccount;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.Plan;
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
     * Starts the purchase of a paid plan by the calling customer, and records that the calling contact accepted the
     * terms.
     *
     * @param plan         the plan to buy
     * @param termsVersion the version of the terms the contact accepted, which must be the current version
     * @return the Stripe Checkout URL to send the browser to
     * @throws IllegalArgumentException when the plan is the free plan, or the terms version is not the current one
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when the customer holds a paid subscription; it
     *                                                                changes plans in the Customer Portal
     */
    Future<String> startCheckout(Plan plan, String termsVersion);

    /**
     * What the success page of a purchase by the calling customer is told about the key the purchase bought: the key
     * itself once, within an hour of the license's creation, and otherwise whether it is still pending or was shown.
     *
     * @param checkoutSessionId the Stripe Checkout Session the purchase completed
     * @return the key's status, {@link org.kinotic.licenses.api.model.CheckoutKeyStatus#PENDING} also when the session
     *         bought nothing for the calling customer
     */
    Future<CheckoutKey> findCheckoutKey(String checkoutSessionId);

    /**
     * Requests a free license for the calling customer, or the renewal of the one it holds, with the figures it
     * certifies, and records that the calling contact accepted the terms. Staff approve or reject the request.
     *
     * @param certification the figures the customer certifies
     * @param termsVersion  the version of the terms the contact accepted, which must be the current version
     * @return the pending request
     * @throws IllegalArgumentException when a figure is missing or negative, or the terms version is not the current one
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when another request of the customer's is open
     */
    Future<FreeLicenseRequest> requestFreeLicense(FreeLicenseCertification certification, String termsVersion);
}
