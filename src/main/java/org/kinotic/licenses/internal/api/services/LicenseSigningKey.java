package org.kinotic.licenses.internal.api.services;

import com.azure.security.keyvault.keys.cryptography.CryptographyAsyncClient;
import io.vertx.core.json.JsonObject;

/**
 * The key every license key is signed with: the client that signs with it, which for the production key is Azure
 * Key Vault's sign operation, and its public half as a JSON Web Key for verifiers.
 *
 * @param cryptography signs with the key
 * @param publicJwk    the public key, an EC P-256 JSON Web Key with {@code kty}, {@code crv}, {@code x}, {@code y},
 *                     {@code kid}, {@code alg} and {@code use}
 */
public record LicenseSigningKey(CryptographyAsyncClient cryptography, JsonObject publicJwk) {
}
