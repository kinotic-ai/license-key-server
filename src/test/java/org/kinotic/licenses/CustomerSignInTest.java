package org.kinotic.licenses;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
import org.kinotic.licenses.support.ServerTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Customers sign up with an email address, whose emailed link opens the form that names the organization and sets the
 * password, and sign in with their email and password. A link is used once and expires, and a request for one never
 * says whether the address has an account.
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerSignInTest extends ServerTest {

    @Autowired
    private AgreementAcceptanceRepository acceptanceRepository;

    @Test
    void theSignUpLinkCreatesTheAccountOnceAndItsPasswordSignsIn(CapturedOutput output) throws Exception {
        String email = "new-" + UUID.randomUUID() + "@tiny.example";
        int before = linksIn(output, SIGN_UP_LINK).size();
        HttpResponse<Buffer> requested = post("/api/auth/signup", new JsonObject().put("email", email), null);
        assertThat(requested.statusCode()).isEqualTo(202);
        assertThat(requested.bodyAsString()).isNullOrEmpty();
        String token = awaitLink(output, SIGN_UP_LINK, before);

        assertThat(post("/api/auth/signup/complete", completion("not-a-token"), null).statusCode()).isEqualTo(401);
        HttpResponse<Buffer> completed = post("/api/auth/signup/complete", completion(token), null);
        assertThat(completed.statusCode()).isEqualTo(204);
        String cookie = completed.cookies().getFirst().split(";")[0];
        assertThat(cookie).startsWith("__Host-kinotic-licenses-session=");
        assertThat(get("/api/auth/me", cookie).statusCode()).isEqualTo(204);

        CustomerContact contact = await(contactRepository.findByEmail(email)).getFirst();
        assertThat(contact.getName()).isEqualTo("Grace Hopper");
        assertThat(await(customerRepository.findById(contact.getCustomerId())).getLegalName()).isEqualTo("Tiny Startup LLC");
        // the terms are accepted with each purchase and request, not at sign-up
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();

        assertThat(post("/api/auth/signup/complete", completion(token), null).statusCode()).isEqualTo(401);

        assertThat(post("/api/auth/logout", new JsonObject(), cookie).statusCode()).isEqualTo(204);
        assertThat(get("/api/auth/me", cookie).statusCode()).isEqualTo(401);

        assertThat(post("/api/auth/login", credentials(email.toUpperCase(), "not the password"), null).statusCode()).isEqualTo(401);
        HttpResponse<Buffer> signedIn = post("/api/auth/login", credentials(email.toUpperCase(), PASSWORD), null);
        assertThat(signedIn.statusCode()).isEqualTo(204);
        assertThat(get("/api/auth/me", signedIn.cookies().getFirst().split(";")[0]).statusCode()).isEqualTo(204);
    }

    @Test
    void anAddressWithTwoSignUpLinksSignsUpOnce(CapturedOutput output) throws Exception {
        String email = "twice-" + UUID.randomUUID() + "@tiny.example";
        int before = linksIn(output, SIGN_UP_LINK).size();
        assertThat(post("/api/auth/signup", new JsonObject().put("email", email), null).statusCode()).isEqualTo(202);
        String first = awaitLink(output, SIGN_UP_LINK, before);
        assertThat(post("/api/auth/signup", new JsonObject().put("email", email), null).statusCode()).isEqualTo(202);
        String second = awaitLink(output, SIGN_UP_LINK, before + 1);

        assertThat(post("/api/auth/signup/complete", completion(first), null).statusCode()).isEqualTo(204);
        assertThat(post("/api/auth/signup/complete", completion(second), null).statusCode()).isEqualTo(409);
        assertThat(await(contactRepository.findByEmail(email))).hasSize(1);
    }

    @Test
    void aShortPasswordIsRefusedAndLeavesTheLinkUsable(CapturedOutput output) throws Exception {
        String email = "short-" + UUID.randomUUID() + "@tiny.example";
        int before = linksIn(output, SIGN_UP_LINK).size();
        assertThat(post("/api/auth/signup", new JsonObject().put("email", email), null).statusCode()).isEqualTo(202);
        String token = awaitLink(output, SIGN_UP_LINK, before);

        assertThat(post("/api/auth/signup/complete", completion(token).put("password", "too short"), null).statusCode()).isEqualTo(400);
        assertThat(post("/api/auth/signup/complete", completion(token), null).statusCode()).isEqualTo(204);
    }

    @Test
    void anExpiredLinkIsRefused(CapturedOutput output) throws Exception {
        String email = "late-" + UUID.randomUUID() + "@tiny.example";
        int before = linksIn(output, SIGN_UP_LINK).size();
        assertThat(post("/api/auth/signup", new JsonObject().put("email", email), null).statusCode()).isEqualTo(202);
        String token = awaitLink(output, SIGN_UP_LINK, before);
        sql("UPDATE email_link SET expires_at = now() - interval '1 second' WHERE email = $1", email);

        assertThat(post("/api/auth/signup/complete", completion(token), null).statusCode()).isEqualTo(401);
    }

    @Test
    void aSignUpWithAnAddressThatHasAnAccountSendsAPasswordResetLink(CapturedOutput output) throws Exception {
        Customer customer = seedCustomer();
        String email = await(contactRepository.findByCustomerId(customer.getId())).getFirst().getEmail();
        // a seeded contact has set no password, so none signs in
        assertThat(post("/api/auth/login", credentials(email, PASSWORD), null).statusCode()).isEqualTo(401);

        int signUps = linksIn(output, SIGN_UP_LINK).size();
        int resets = linksIn(output, PASSWORD_RESET_LINK).size();
        HttpResponse<Buffer> requested = post("/api/auth/signup", new JsonObject().put("email", email), null);
        assertThat(requested.statusCode()).isEqualTo(202);
        assertThat(requested.bodyAsString()).isNullOrEmpty();
        String token = awaitLink(output, PASSWORD_RESET_LINK, resets);
        assertThat(linksIn(output, SIGN_UP_LINK)).hasSize(signUps);

        // a reset link opens no sign-up form
        assertThat(post("/api/auth/signup/complete", completion(token), null).statusCode()).isEqualTo(401);
        HttpResponse<Buffer> reset = post("/api/auth/password/reset", new JsonObject().put("token", token).put("password", PASSWORD), null);
        assertThat(reset.statusCode()).isEqualTo(204);
        assertThat(get("/api/auth/me", reset.cookies().getFirst().split(";")[0]).statusCode()).isEqualTo(204);
        assertThat(post("/api/auth/login", credentials(email, PASSWORD), null).statusCode()).isEqualTo(204);
        assertThat(await(customerRepository.findById(customer.getId())).getLegalName()).isEqualTo("Acme Robotics Inc");
    }

    @Test
    void aForgottenPasswordIsResetByTheEmailedLink(CapturedOutput output) throws Exception {
        String email = "forgot-" + UUID.randomUUID() + "@tiny.example";
        signUp(output, email, "Tiny Startup LLC");
        int before = linksIn(output, PASSWORD_RESET_LINK).size();
        assertThat(post("/api/auth/password/forgot", new JsonObject().put("email", email), null).statusCode()).isEqualTo(202);
        String token = awaitLink(output, PASSWORD_RESET_LINK, before);

        String newPassword = "a brand new passphrase";
        assertThat(post("/api/auth/password/reset", new JsonObject().put("token", token).put("password", newPassword), null).statusCode()).isEqualTo(204);
        assertThat(post("/api/auth/password/reset", new JsonObject().put("token", token).put("password", newPassword), null).statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/login", credentials(email, PASSWORD), null).statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/login", credentials(email, newPassword), null).statusCode()).isEqualTo(204);
    }

    @Test
    void aPasswordResetForAnUnknownAddressLooksLikeAnyOther() throws Exception {
        HttpResponse<Buffer> requested = post("/api/auth/password/forgot",
                                              new JsonObject().put("email", "nobody-" + UUID.randomUUID() + "@example.com"), null);
        assertThat(requested.statusCode()).isEqualTo(202);
        assertThat(requested.bodyAsString()).isNullOrEmpty();
    }

    @Test
    void theAddressLimitKeysOnTheAddressTheProxyAppended() throws Exception {
        // an address unique to this run, so no earlier request shares its limiter bucket
        String proxied = "2001:db8::" + Long.toHexString(System.nanoTime() & 0xffffL) + ":" + Long.toHexString(UUID.randomUUID().getMostSignificantBits() & 0xffffL);
        int accepted = 0;
        int limited = 0;
        for (int i = 0; i < 35; i++) {
            // a client that writes a different address of its own to the left each time is still one client
            HttpResponse<Buffer> response = await(client.post("/api/auth/signup")
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
    void theStaffRoutesDoNotAnswerOnThePublicHost() throws Exception {
        HttpResponse<Buffer> onPublicHost = await(client.get("/api/auth/staff/login").putHeader("Host", "localhost:5173").send());
        assertThat(onPublicHost.statusCode()).isEqualTo(404);
    }

    @Test
    void aStaffCallbackThatMatchesNoStartedSignInReturnsToTheStaffLoginPage() throws Exception {
        HttpResponse<Buffer> callback = await(client.get("/api/auth/staff/callback?code=a-code&state=a-state")
                                                    .putHeader("Host", "localhost:5174").followRedirects(false).send());
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.getHeader("Location")).startsWith("http://localhost:5174/login?error=");
    }

    private static JsonObject completion(String token) {
        return new JsonObject().put("token", token).put("organization", "Tiny Startup LLC")
                               .put("contactName", "Grace Hopper").put("password", PASSWORD);
    }

    private static JsonObject credentials(String email, String password) {
        return new JsonObject().put("email", email).put("password", password);
    }
}
