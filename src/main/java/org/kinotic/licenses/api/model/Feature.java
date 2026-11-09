package org.kinotic.licenses.api.model;

/**
 * The Enterprise Features a key may enable, named as the {@code features} claim carries them.
 */
public enum Feature {
    AZURE_KEY_VAULT,
    /** Okta and Entra organization SSO; planned. */
    ENTERPRISE_IDENTITY_PROVIDERS
}
