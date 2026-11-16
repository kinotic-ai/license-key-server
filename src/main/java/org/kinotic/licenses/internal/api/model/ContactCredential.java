package org.kinotic.licenses.internal.api.model;

import org.kinotic.licenses.api.model.CustomerContact;

/**
 * A customer contact with the hash of its password.
 *
 * @param contact      the contact
 * @param passwordHash the bcrypt hash of its password, or {@code null} when it has set none
 */
public record ContactCredential(CustomerContact contact, String passwordHash) {
}
