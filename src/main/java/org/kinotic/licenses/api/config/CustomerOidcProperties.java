package org.kinotic.licenses.api.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * The license server's client registrations at the providers customers sign up and sign in with, bound under
 * {@code kinotic.licenses.customerOidc}. Each registration's redirect URI is the public site's origin followed by
 * {@code /api/auth/provider/<provider>/callback}, where {@code <provider>} is {@code github}, {@code azure-ad} or
 * {@code google}.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class CustomerOidcProperties {

    /**
     * The GitHub OAuth app.
     */
    private OidcClientProperties github = new OidcClientProperties();

    /**
     * The Microsoft Entra app registration, which admits personal accounts and the accounts of every directory. Its
     * token configuration adds the optional {@code email} and {@code xms_edov} claims to the id token: a work or
     * school account signs in only when {@code xms_edov} says its directory has verified the email's domain.
     */
    private OidcClientProperties microsoft = new OidcClientProperties();

    /**
     * The Google OAuth client.
     */
    private OidcClientProperties google = new OidcClientProperties();

}
