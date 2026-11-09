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
import org.kinotic.licenses.api.model.PublicityConsent;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Brings a new customer on, the legal entity with its billing contact, and records each acceptance of the agreement in
 * force by one of its contacts, which every purchase and free license request makes.
 */
@Component
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final AgreementAcceptanceRepository acceptanceRepository;

    /**
     * Creates the customer with its billing contact. An email address belongs to one customer: a sign-up with an
     * address that already has an account is refused, and the person signs in to that account instead.
     *
     * @param legalName    the customer's legal name
     * @param contactName  the billing contact's name
     * @param contactEmail the billing contact's email, which the caller has confirmed belongs to the person signing up
     * @return the billing contact, or a failure with {@link AlreadyExistsException} when the address already has an
     *         account
     */
    public Future<CustomerContact> createCustomer(String legalName, String contactName, String contactEmail) {
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
        // checked before anything is written, so a refused sign-up leaves no customer behind; the unique index on the
        // contact's email refuses the one that loses a race past this check
        return contactRepository.findByEmail(contact.getEmail())
                                .compose(existing -> existing.isEmpty()
                                        ? customerRepository.save(customer)
                                        : Future.failedFuture(new AlreadyExistsException(CustomerContactRepository.EMAIL_TAKEN)))
                                 .compose(saved -> contactRepository.save(contact)
                                                                    // a contact that could not be saved, as by losing a race past the check
                                                                    // above, leaves no customer behind; the save's failure is the one reported
                                                                    .recover(t -> customerRepository.delete(customer.getId())
                                                                                                    .transform(deleted -> Future.<CustomerContact>failedFuture(t))));
    }

    /**
     * Sends an email to each contact of the customer, one after another; the first that fails fails the result.
     *
     * @param send sends to one contact, given the contact and the customer's legal name
     */
    public Future<Void> emailContacts(String customerId, BiFunction<CustomerContact, String, Future<Void>> send) {
        return customerRepository.findById(customerId)
                                 .compose(customer -> contactRepository.findByCustomerId(customerId).compose(contacts -> {
                                     Future<Void> ret = Future.succeededFuture();
                                     for (CustomerContact contact : contacts) {
                                         ret = ret.compose(v -> send.apply(contact, customer.getLegalName()));
                                     }
                                     return ret;
                                 }));
    }

    /**
     * Records that a contact of the customer accepted the current terms, as with every purchase, free license request
     * and design partner application, with the publicity the terms ask for.
     *
     * @param publicityConsent what the customer lets Minds Ignited publish about it, or {@code null} for nothing
     */
    public Future<AgreementAcceptance> recordAcceptance(String customerId, String acceptedByName, String acceptedByEmail,
                                                        PublicityConsent publicityConsent) {
        return acceptanceRepository.save(new AgreementAcceptance()
                                                 .setId(UUID.randomUUID().toString())
                                                 .setCustomerId(customerId)
                                                 .setTermsVersion(AgreementTerms.VERSION)
                                                 .setAcceptedByName(acceptedByName)
                                                 .setAcceptedByEmail(acceptedByEmail)
                                                 .setPublicityConsent(publicityConsent));
    }
}
