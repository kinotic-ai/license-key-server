package org.kinotic.licenses;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.services.StaffCustomerService;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A license key is the JWT Kinotic's license verifier accepts: parsed and verified with jjwt the way
 * {@code LicenseVerifier.verify} in kinotic-ai/kinotic does, against the public key the server publishes, it names the
 * customer's legal name as its licensee, expires, and turns on every Enterprise Feature.
 */
class LicenseKeyTest extends ServerTest {

    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();

    @Autowired
    private LicenseService licenseService;
    @Autowired
    private StaffCustomerService staffCustomers;

    @Test
    void aKeyIsAJwtKinoticsVerifierAccepts() throws Exception {
        Customer customer = seedCustomer();
        License license = await(licenseService.createLicense(customer, Plan.STARTUP, "sub_" + UUID.randomUUID(),
                                                             "cs_" + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)))).license();
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        IssuedKey key = await(licenseService.keysOf(license.getId())).getFirst();

        assertThat(new String(BASE64URL.decode(key.getKey().split("\\.")[0]), StandardCharsets.UTF_8))
                .isEqualTo("{\"alg\":\"ES256\",\"typ\":\"JWT\"}");

        // the parsing calls of LicenseVerifier.verify
        Claims claims = Jwts.parser()
                            .verifyWith(publishedPublicKey())
                            .build()
                            .parseSignedClaims(key.getKey())
                            .getPayload();
        assertThat(claims.getSubject()).isEqualTo(customer.getLegalName());
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getExpiration().toInstant()).isEqualTo(key.getExpiresAt());
        assertThat(claims.getIssuedAt().toInstant().getEpochSecond()).isEqualTo(key.getIssuedAt().getEpochSecond());
        assertThat(claims.getId()).isEqualTo(key.getJti());
        assertThat(claims.get("features", List.class)).containsExactly("*");
        assertThat(claims).doesNotContainKey("purpose");
    }

    @Test
    void aKeyWhosePayloadChangedDoesNotVerify() throws Exception {
        License license = await(licenseService.createLicense(seedCustomer(), Plan.STARTUP, "sub_" + UUID.randomUUID(),
                                                             "cs_" + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)))).license();
        String[] parts = await(licenseService.keysOf(license.getId())).getFirst().getKey().split("\\.");
        JsonObject payload = new JsonObject(new String(BASE64URL.decode(parts[1]), StandardCharsets.UTF_8)).put("sub", "Someone Else Inc");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.encode().getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> Jwts.parser().verifyWith(publishedPublicKey()).build().parseSignedClaims(forged))
                .isInstanceOf(SignatureException.class);
    }

    // the key a verifier builds as LicenseVerifier.trusted() does, from the public key the staff dashboard shows
    private PublicKey publishedPublicKey() throws Exception {
        String publicKey = asStaff(staffCustomers::getSigningPublicKey);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
    }
}
