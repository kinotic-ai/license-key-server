package org.kinotic.licenses;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kinotic.licenses.api.model.ContactRole;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.internal.api.model.SignInCode;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.SignInCodeRepository;
import org.kinotic.licenses.support.ServerTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Customers sign in with the code emailed to a contact: the code is used once, expires, and the response to a request
 * never says whether the address has an account.
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerSignInTest extends ServerTest {

    private static final Pattern CODE_IN_LOG = Pattern.compile("Your Kinotic sign-in code is:\\s+(\\d{8})");
    private static final String ORIGIN = "http://localhost:5173";

    private static Vertx vertx;
    private static WebClient client;

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private CustomerContactRepository contactRepository;
    @Autowired
    private SignInCodeRepository codeRepository;

    @BeforeAll
    static void startClient() {
        vertx = Vertx.vertx();
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost("localhost").setDefaultPort(PORT));
    }

    @AfterAll
    static void stopClient() {
        client.close();
        vertx.close();
    }

    @Test
    void aContactSignsInWithTheEmailedCodeOnce(CapturedOutput output) throws Exception {
        String email = "ada-" + UUID.randomUUID() + "@acme.example";
        seedContact(email);

        HttpResponse<Buffer> requested = post("/api/auth/code/request", new JsonObject().put("email", email), null);
        assertThat(requested.statusCode()).isEqualTo(202);
        String code = awaitCode(output);

        HttpResponse<Buffer> wrongCode = post("/api/auth/code/verify", new JsonObject().put("email", email).put("code", "00000000"), null);
        assertThat(wrongCode.statusCode()).isEqualTo(401);

        HttpResponse<Buffer> verified = post("/api/auth/code/verify", new JsonObject().put("email", email).put("code", code), null);
        assertThat(verified.statusCode()).isEqualTo(204);
        String cookie = verified.cookies().getFirst();
        assertThat(cookie).startsWith("__Host-kinotic-licenses-session=");

        HttpResponse<Buffer> me = client.get("/api/auth/me").putHeader("Origin", ORIGIN).putHeader("Cookie", cookie)
                                        .send().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(me.statusCode()).isEqualTo(204);

        HttpResponse<Buffer> reused = post("/api/auth/code/verify", new JsonObject().put("email", email).put("code", code), null);
        assertThat(reused.statusCode()).isEqualTo(401);

        HttpResponse<Buffer> loggedOut = post("/api/auth/logout", new JsonObject(), cookie);
        assertThat(loggedOut.statusCode()).isEqualTo(204);
        HttpResponse<Buffer> meAfter = client.get("/api/auth/me").putHeader("Origin", ORIGIN).putHeader("Cookie", cookie)
                                             .send().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(meAfter.statusCode()).isEqualTo(401);
    }

    @Test
    void aRequestForAnUnknownAddressLooksLikeAnyOther() throws Exception {
        HttpResponse<Buffer> requested = post("/api/auth/code/request",
                                              new JsonObject().put("email", "nobody-" + UUID.randomUUID() + "@example.com"), null);
        assertThat(requested.statusCode()).isEqualTo(202);
        assertThat(requested.bodyAsString()).isNullOrEmpty();
    }

    @Test
    void anExpiredCodeIsRefused() throws Exception {
        String email = "late-" + UUID.randomUUID() + "@acme.example";
        seedContact(email);
        // the only usable codes are the unexpired ones, so a code stored as already expired never matches
        SignInCode expired = new SignInCode().setId(UUID.randomUUID().toString()).setEmail(email)
                                             .setCodeHash("irrelevant").setExpiresAt(Instant.now().minusSeconds(1));
        codeRepository.save(expired).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(codeRepository.findUsable(email, Instant.now()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS))
                .isEmpty();
    }

    @Test
    void theStaffRoutesDoNotAnswerOnThePublicHost() throws Exception {
        HttpResponse<Buffer> onPublicHost = client.get("/api/auth/staff/login").putHeader("Host", "localhost:5173")
                                                  .send().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThat(onPublicHost.statusCode()).isEqualTo(404);
    }

    private void seedContact(String email) throws Exception {
        Customer customer = new Customer().setId(UUID.randomUUID().toString()).setLegalName("Acme Robotics Inc");
        customerRepository.save(customer).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        CustomerContact contact = new CustomerContact().setId(UUID.randomUUID().toString()).setCustomerId(customer.getId())
                                                       .setName("Ada Lovelace").setEmail(email).setRole(ContactRole.BILLING);
        contactRepository.save(contact).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static HttpResponse<Buffer> post(String path, JsonObject body, String cookie) throws Exception {
        var request = client.post(path).putHeader("Origin", ORIGIN).putHeader("Content-Type", "application/json");
        if (cookie != null) {
            request.putHeader("Cookie", cookie);
        }
        return request.sendBuffer(body.toBuffer()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // the email is logged, not sent, in the test profile
    private static String awaitCode(CapturedOutput output) throws InterruptedException {
        String ret = null;
        for (int i = 0; i < 100 && ret == null; i++) {
            List<String> codes = CODE_IN_LOG.matcher(output.getAll()).results().map(m -> m.group(1)).toList();
            if (!codes.isEmpty()) {
                ret = codes.getLast();
            } else {
                Thread.sleep(100);
            }
        }
        assertThat(ret).as("the sign-in code was logged").isNotNull();
        return ret;
    }
}
