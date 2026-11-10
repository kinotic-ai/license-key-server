package org.kinotic.licenses.internal.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.models.JsonWebKey;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyOperation;
import com.azure.security.keyvault.keys.models.KeyType;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.internal.api.services.LicenseSigningKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
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
        try {
            return new LicenseSigningKey(cryptography, publicKeyInfo(jwk.getX(), jwk.getY()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The public key of " + keyId + " cannot be read", e);
        }
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
            return new LicenseSigningKey(cryptography, publicKeyInfo(x, y));
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the ephemeral signing key", e);
        }
    }

    // the X.509 SubjectPublicKeyInfo of the P-256 point (x, y), in Base64
    private static String publicKeyInfo(byte[] x, byte[] y) throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, parameters.getParameterSpec(ECParameterSpec.class)));
        return Base64.getEncoder().encodeToString(key.getEncoded());
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
