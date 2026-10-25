package org.kinotic.licenses.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the whole server once for the suite: Ignite, the gateway on the test ports, and the schema migrated into the
 * suite's database. Tests call the server over HTTP and read the database through the repositories.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class ServerTest {

    /** The gateway's port in the test profile. */
    public static final int PORT = 58603;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase database = TestDatabase.get();
        registry.add("kinotic.licenses.database.host", database::host);
        registry.add("kinotic.licenses.database.port", database::port);
        registry.add("kinotic.licenses.database.name", database::name);
        registry.add("kinotic.licenses.database.user", database::user);
        registry.add("kinotic.licenses.database.password", database::password);
    }
}
