package org.kinotic.licenses.internal.api.repositories;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.ContactRole;
import org.kinotic.licenses.api.model.CustomerContact;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Stores customer contacts.
 */
@Repository
@RequiredArgsConstructor
public class CustomerContactRepository {

    private static final String COLUMNS = "cc.id, cc.customer_id, cc.name, cc.email, cc.role";

    private final Pool pool;

    public Future<CustomerContact> save(CustomerContact contact) {
        return pool.preparedQuery("INSERT INTO customer_contact (id, customer_id, name, email, role) VALUES ($1, $2, $3, $4, $5)")
                   .execute(Tuple.of(contact.getId(), contact.getCustomerId(), contact.getName(), contact.getEmail(),
                                     PgValues.name(contact.getRole())))
                   .map(rows -> contact);
    }

    /**
     * The contacts with the email address, the contact of the newest customer first.
     */
    public Future<List<CustomerContact>> findByEmail(String email) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM customer_contact cc JOIN customer c ON c.id = cc.customer_id"
                                          + " WHERE lower(cc.email) = lower($1) ORDER BY c.created_at DESC")
                   .execute(Tuple.of(email))
                   .map(rows -> PgValues.map(rows, CustomerContactRepository::toContact));
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
