package org.kinotic.licenses.internal.api.services;

import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.RequiredArgsConstructor;
import org.kinotic.licenses.api.model.Feature;
import org.kinotic.licenses.api.model.LicenseKeyClaims;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Issues license keys to the Kinotic license key format: a compact JWS,
 * {@code base64url(header).base64url(payload).base64url(signature)}, whose header is
 * {@code {"alg":"ES256","typ":"kinotic-license"}} and whose signature is ES256 over the signing input in IEEE P1363
 * form ({@code R||S}), as Azure Key Vault returns it. The payload carries {@code sub}, {@code jti}, {@code iat},
 * {@code exp} (absent for a key that never expires), {@code purpose} and {@code features}.
 * <p>
 * The format is shared with the verifier in Kinotic's {@code kinotic-license} module; this is the one class that
 * knows it on the issuing side.
 */
@Component
@RequiredArgsConstructor
public class LicenseKeyIssuer {

    private static final String HEADER = "{\"alg\":\"ES256\",\"typ\":\"kinotic-license\"}";
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
        JsonObject ret = new JsonObject()
                .put("sub", claims.subject())
                .put("jti", claims.keyId())
                .put("iat", claims.issuedAt().getEpochSecond());
        if (claims.expiresAt() != null) {
            ret.put("exp", claims.expiresAt().getEpochSecond());
        }
        JsonArray features = new JsonArray();
        claims.features().stream().map(Feature::name).sorted().forEach(features::add);
        return ret.put("purpose", claims.purpose().name())
                  .put("features", features);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
