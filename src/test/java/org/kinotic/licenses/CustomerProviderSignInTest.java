package org.kinotic.licenses;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.security.ParticipantType;
import org.kinotic.licenses.internal.api.services.CustomerSignInService;
import org.kinotic.licenses.support.ServerTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Customers sign up and in through GitHub, Microsoft or Google. The routes are exercised as far as the provider: the
 * start redirects to it, and a callback that matches no started sign-in returns to the login page. What follows a
 * sign-in the provider verified, an address signed in to its account or given one, is exercised on
 * {@link CustomerSignInService}; the exchange with a provider is kinotic-common's, tested there against a provider
 * of its own.
 */
class CustomerProviderSignInTest extends ServerTest {

    private static final String START = "/api/auth/provider/github/start";
    private static final String CALLBACK = "/api/auth/provider/github/callback";

    @Autowired
    private CustomerSignInService signInService;

    @Test
    void theSiteListsTheProvidersConfigured() throws Exception {
        HttpResponse<Buffer> site = get("/api/site", null);
        assertThat(site.statusCode()).isEqualTo(200);
        assertThat(site.bodyAsJsonObject().getJsonArray("signInProviders")).isEqualTo(new JsonArray().add("github"));
    }

    @Test
    void aSignInStartsAtTheProvider() throws Exception {
        HttpResponse<Buffer> started = start(START + "?referer=/buy");
        assertThat(started.statusCode()).isEqualTo(302);
        String location = started.getHeader("Location");
        assertThat(location).startsWith("https://github.com/login/oauth/authorize?");
        assertThat(parameter(location, "client_id")).isEqualTo("test-github-client");
        assertThat(parameter(location, "redirect_uri")).isEqualTo(PUBLIC_ORIGIN + CALLBACK);
        assertThat(parameter(location, "scope")).isEqualTo("read:user user:email");
        assertThat(parameter(location, "code_challenge_method")).isEqualTo("S256");
        assertThat(parameter(location, "code_challenge")).isNotBlank();
        assertThat(parameter(location, "state")).isNotBlank();
        assertThat(location).doesNotContain("test-github-secret");
    }

    @Test
    void aProviderThatIsNotConfiguredIsNotFound() throws Exception {
        assertThat(start("/api/auth/provider/google/start").statusCode()).isEqualTo(404);
        assertThat(start("/api/auth/provider/okta/start").statusCode()).isEqualTo(404);
        assertThat(callback("/api/auth/provider/google/callback?code=a-code&state=a-state", null).statusCode()).isEqualTo(404);
    }

    @Test
    void aCallbackThatMatchesNoStartedSignInReturnsToTheLoginPage() throws Exception {
        HttpResponse<Buffer> started = start(START);
        String cookie = started.cookies().getFirst().split(";")[0];
        String state = parameter(started.getHeader("Location"), "state");

        assertThat(callback(CALLBACK + "?code=a-code&state=" + state, null).getHeader("Location"))
                .as("a callback from a browser that started no sign-in")
                .startsWith(PUBLIC_ORIGIN + "/login?error=");

        HttpResponse<Buffer> forged = callback(CALLBACK + "?code=a-code&state=not-the-state", cookie);
        assertThat(forged.statusCode()).isEqualTo(302);
        assertThat(forged.getHeader("Location")).startsWith(PUBLIC_ORIGIN + "/login?error=");
        // the refused callback used the sign-in up, so the state it was started with opens nothing either
        cookie = sessionCookie(forged, cookie);
        assertThat(callback(CALLBACK + "?code=a-code&state=" + state, cookie).getHeader("Location"))
                .startsWith(PUBLIC_ORIGIN + "/login?error=");
        assertThat(get("/api/auth/me", cookie).statusCode()).isEqualTo(401);
    }

