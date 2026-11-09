package org.kinotic.licenses;

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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A license key is a compact JWS in the Kinotic license key format, signed by the server's signing key: the key a
 * new license gets verifies against the published public key and carries the license's claims, and the trial key
 * never expires.
 */
class LicenseKeyTest extends ServerTest {

    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();

    @Autowired
    private LicenseService licenseService;
    @Autowired
    private StaffCustomerService staffCustomers;

    @Test
    void aNewLicenseGetsAKeyThatVerifiesAgainstThePublicKey() throws Exception {
        Customer customer = seedCustomer();
        Instant periodEnd = Instant.now().plus(Duration.ofDays(30));

        License license = await(licenseService.createLicense(customer, Plan.STARTUP, "sub_" + UUID.randomUUID(),
                                                             "cs_" + UUID.randomUUID(), periodEnd)).license();
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(license.getTermEnd()).isEqualTo(LicenseService.termEnd(license.getTermStart()));

        List<IssuedKey> keys = await(licenseService.keysOf(license.getId()));
        assertThat(keys).hasSize(1);
        IssuedKey key = keys.getFirst();
        assertThat(key.getExpiresAt()).isEqualTo(license.getTermEnd().plus(LicenseService.KEY_BUFFER));

        String[] parts = key.getKey().split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(BASE64URL.decode(parts[0]), StandardCharsets.UTF_8))
                .isEqualTo("{\"alg\":\"ES256\",\"typ\":\"kinotic-license\"}");
        JsonObject claims = new JsonObject(new String(BASE64URL.decode(parts[1]), StandardCharsets.UTF_8));
        assertThat(claims.getString("sub")).isEqualTo(customer.getId());
        assertThat(claims.getString("jti")).isEqualTo(key.getJti());
        assertThat(claims.getLong("iat")).isEqualTo(key.getIssuedAt().getEpochSecond());
        assertThat(claims.getLong("exp")).isEqualTo(key.getExpiresAt().getEpochSecond());
        assertThat(claims.getString("purpose")).isEqualTo("PRODUCTION");
        assertThat(claims.getJsonArray("features").getList()).containsExactly("AZURE_KEY_VAULT");

        byte[] signature = BASE64URL.decode(parts[2]);
        assertThat(signature).as("ES256 signature in IEEE P1363 form").hasSize(64);
        assertThat(verifies(parts[0] + "." + parts[1], signature)).isTrue();
        assertThat(verifies(parts[0] + "." + parts[1] + "x", signature)).isFalse();
    }

    @Test
    void theTrialKeyNeverExpiresAndBelongsToNoLicense() throws Exception {
        IssuedKey trial = await(licenseService.issueTrialKey());
        assertThat(trial.getLicenseId()).isNull();
        assertThat(trial.getExpiresAt()).isNull();
        JsonObject claims = new JsonObject(new String(BASE64URL.decode(trial.getKey().split("\\.")[1]), StandardCharsets.UTF_8));
        assertThat(claims.getString("sub")).isEqualTo(LicenseService.TRIAL_SUBJECT);
        assertThat(claims.containsKey("exp")).isFalse();
        assertThat(claims.getString("purpose")).isEqualTo("DEVELOPMENT_AND_TESTING");
        assertThat(claims.getJsonArray("features").getList()).contains("AZURE_KEY_VAULT", "ENTERPRISE_IDENTITY_PROVIDERS");
        String[] parts = trial.getKey().split("\\.");
        assertThat(verifies(parts[0] + "." + parts[1], BASE64URL.decode(parts[2]))).isTrue();
    }

    private boolean verifies(String signingInput, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(publishedPublicKey());
        verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(signature);
    }

    // the key a verifier builds from the JSON Web Key staff commit with the trial key
    private PublicKey publishedPublicKey() throws Exception {
        JsonObject jwk = new JsonObject(asStaff(staffCustomers::getSigningKey));
        assertThat(jwk.getString("kty")).isEqualTo("EC");
        assertThat(jwk.getString("crv")).isEqualTo("P-256");
        assertThat(jwk.getString("alg")).isEqualTo("ES256");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECPoint point = new ECPoint(new BigInteger(1, BASE64URL.decode(jwk.getString("x"))),
                                    new BigInteger(1, BASE64URL.decode(jwk.getString("y"))));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, parameters.getParameterSpec(ECParameterSpec.class)));
    }

}
