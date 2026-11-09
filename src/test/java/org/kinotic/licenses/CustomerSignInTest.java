package org.kinotic.licenses;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Customers sign in with the code emailed to a contact: the code is used once, expires, and the response to a request
 * never says whether the address has an account. A new customer signs up by confirming its address with a code first.
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerSignInTest extends ServerTest {

    @Autowired
    private SignInCodeRepository codeRepository;
    @Autowired
    private AgreementAcceptanceRepository acceptanceRepository;

    @Test
    void aSignUpCreatesTheAccountOfAConfirmedAddressAndSignsItIn(CapturedOutput output) throws Exception {
        String email = "new-" + UUID.randomUUID() + "@tiny.example";
        // a page that confirmed no address signs nothing up, so it learns nothing about any address
        JsonObject signUp = new JsonObject().put("organization", "Tiny Startup LLC").put("contactName", "Grace Hopper");
        assertThat(post("/api/auth/signup", signUp, null).statusCode()).isEqualTo(401);

        String cookie = signUp(output, email, "Tiny Startup LLC");
        assertThat(get("/api/auth/me", cookie).statusCode()).isEqualTo(204);
        CustomerContact contact = await(contactRepository.findByEmail(email)).getFirst();
        assertThat(contact.getName()).isEqualTo("Grace Hopper");
        assertThat(await(customerRepository.findById(contact.getCustomerId())).getLegalName()).isEqualTo("Tiny Startup LLC");
        // the terms are accepted with each purchase and request, not at sign-up
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();

        // the address's code now signs in to that account, and leaves nothing to sign up with
        String signedIn = signIn(output, email.toUpperCase());
        assertThat(post("/api/auth/signup", signUp, signedIn).statusCode()).isEqualTo(401);
    }

    @Test
    void anAddressConfirmedOnTwoPagesSignsUpOnce(CapturedOutput output) throws Exception {
        String email = "twice-" + UUID.randomUUID() + "@tiny.example";
        String first = confirmEmail(output, email);
        String second = confirmEmail(output, email);
        JsonObject signUp = new JsonObject().put("organization", "Tiny Startup LLC").put("contactName", "Grace Hopper");

        assertThat(post("/api/auth/signup", signUp, first).statusCode()).isEqualTo(204);
        HttpResponse<Buffer> again = post("/api/auth/signup", signUp, second);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(await(contactRepository.findByEmail(email))).hasSize(1);
    }

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
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(verified.bodyAsJsonObject().getBoolean("account")).isTrue();
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
        // an address unique to this run, so no earlier request shares its limiter bucket
        String proxied = "2001:db8::" + Long.toHexString(System.nanoTime() & 0xffffL) + ":" + Long.toHexString(UUID.randomUUID().getMostSignificantBits() & 0xffffL);
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
            List<String> codes = codesIn(output);
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
