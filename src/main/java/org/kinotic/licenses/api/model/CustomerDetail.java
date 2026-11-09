package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * Everything staff see of a customer: the customer, its contacts, its licenses with their keys, and the agreement
 * acceptances it made.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class CustomerDetail {
    private Customer customer;
    private List<CustomerContact> contacts;
    private List<LicenseWithKeys> licenses;
    private List<AgreementAcceptance> acceptances;
}
