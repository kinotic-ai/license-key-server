package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * What a customer sees of itself: the customer, its contacts, and its licenses with their keys.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class CustomerAccount {
    private Customer customer;
    private List<CustomerContact> contacts;
    private List<LicenseWithKeys> licenses;
    /** The customer's free license requests, newest first; empty for a paying customer. */
    private List<FreeLicenseRequest> freeLicenseRequests;
    /** The customer's design partner applications, newest first. */
    private List<DesignPartnerApplication> designPartnerApplications;
    /** The customer as a design partner, with when its discount ends; {@code null} when it is none. */
    private DesignPartner designPartner;
    /** Whether the customer may start a trial of a paid plan. */
    private boolean trialAvailable;
}
