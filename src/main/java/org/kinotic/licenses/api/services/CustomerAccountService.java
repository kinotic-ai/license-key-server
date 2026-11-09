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
import org.kinotic.licenses.api.model.PlanPrice;
import org.kinotic.licenses.api.model.RevenueConfirmation;
import org.kinotic.licenses.api.model.CheckoutRequest;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.security.LicenseZones;

import java.util.List;

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
     * The prices the calling customer buys at, for every paid plan and billing period: those of the price list its
     * active free license keeps, or of the current price list.
     */
    Future<List<PlanPrice>> findPrices();

    /**
     * The plan a company with these figures is on.
     *
     * @throws IllegalArgumentException when the annual revenue is missing or negative, or the capital raised is when the
     *                                  revenue is under $1M
     */
    Future<Plan> findPlanFor(CompanyFigures figures);

    /**
     * Starts the purchase of a paid plan by the calling customer, and records that the calling contact accepted the
     * terms. A customer holding an active free license moves it onto the plan bought, with the price list and terms it
     * keeps.
     *
     * @param request      the plan, billing period and the company's figures, which must match the plan
     * @param termsVersion the version of the terms the contact accepted, which must be the current version
     * @return the Stripe Checkout URL to send the browser to
     * @throws IllegalArgumentException when the plan is the free plan, the figures set another plan, or the terms
     *                                  version is not the current one
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when the customer holds a paid subscription; its
     *                                                                plan moves at its yearly confirmation
     */
    Future<String> startCheckout(CheckoutRequest request, String termsVersion);

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
     * Confirms the calling customer's figures for the anniversary of its license they are due for (see
     * {@link org.kinotic.licenses.api.model.LicenseWithKeys#getConfirmationDueFor()}). Figures that set another plan
     * move the license at its next anniversary; a free license's figures that still set Free ask staff to approve its
     * next year.
     *
     * @param licenseId the license
     * @param figures   the annual revenue, and for a free license the capital raised
     * @return the confirmation
     * @throws IllegalArgumentException when the figures are not valid or the customer has no license with the id
     * @throws IllegalStateException    when no confirmation is due
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when the figures are confirmed already
     */
    Future<RevenueConfirmation> confirmRevenue(String licenseId, CompanyFigures figures);

    /**
     * Requests a free license for the calling customer with the figures it certifies, and records that the calling
     * contact accepted the terms. Staff approve or reject the request. A free license renews with its yearly
     * confirmation.
     *
     * @param certification the figures the customer certifies
     * @param termsVersion  the version of the terms the contact accepted, which must be the current version
     * @return the pending request
     * @throws IllegalArgumentException when a figure is missing or negative, or the terms version is not the current one
     * @throws IllegalStateException    when the customer holds an active free license
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when another request of the customer's is open
     */
    Future<FreeLicenseRequest> requestFreeLicense(FreeLicenseCertification certification, String termsVersion);
}
