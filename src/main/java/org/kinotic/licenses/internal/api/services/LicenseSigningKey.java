package org.kinotic.licenses.internal.api.services;

import com.azure.security.keyvault.keys.cryptography.CryptographyAsyncClient;

/**
 * The key every license key is signed with: the client that signs with it, which for the production key is Azure
 * Key Vault's sign operation, and its public half for verifiers.
 *
 * @param cryptography signs with the key
 * @param publicKey    the public key as its X.509 SubjectPublicKeyInfo in Base64, the form Kinotic's license verifier
 *                     declares the key it trusts in
 */
public record LicenseSigningKey(CryptographyAsyncClient cryptography, String publicKey) {
}
