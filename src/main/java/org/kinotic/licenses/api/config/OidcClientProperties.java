package org.kinotic.licenses.api.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * The license server's client registration at one sign-in provider.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class OidcClientProperties {

    /**
     * The registration's client id. The provider is offered to customers once it is set.
     */
    private String clientId;

    private String clientSecret;

}
