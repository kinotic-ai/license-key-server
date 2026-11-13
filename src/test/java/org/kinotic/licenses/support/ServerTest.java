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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.ContactRole;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.security.ParticipantType;
import org.kinotic.licenses.api.services.CustomerAccountService;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.model.PaidSubscription;
import org.kinotic.licenses.internal.api.model.PriceLookupKey;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.rest.StripeWebhookRoutes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Boots the whole server once for the suite: Ignite, the gateway on the test ports, the schema migrated into the
 * suite's database, and Stripe answered by {@link StripeStandIn}. Tests call the server over HTTP as the browser would and read the database through the
 * repositories.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(StripeStandIn.class)
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
    protected SecurityContext securityContext;
    @Autowired
    protected CustomerRepository customerRepository;
    @Autowired
    protected CustomerContactRepository contactRepository;
    @Autowired
    protected CustomerAccountService customerAccount;

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

    /** Delivers a Stripe webhook event as Stripe does, signed with the test webhook secret. */
    protected static HttpResponse<Buffer> deliver(JsonObject event) throws Exception {
        String payload = event.encode();
        return await(client.post(StripeWebhookRoutes.PATH)
                           .putHeader("Stripe-Signature", StripeEvents.signature(payload))
                           .putHeader("Content-Type", "application/json")
                           .sendBuffer(Buffer.buffer(payload)));
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

    /**
     * Signs the address, which has no account, up as the page does: confirms it with the emailed code, creates the
     * account of the organization, and returns the session cookie the account is signed in with.
     */
    protected static String signUp(CapturedOutput output, String email, String organization) throws Exception {
        String confirmed = confirmEmail(output, email);
        HttpResponse<Buffer> signedUp = post("/api/auth/signup", new JsonObject().put("organization", organization)
                                                                                 .put("contactName", "Grace Hopper"), confirmed);
        assertThat(signedUp.statusCode()).isEqualTo(204);
        return signedUp.cookies().getFirst().split(";")[0];
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

    /** Calls a published service as a staff member, as the gateway dispatches an RPC of a signed-in staff page. */
    protected <T> T asStaff(Supplier<Future<T>> call) throws Exception {
        LicenseParticipant staff = LicenseParticipant.builder().id("staff@mindsignited.com").type(ParticipantType.STAFF)
                                                     .email("staff@mindsignited.com").name("Staff").metadata(Map.of()).roles(List.of()).build();
        return callAs(staff, call);
    }

    /** Calls a published service as the contact, as the gateway dispatches an RPC of a page the contact signed in. */
    protected <T> T asCustomer(CustomerContact contact, Supplier<Future<T>> call) throws Exception {
        return callAs(LicenseParticipant.ofContact(contact), call);
    }

    // the services read the caller from the Vert.x context, as they do for an RPC the gateway dispatches
    private <T> T callAs(LicenseParticipant participant, Supplier<Future<T>> call) throws Exception {
        return securityContext.callAs(participant, () -> (CompletionStage<T>) call.get().toCompletionStage()).get();
    }

    /** A monthly one-seat subscription to the plan on the current price list, as a Checkout Session would have bought it. */
    protected static PaidSubscription subscription(Plan plan, String stripeSubscriptionId, String checkoutSessionId, Instant currentPeriodEnd) {
        return new PaidSubscription(new PriceLookupKey(StripeStandIn.CURRENT_PRICE_LIST, plan, BillingPeriod.MONTHLY), 1,
                                    stripeSubscriptionId, checkoutSessionId, currentPeriodEnd);
    }

    /**
     * Adds a cluster with a new id to the license as its customer's first contact does from the account, which issues
     * the cluster its first key.
     */
    protected LicensedCluster addCluster(License license) throws Exception {
        CustomerContact contact = await(contactRepository.findByCustomerId(license.getCustomerId())).getFirst();
        return asCustomer(contact, () -> customerAccount.addCluster(license.getId(), UUID.randomUUID().toString(), "production"));
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
