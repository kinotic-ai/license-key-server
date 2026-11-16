package org.kinotic.licenses.internal.api.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.kinotic.common.api.oidc.OidcClientSettings;
import org.kinotic.common.api.oidc.OidcProviderType;

/**
 * The license server's client registration at one of the providers customers sign in with: the provider's fixed
 * addresses together with the client id and secret the deployment configures.
 */
@Getter
@Setter
@Accessors(chain = true)
public class CustomerOidcRegistration implements OidcClientSettings {

    private OidcProviderType provider;
    private String authority;
    private String clientId;
    private String clientSecret;
    private String audience;
    private String authorizationUri;
    private String tokenUri;
    private String userInfoUri;
    private String userEmailsUri;

}
