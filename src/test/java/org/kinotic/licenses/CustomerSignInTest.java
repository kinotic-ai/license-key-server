package org.kinotic.licenses;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.internal.api.model.SignInCode;
import org.kinotic.licenses.internal.api.repositories.SignInCodeRepository;
import org.kinotic.licenses.support.ServerTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    @Autowired
    private SignInCodeRepository codeRepository;

    @Test
    void aContactSignsInWithTheEmailedCodeOnce(CapturedOutput output) throws Exception {
        Customer customer = seedCustomer();
        String email = await(contactRepository.findByCustomerId(customer.getId())).getFirst().getEmail();

        HttpResponse<Buffer> requested = post("/api/auth/code/request", new JsonObject().put("email", email), null);
        assertThat(requested.statusCode()).isEqualTo(202);
        String code = awaitCode(output);

        HttpResponse<Buffer> wrongCode = post("/api/auth/code/verify", new JsonObject().put("email", email).put("code", "00000000"), null);
        assertThat(wrongCode.statusCode()).isEqualTo(401);

        HttpResponse<Buffer> verified = post("/api/auth/code/verify", new JsonObject().put("email", email).put("code", code), null);
        assertThat(verified.statusCode()).isEqualTo(204);
        String cookie = verified.cookies().getFirst();
        assertThat(cookie).startsWith("__Host-kinotic-licenses-session=");

        HttpResponse<Buffer> me = get("/api/auth/me", cookie);
        assertThat(me.statusCode()).isEqualTo(204);

        HttpResponse<Buffer> reused = post("/api/auth/code/verify", new JsonObject().put("email", email).put("code", code), null);
        assertThat(reused.statusCode()).isEqualTo(401);

        HttpResponse<Buffer> loggedOut = post("/api/auth/logout", new JsonObject(), cookie);
        assertThat(loggedOut.statusCode()).isEqualTo(204);
        HttpResponse<Buffer> meAfter = get("/api/auth/me", cookie);
        assertThat(meAfter.statusCode()).isEqualTo(401);
    }

    @Test
    void theAddressLimitKeysOnTheAddressTheProxyAppended() throws Exception {
        String proxied = "203.0.113." + (1 + new java.util.Random().nextInt(250));
        int accepted = 0;
        int limited = 0;
        for (int i = 0; i < 35; i++) {
            // a client that writes a different address of its own to the left each time is still one client
            HttpResponse<Buffer> response = await(client.post("/api/auth/code/request")
                                                        .putHeader("Origin", PUBLIC_ORIGIN)
                                                        .putHeader("Content-Type", "application/json")
                                                        .putHeader("X-Forwarded-For", "198.51.100." + i + ", " + proxied)
                                                        .sendBuffer(new JsonObject().put("email", "nobody-" + UUID.randomUUID() + "@example.com").toBuffer()));
            if (response.statusCode() == 202) {
                accepted++;
            } else if (response.statusCode() == 429) {
                limited++;
            }
        }
        assertThat(accepted).isEqualTo(30);
        assertThat(limited).isEqualTo(5);
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
        // the only usable codes are the unexpired ones, so a code stored as already expired never matches
        SignInCode expired = new SignInCode().setId(UUID.randomUUID().toString()).setEmail(email)
                                             .setCodeHash("irrelevant").setExpiresAt(Instant.now().minusSeconds(1));
        await(codeRepository.save(expired));
        assertThat(await(codeRepository.findUsable(email, Instant.now()))).isEmpty();
    }

    @Test
    void theStaffRoutesDoNotAnswerOnThePublicHost() throws Exception {
        HttpResponse<Buffer> onPublicHost = await(client.get("/api/auth/staff/login").putHeader("Host", "localhost:5173").send());
        assertThat(onPublicHost.statusCode()).isEqualTo(404);
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
