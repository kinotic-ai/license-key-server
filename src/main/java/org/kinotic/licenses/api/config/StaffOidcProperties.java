package org.kinotic.licenses.api.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * The Microsoft Entra tenant staff sign in with, Minds Ignited's own, bound under {@code kinotic.licenses.staffOidc}.
 * Discovery runs against the issuer.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class StaffOidcProperties {

    /**
     * The Entra tenant's issuer, {@code https://login.microsoftonline.com/<tenant-id>/v2.0}.
     */
    private String issuer;

    private String clientId;

    private String clientSecret;

    /**
     * The email domain a signed-in account must belong to, {@code mindsignited.com}; an account on any other
     * domain is refused even when the provider authenticates it.
     */
    private String allowedEmailDomain;

}
