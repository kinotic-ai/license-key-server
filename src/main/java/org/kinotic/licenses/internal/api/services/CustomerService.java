package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
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
     * Creates the customer with its billing contact and records that the contact accepted the current terms. An email
     * address belongs to one customer: a sign-up with an address that already has an account is refused, and the
     * person signs in to that account instead.
     *
     * @param legalName    the customer's legal name
     * @param contactName  the billing contact's name, who accepted the terms
     * @param contactEmail the billing contact's email
     * @return the customer, or a failure with {@link AlreadyExistsException} when the address already has an account
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
        // checked before anything is written, so a refused sign-up leaves no customer behind; the unique index on the
        // contact's email refuses the one that loses a race past this check
        return contactRepository.findByEmail(contact.getEmail())
                                .compose(existing -> existing.isEmpty()
                                        ? customerRepository.save(customer)
                                        : Future.failedFuture(new AlreadyExistsException(CustomerContactRepository.EMAIL_TAKEN)))
                                 .compose(saved -> contactRepository.save(contact))
                                 .compose(savedContact -> acceptanceRepository.save(acceptance))
                                 .compose(savedAcceptance -> customerRepository.findById(customer.getId()));
    }
}
