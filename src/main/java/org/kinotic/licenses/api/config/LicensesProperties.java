package org.kinotic.licenses.api.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Settings of the license key server, bound under {@code kinotic.licenses}.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class LicensesProperties {

    /**
     * The origin the public site is served on, {@code https://licenses.kinotic.ai} in production, with no trailing
     * slash. Stripe Checkout and the Customer Portal return to it, and the emails link to it.
     */
    @NotBlank
    private String publicBaseUrl;

    /**
     * The origin the staff dashboard is served on, with no trailing slash. Only requests addressed to its host reach
     * the staff routes, and the staff OIDC sign-in returns to it.
     */
    @NotBlank
    private String staffBaseUrl;

    /**
     * The Azure Key Vault identifier of the ES256 key every license key is signed with, such as
     * {@code https://my-vault.vault.azure.net/keys/kinotic-license-signing/<version>}. The key never leaves the
     * vault: the server only asks the vault to sign.
     */
    private String signingKeyId;

    /**
     * Signs keys with a key generated at startup when {@link #signingKeyId} is unset, for a developer's machine. No
     * verifier trusts such a key, so a production server leaves this off and fails to start without a Key Vault key.
     */
    private boolean ephemeralSigningKey = false;

    /**
     * The Postgres database.
     */
    private DatabaseProperties database = new DatabaseProperties();

    /**
     * Stripe Billing.
     */
    private StripeProperties stripe = new StripeProperties();

    /**
     * Outbound email through Azure Communication Services.
     */
    private EmailProperties email = new EmailProperties();

    /**
     * The OpenID Connect provider staff sign in with.
     */
    private StaffOidcProperties staffOidc = new StaffOidcProperties();

}
