package org.kinotic.licenses.internal.api.services;

import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.LicenseKeyClaims;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Issues license keys in the format Kinotic verifies: a JWT signed with ES256, whose header is
 * {@code {"alg":"ES256","typ":"JWT"}} and whose payload carries {@code sub} (the licensee's legal name), {@code jti},
 * {@code iat}, {@code exp} and {@code features: ["*"]}, which turns on every Enterprise Feature. The signature is over
 * the signing input in IEEE P1363 form ({@code R||S}), as JWS requires and Azure Key Vault returns it.
 * <p>
 * Kinotic's verifier is {@code org.kinotic.domain.internal.api.ee.license.LicenseVerifier} in kinotic-ai/kinotic; this
 * is the one class that knows the format on the issuing side.
 */
@Component
@RequiredArgsConstructor
public class LicenseKeyIssuer {

    private static final String HEADER = "{\"alg\":\"ES256\",\"typ\":\"JWT\"}";
    // the features entry Kinotic reads as every Enterprise Feature, those released later included
    private static final String ALL_FEATURES = "*";
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final LicenseSigningKey signingKey;
    private final Vertx vertx;

    /**
     * Signs the claims into a compact JWS.
     */
    public Future<String> issue(LicenseKeyClaims claims) {
        String signingInput = BASE64URL.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8))
                + "." + BASE64URL.encodeToString(payload(claims).encode().getBytes(StandardCharsets.UTF_8));
        // Key Vault signs a digest, never the message itself
        byte[] digest = sha256(signingInput.getBytes(StandardCharsets.US_ASCII));
        return Future.fromCompletionStage(signingKey.cryptography().sign(SignatureAlgorithm.ES256, digest).toFuture(),
                                          vertx.getOrCreateContext())
                     .map(result -> signingInput + "." + BASE64URL.encodeToString(result.getSignature()));
    }

    private static JsonObject payload(LicenseKeyClaims claims) {
        return new JsonObject()
                .put("sub", claims.subject())
                .put("jti", claims.keyId())
                .put("iat", claims.issuedAt().getEpochSecond())
                .put("exp", claims.expiresAt().getEpochSecond())
                .put("features", new JsonArray().add(ALL_FEATURES));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
