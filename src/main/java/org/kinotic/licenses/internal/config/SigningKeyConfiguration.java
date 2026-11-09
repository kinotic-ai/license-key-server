package org.kinotic.licenses.internal.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.models.JsonWebKey;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyOperation;
import com.azure.security.keyvault.keys.models.KeyType;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.internal.api.services.LicenseSigningKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the key license keys are signed with. With {@code kinotic.licenses.signingKeyId} set, that is the ES256
 * key in Azure Key Vault, which signs in the vault and never leaves it. With {@code kinotic.licenses.ephemeralSigningKey}
 * on instead, as on a developer's machine, the server signs with a P-256 key it generates at startup and forgets at
 * shutdown, and says so in the log. With neither, the server does not start.
 */
@Slf4j
@Configuration
public class SigningKeyConfiguration {

    private static final int COORDINATE_BYTES = 32;

    @Bean
    public LicenseSigningKey licenseSigningKey(KinoticLicensesProperties properties) {
        String keyId = properties.getLicenses().getSigningKeyId();
        LicenseSigningKey ret;
        if (StringUtils.isNotBlank(keyId)) {
            ret = keyVaultKey(keyId);
        } else if (properties.getLicenses().isEphemeralSigningKey()) {
            log.warn("kinotic.licenses.ephemeralSigningKey is on: license keys are signed with an ephemeral key that no"
                             + " verifier trusts");
            ret = ephemeralKey();
        } else {
            throw new IllegalStateException("kinotic.licenses.signingKeyId is not set. Set it to the Key Vault key, or set"
                                                    + " kinotic.licenses.ephemeralSigningKey=true on a developer's machine");
        }
        return ret;
    }

    private static LicenseSigningKey keyVaultKey(String keyId) {
        var cryptography = new CryptographyClientBuilder()
                .keyIdentifier(keyId)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildAsyncClient();
        // read once at startup, which also proves the vault, the key and the credential before the first sign
        KeyVaultKey key = cryptography.getKey().block();
        JsonWebKey jwk = key.getKey();
        // a key in a Premium vault or a Managed HSM is EC-HSM; either signs the same ES256
        boolean ellipticCurve = KeyType.EC.equals(jwk.getKeyType()) || KeyType.EC_HSM.equals(jwk.getKeyType());
        if (!ellipticCurve || !KeyCurveName.P_256.equals(jwk.getCurveName())) {
            throw new IllegalStateException("The signing key " + keyId + " must be an EC P-256 key; it is "
                                                    + jwk.getKeyType() + " " + jwk.getCurveName());
        }
        log.info("Signing license keys with the Key Vault key {}", key.getId());
        return new LicenseSigningKey(cryptography, publicJwk(key.getId(), jwk.getX(), jwk.getY()));
    }

    private static LicenseSigningKey ephemeralKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
            byte[] x = coordinate(publicKey.getW().getAffineX());
            byte[] y = coordinate(publicKey.getW().getAffineY());
            String kid = "ephemeral-" + UUID.randomUUID();
            JsonWebKey jwk = new JsonWebKey()
                    .setId(kid)
                    .setKeyType(KeyType.EC)
                    .setCurveName(KeyCurveName.P_256)
                    .setKeyOps(List.of(KeyOperation.SIGN, KeyOperation.VERIFY))
                    .setX(x)
                    .setY(y)
                    .setD(coordinate(((ECPrivateKey) pair.getPrivate()).getS()));
            // a client given the key material signs locally, with the same operation the vault-backed client offers
            var cryptography = new CryptographyClientBuilder().jsonWebKey(jwk).buildAsyncClient();
            return new LicenseSigningKey(cryptography, publicJwk(kid, x, y));
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the ephemeral signing key", e);
        }
    }

    private static JsonObject publicJwk(String kid, byte[] x, byte[] y) {
        Base64.Encoder base64url = Base64.getUrlEncoder().withoutPadding();
        return new JsonObject()
                .put("kty", "EC")
                .put("crv", "P-256")
                .put("x", base64url.encodeToString(x))
                .put("y", base64url.encodeToString(y))
                .put("kid", kid)
                .put("alg", "ES256")
                .put("use", "sig");
    }

    // a fixed 32-byte big-endian coordinate, as JWK requires, whatever length BigInteger gives it
    private static byte[] coordinate(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] ret = new byte[COORDINATE_BYTES];
        if (bytes.length > COORDINATE_BYTES) {
            System.arraycopy(bytes, bytes.length - COORDINATE_BYTES, ret, 0, COORDINATE_BYTES);
        } else {
            System.arraycopy(bytes, 0, ret, COORDINATE_BYTES - bytes.length, bytes.length);
        }
        Arrays.fill(bytes, (byte) 0);
        return ret;
    }
}
