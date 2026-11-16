package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.ContactRole;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.internal.api.model.ContactCredential;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Stores customer contacts.
 */
@Repository
@RequiredArgsConstructor
public class CustomerContactRepository {

    /** Answers a sign-up whose address already belongs to an account. */
    public static final String EMAIL_TAKEN = "This email already belongs to a Kinotic license account. Sign in to manage it.";

    private static final String COLUMNS = "cc.id, cc.customer_id, cc.name, cc.email, cc.role";
    private static final String UNIQUE_VIOLATION = "23505";

    private final Pool pool;

    /**
     * Stores the contact with the hash of its password.
     *
     * @param passwordHash the bcrypt hash of the contact's password, or {@code null} for none
     * @return the contact, or a failure with {@link AlreadyExistsException} when another contact has its email address
     */
    public Future<CustomerContact> save(CustomerContact contact, String passwordHash) {
        return pool.preparedQuery("INSERT INTO customer_contact (id, customer_id, name, email, role, password_hash) VALUES ($1, $2, $3, $4, $5, $6)")
                   .execute(Tuple.of(contact.getId(), contact.getCustomerId(), contact.getName(), contact.getEmail(),
                                     PgValues.name(contact.getRole()), passwordHash))
                   .map(rows -> contact)
                   .recover(t -> Future.failedFuture(t instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState())
                                                             ? new AlreadyExistsException(EMAIL_TAKEN, t)
                                                             : t));
    }

    /**
     * The contact with the email address, as a list of at most one.
     */
    public Future<List<CustomerContact>> findByEmail(String email) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM customer_contact cc JOIN customer c ON c.id = cc.customer_id"
                                          + " WHERE lower(cc.email) = lower($1) ORDER BY c.created_at DESC")
                   .execute(Tuple.of(email))
                   .map(rows -> PgValues.map(rows, CustomerContactRepository::toContact));
    }

    /**
     * The contact with the email address and its password hash, or {@code null} when no contact has the address.
     */
    public Future<ContactCredential> findCredentialByEmail(String email) {
        return pool.preparedQuery("SELECT " + COLUMNS + ", cc.password_hash FROM customer_contact cc WHERE lower(cc.email) = lower($1)")
                   .execute(Tuple.of(email))
                   .map(rows -> PgValues.first(rows, row -> new ContactCredential(toContact(row), row.getString("password_hash"))));
    }

    /**
     * Sets the password hash of the contact with the email address.
     *
     * @return the contact, or {@code null} when no contact has the address
     */
    public Future<CustomerContact> updatePasswordHash(String email, String passwordHash) {
        return pool.preparedQuery("UPDATE customer_contact cc SET password_hash = $2 WHERE lower(cc.email) = lower($1) RETURNING " + COLUMNS)
                   .execute(Tuple.of(email, passwordHash))
                   .map(rows -> PgValues.first(rows, CustomerContactRepository::toContact));
    }

    public Future<List<CustomerContact>> findByCustomerId(String customerId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM customer_contact cc WHERE cc.customer_id = $1 ORDER BY cc.role, cc.name")
                   .execute(Tuple.of(customerId))
                   .map(rows -> PgValues.map(rows, CustomerContactRepository::toContact));
    }

    private static CustomerContact toContact(Row row) {
        return new CustomerContact()
                .setId(row.getString("id"))
                .setCustomerId(row.getString("customer_id"))
                .setName(row.getString("name"))
                .setEmail(row.getString("email"))
                .setRole(PgValues.enumValue(row, "role", ContactRole.class));
    }
}
