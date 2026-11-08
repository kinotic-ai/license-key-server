package org.kinotic.licenses.support;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.springframework.boot.test.system.CapturedOutput;
import java.util.List;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import org.kinotic.licenses.api.model.ContactRole;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Boots the whole server once for the suite: Ignite, the gateway on the test ports, and the schema migrated into the
 * suite's database. Tests call the server over HTTP as the browser would and read the database through the
 * repositories.
 */
@SpringBootTest
@ActiveProfiles("test")
// an extension, unlike @EnabledIf, is inherited, so every server test class is disabled without a database
@ExtendWith(DatabaseAvailableCondition.class)
public abstract class ServerTest {

    /** The gateway's port in the test profile. */
    public static final int PORT = 58603;
    /** The public site's origin in the test profile, which browser requests carry. */
    public static final String PUBLIC_ORIGIN = "http://localhost:5173";

    private static Vertx clientVertx;
    protected static WebClient client;

    private static final Pattern CODE_IN_LOG = Pattern.compile("Your Kinotic sign-in code is:\\s+(\\d{8})");

    @Autowired
    protected Pool pool;
    @Autowired
    protected CustomerRepository customerRepository;
    @Autowired
    protected CustomerContactRepository contactRepository;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase database = TestDatabase.get();
        registry.add("kinotic.licenses.database.host", database::host);
        registry.add("kinotic.licenses.database.port", database::port);
        registry.add("kinotic.licenses.database.name", database::name);
        registry.add("kinotic.licenses.database.user", database::user);
        registry.add("kinotic.licenses.database.password", database::password);
    }

    @BeforeAll
    static void startClient() {
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx, new WebClientOptions().setDefaultHost("localhost").setDefaultPort(PORT));
    }

    @AfterAll
    static void stopClient() {
        client.close();
        clientVertx.close();
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** A JSON POST from the public site's pages, with the session cookie when one is given. */
    protected static HttpResponse<Buffer> post(String path, JsonObject body, String cookie) throws Exception {
        HttpRequest<Buffer> request = client.post(path).putHeader("Origin", PUBLIC_ORIGIN).putHeader("Content-Type", "application/json");
        if (cookie != null) {
            request.putHeader("Cookie", cookie);
        }
        return await(request.sendBuffer(body.toBuffer()));
    }

    protected static HttpResponse<Buffer> get(String path, String cookie) throws Exception {
        HttpRequest<Buffer> request = client.get(path).putHeader("Origin", PUBLIC_ORIGIN);
        if (cookie != null) {
            request.putHeader("Cookie", cookie);
        }
        return await(request.send());
    }

    /** Runs a statement against the suite's database, for arranging state no public interface sets, such as a past date. */
    protected void sql(String statement, Object... values) throws Exception {
        await(pool.preparedQuery(statement).execute(Tuple.from(values)));
    }

    /**
     * Signs the address, which has an account, in with the code the server emails it, and returns the session cookie.
     */
    protected static String signIn(CapturedOutput output, String email) throws Exception {
        return enterCode(output, email, true);
    }

    /**
     * Confirms the address, which has no account, with the code the server emails it, and returns the session cookie
     * that lets the page sign up with it.
     */
    protected static String confirmEmail(CapturedOutput output, String email) throws Exception {
        return enterCode(output, email, false);
    }

    // the code is read from the log, where the test profile writes every email
    private static String enterCode(CapturedOutput output, String email, boolean account) throws Exception {
        int before = codesIn(output).size();
        assertThat(post("/api/auth/code/request", new JsonObject().put("email", email), null).statusCode()).isEqualTo(202);
        String code = null;
        for (int i = 0; i < 100 && code == null; i++) {
            List<String> codes = codesIn(output);
            if (codes.size() > before) {
                code = codes.getLast();
            } else {
                Thread.sleep(100);
            }
        }
        assertThat(code).as("the sign-in code was logged").isNotNull();
        HttpResponse<Buffer> verified = post("/api/auth/code/verify", new JsonObject().put("email", email).put("code", code), null);
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(verified.bodyAsJsonObject().getBoolean("account")).isEqualTo(account);
        return verified.cookies().getFirst().split(";")[0];
    }

    /** The sign-in codes the test profile has logged so far, oldest first. */
    protected static List<String> codesIn(CapturedOutput output) {
        return CODE_IN_LOG.matcher(output.getAll()).results().map(m -> m.group(1)).toList();
    }

    /** A customer with one billing contact whose email is unique to the test. */
    protected Customer seedCustomer() throws Exception {
        Customer customer = await(customerRepository.save(new Customer().setId(UUID.randomUUID().toString()).setLegalName("Acme Robotics Inc")));
        await(contactRepository.save(new CustomerContact().setId(UUID.randomUUID().toString()).setCustomerId(customer.getId())
                                                          .setName("Ada Lovelace").setEmail("ada-" + customer.getId() + "@acme.example")
                                                          .setRole(ContactRole.BILLING)));
        return customer;
    }
}
