package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.kinotic.licenses.api.model.AgreementAcceptance;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.ContactRole;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Brings a new customer on: the legal entity, its billing contact, and the contact's acceptance of the agreement in
 * force, recorded before anything is bought or requested.
 */
@Component
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final AgreementAcceptanceRepository acceptanceRepository;

    /**
     * Creates the customer with its billing contact and records that the contact accepted the current terms.
     *
     * @param legalName    the customer's legal name
     * @param contactName  the billing contact's name, who accepted the terms
     * @param contactEmail the billing contact's email
     * @return the customer
     */
    public Future<Customer> createCustomer(String legalName, String contactName, String contactEmail) {
        Validate.notBlank(legalName, "The legal name is required");
        Validate.notBlank(contactName, "The contact's name is required");
        Validate.isTrue(contactEmail != null && contactEmail.contains("@"), "A valid contact email is required");
        Customer customer = new Customer().setId(UUID.randomUUID().toString()).setLegalName(legalName.trim());
        CustomerContact contact = new CustomerContact()
                .setId(UUID.randomUUID().toString())
                .setCustomerId(customer.getId())
                .setName(contactName.trim())
                .setEmail(contactEmail.trim())
                .setRole(ContactRole.BILLING);
        AgreementAcceptance acceptance = new AgreementAcceptance()
                .setId(UUID.randomUUID().toString())
                .setCustomerId(customer.getId())
                .setTermsVersion(AgreementTerms.VERSION)
                .setAcceptedByName(contact.getName())
                .setAcceptedByEmail(contact.getEmail());
        return customerRepository.save(customer)
                                 .compose(saved -> contactRepository.save(contact))
                                 .compose(savedContact -> acceptanceRepository.save(acceptance))
                                 .compose(savedAcceptance -> customerRepository.findById(customer.getId()));
    }
}
