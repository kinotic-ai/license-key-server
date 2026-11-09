package org.kinotic.licenses.api.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * The Postgres database, bound under {@code kinotic.licenses.database}. Flyway migrates it over JDBC at startup
 * and the server queries it through the non-blocking Vert.x client, both from these settings.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class DatabaseProperties {

    private String host = "localhost";

    private int port = 5432;

    private String name = "licenses";

    private String user = "licenses";

    private String password;

    /**
     * The most connections the server holds open to the database.
     */
    private int maxPoolSize = 8;

    /**
     * The JDBC URL Flyway migrates with, derived from the host, port and database name.
     */
    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + name;
    }
}
