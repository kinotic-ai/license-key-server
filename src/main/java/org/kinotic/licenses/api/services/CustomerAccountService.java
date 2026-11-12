package org.kinotic.licenses.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.licenses.api.model.CustomerAccount;
import org.kinotic.licenses.api.model.DesignPartnerApplication;
import org.kinotic.licenses.api.model.DesignPartnerApplicationForm;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.PlanPrice;
import org.kinotic.licenses.api.model.RevenueConfirmation;
import org.kinotic.licenses.api.model.CheckoutRequest;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.TrialRequest;
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
     * The calling customer's account: the customer, its contacts, and its licenses with their clusters and every key
     * issued for them.
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
     * Starts the purchase of a paid plan, for a number of clusters each billed at its price, by the calling customer, and
     * records that the calling contact accepted the terms. A customer holding an active free license or a trial moves it onto the plan bought, with the price list and
     * terms it keeps.
     *
     * @param request      the plan, billing period, number of clusters and the company's figures, which must match the
     *                     plan
     * @param termsVersion the version of the terms the contact accepted, which must be the current version
     * @return the Stripe Checkout URL to send the browser to
     * @throws IllegalArgumentException when the plan is the free plan, the figures set another plan, or the terms
     *                                  version is not the current one
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when the customer holds a paid subscription; its
     *                                                                plan moves at its yearly confirmation
     */
    Future<String> startCheckout(CheckoutRequest request, String termsVersion);

    /**
     * Starts a three-month trial of a paid plan, for one cluster, for the calling customer, with no payment details,
     * tells the customer's contacts, and records that the calling contact accepted the terms. A customer has one trial, and only
     * while it has never held a paid license and holds no active free license. Buying a plan before the trial ends moves
     * the trial onto it, with the price list and terms the trial keeps; otherwise the trial expires.
     *
     * @param request      the plan and the company's figures, which must match the plan
     * @param termsVersion the version of the terms the contact accepted, which must be the current version
     * @return the trial, which the account adds the cluster to
     * @throws IllegalArgumentException when the plan is the free plan, the figures set another plan, or the terms
     *                                  version is not the current one
     * @throws IllegalStateException    when the customer has had a trial or a paid license, or holds an active free license
     */
    Future<License> startTrial(TrialRequest request, String termsVersion);

    /**
     * Adds a cluster to one of the calling customer's licenses, taking one of its seats, and issues the cluster's first
     * key, which works on that cluster alone. The cluster then renews its key from this server by itself, or, where it
     * cannot reach this server, gets each new key from the account.
     *
     * @param licenseId the license, which must be active or a trial
     * @param clusterId the cluster's id, which the system console's License page shows: the UID of its kube-system
     *                  namespace
     * @param name      a name for the cluster, such as production
     * @return the cluster, whose key the account shows
     * @throws IllegalArgumentException when the customer has no license with the id, or the cluster id or name is not
     *                                  valid
     * @throws IllegalStateException    when the license is not active or a trial, or every seat is taken
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when the cluster holds a seat of a license already
     */
    Future<LicensedCluster> addCluster(String licenseId, String clusterId, String name);

    /**
     * Applies for the calling customer to be a design partner, on the paid plan its figures set, and records that the
     * calling contact accepted the terms with the design partner publicity they describe. Staff approve or reject it.
     *
     * @param form         the figures, billing period and what the customer uses Kinotic for
     * @param termsVersion the version of the terms the contact accepted, which must be the current version
     * @return the pending application
     * @throws IllegalArgumentException when the form is not valid, the figures set Free, or the terms version is not
     *                                  the current one
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when another application of the customer's is
     *                                                                pending
     */
    Future<DesignPartnerApplication> applyAsDesignPartner(DesignPartnerApplicationForm form, String termsVersion);

    /**
     * Starts the purchase of the calling customer's approved design partner application: its plan and billing period
     * at half off for 36 months, for one cluster; more are added from the Customer Portal.
     *
     * @return the Stripe Checkout URL to send the browser to
     * @throws IllegalArgumentException when the customer has no approved application with the id
     * @throws IllegalStateException    when the discounted subscription is bought already
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when the customer holds another paid subscription
     */
    Future<String> startDesignPartnerCheckout(String applicationId);

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
     * Requests a free license for the calling customer with the figures it certifies, which must set the Free plan, and
     * records that the calling contact accepted the terms, with the Free terms' consent to name the customer as a
     * Kinotic customer. Staff approve or reject the request. A free license renews with its yearly
     * confirmation.
     *
     * @param certification the figures the customer certifies
     * @param termsVersion  the version of the terms the contact accepted, which must be the current version
     * @return the pending request
     * @throws IllegalArgumentException when a figure is missing or negative, the figures set a paid plan, or the terms
     *                                  version is not the current one
     * @throws IllegalStateException    when the customer holds an active free license
     * @throws org.kinotic.core.api.exceptions.AlreadyExistsException when another request of the customer's is open
     */
    Future<FreeLicenseRequest> requestFreeLicense(FreeLicenseCertification certification, String termsVersion);
}