    @Test
    void aSignInTheProviderRefusedReturnsToTheLoginPageWithTheReason() throws Exception {
        HttpResponse<Buffer> started = start(START);
        String cookie = started.cookies().getFirst().split(";")[0];
        String state = parameter(started.getHeader("Location"), "state");

        HttpResponse<Buffer> refused = callback(CALLBACK + "?error=access_denied&state=" + state, cookie);
        assertThat(refused.statusCode()).isEqualTo(302);
        assertThat(URLDecoder.decode(refused.getHeader("Location"), StandardCharsets.UTF_8))
                .isEqualTo(PUBLIC_ORIGIN + "/login?error=The sign-in provider reported: access_denied");
    }

    @Test
    void aSignUpFormWithNoProviderSignUpBehindItIsRefused() throws Exception {
        assertThat(get("/api/auth/signup/provider", null).statusCode()).isEqualTo(404);
        JsonObject form = new JsonObject().put("email", "grace@tiny.example")
                                          .put("organization", "Tiny Startup LLC")
                                          .put("contactName", "Grace Hopper");
        assertThat(post("/api/auth/signup/provider/complete", form, null).statusCode()).isEqualTo(401);
    }

    @Test
    void aProvenAddressWithNoAccountIsGivenOneWithNoPassword() throws Exception {
        String email = "provider-" + UUID.randomUUID() + "@tiny.example";
        assertThat(await(signInService.signInWithProvenEmail(email))).isNull();

        LicenseParticipant created = await(signInService.completeProviderSignUp(email, "Tiny Startup LLC", "Grace Hopper"));
        assertThat(created.getType()).isEqualTo(ParticipantType.CUSTOMER);
        CustomerContact contact = await(contactRepository.findByEmail(email)).getFirst();
        assertThat(created.getId()).isEqualTo(contact.getId());
        assertThat(contact.getName()).isEqualTo("Grace Hopper");
        assertThat(await(customerRepository.findById(contact.getCustomerId())).getLegalName()).isEqualTo("Tiny Startup LLC");

        // the address signs in through a provider from then on, however the provider cases it, and with no password
        assertThat(await(signInService.signInWithProvenEmail(email.toUpperCase())).getId()).isEqualTo(contact.getId());
        JsonObject credentials = new JsonObject().put("email", email).put("password", PASSWORD);
        assertThat(post("/api/auth/login", credentials, null).statusCode()).isEqualTo(401);

        assertThatThrownBy(() -> await(signInService.completeProviderSignUp(email, "Another LLC", "Grace Hopper")))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AlreadyExistsException.class);
        assertThat(await(contactRepository.findByEmail(email))).hasSize(1);
    }

    @Test
    void aProvenAddressWithAnAccountIsSignedInToIt() throws Exception {
        Customer customer = seedCustomer();
        CustomerContact contact = await(contactRepository.findByCustomerId(customer.getId())).getFirst();

        LicenseParticipant signedIn = await(signInService.signInWithProvenEmail(contact.getEmail()));
        assertThat(signedIn.getId()).isEqualTo(contact.getId());
        assertThat(signedIn.getCustomerId()).isEqualTo(customer.getId());
        assertThat(signedIn.getType()).isEqualTo(ParticipantType.CUSTOMER);
    }

    /** The form a provider button posts, answered without following the redirect to the provider. */
    private static HttpResponse<Buffer> start(String path) throws Exception {
        return await(client.post(path).putHeader("Origin", PUBLIC_ORIGIN).followRedirects(false).send());
    }

    /** The provider's redirect back to the server, carrying the session cookie when one is given. */
    private static HttpResponse<Buffer> callback(String path, String cookie) throws Exception {
        HttpRequest<Buffer> request = client.get(path).followRedirects(false);
        if (cookie != null) {
            request.putHeader("Cookie", cookie);
        }
        return await(request.send());
    }

    // the session cookie after a response, which replaces the one sent when the server issued a new id
    private static String sessionCookie(HttpResponse<Buffer> response, String sent) {
        return response.cookies().isEmpty() ? sent : response.cookies().getFirst().split(";")[0];
    }

    private static String parameter(String url, String name) {
        String ret = null;
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (pair.substring(0, equals).equals(name)) {
                ret = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return ret;
    }
}
