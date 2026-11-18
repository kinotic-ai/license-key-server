package org.kinotic.licenses.internal.api.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.kinotic.common.api.oidc.OidcEndpointSettings;
import org.kinotic.common.api.oidc.OidcProviderType;

import java.util.List;

/**
 * One of the license server's client registrations at a sign-in provider: the provider's fixed addresses together with
 * the client id and secret the deployment configures, and what a sign-in through it sends the provider.
 */
@Getter
@Setter
@Accessors(chain = true)
public class OidcRegistration implements OidcEndpointSettings {

    private OidcProviderType provider;
    private String authority;
    private String clientId;
    private String clientSecret;
    private String audience;
    private String authorizationUri;
    private String tokenUri;
    private String userInfoUri;
    private String userEmailsUri;
    /** The redirect URI the registration names at the provider. */
    private String callbackUrl;
    /** The scopes a sign-in requests. */
    private List<String> scopes;

}
