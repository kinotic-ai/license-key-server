package org.kinotic.licenses.support;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;

/**
 * The Postgres the suite runs against: the one {@code LICENSES_TEST_DATABASE_URL} names
 * ({@code postgresql://user:password@host:port/db}), else a Testcontainers container when Docker is available.
 * A suite with neither is skipped.
 */
public final class TestDatabase {

    private static final String URL_VARIABLE = "LICENSES_TEST_DATABASE_URL";

    private static volatile TestDatabase instance;

    private final String host;
    private final int port;
    private final String name;
    private final String user;
    private final String password;

    private TestDatabase(String host, int port, String name, String user, String password) {
        this.host = host;
        this.port = port;
        this.name = name;
        this.user = user;
        this.password = password;
    }

    /**
     * The suite's database, started on first use; skips the calling test when none is available.
     */
    public static TestDatabase get() {
        TestDatabase ret = instance;
        if (ret == null) {
            synchronized (TestDatabase.class) {
                ret = instance;
                if (ret == null) {
                    ret = resolve();
                    instance = ret;
                }
            }
        }
        return ret;
    }

    private static TestDatabase resolve() {
        String url = System.getenv(URL_VARIABLE);
        TestDatabase ret;
        if (url != null && !url.isBlank()) {
            URI uri = URI.create(url);
            String[] userInfo = uri.getUserInfo().split(":", 2);
            ret = new TestDatabase(uri.getHost(), uri.getPort(), uri.getPath().substring(1), userInfo[0],
                                   userInfo.length > 1 ? userInfo[1] : "");
        } else {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                                   "No Postgres: set " + URL_VARIABLE + " or run Docker for Testcontainers");
            PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine");
            // lives for the JVM; Testcontainers' reaper removes it afterwards
            container.start();
            ret = new TestDatabase(container.getHost(), container.getFirstMappedPort(), container.getDatabaseName(),
                                   container.getUsername(), container.getPassword());
        }
        return ret;
    }

    public String host() { return host; }
    public int port() { return port; }
    public String name() { return name; }
    public String user() { return user; }
    public String password() { return password; }
}
