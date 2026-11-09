package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A person at a customer. Contacts receive the keys and sign in to the customer account.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class CustomerContact {
    private String id;
    private String customerId;
    private String name;
    private String email;
    private ContactRole role;
}
