package org.kinotic.licenses.internal.api.services;

import org.kinotic.common.api.oidc.OidcSignInFlow;
import org.kinotic.common.api.oidc.OidcErrorCodes;
import org.kinotic.common.api.oidc.OidcCallbackException;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.kinotic.common.api.oidc.OidcClientService;
import org.kinotic.common.api.oidc.OidcProviderType;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.config.StaffOidcProperties;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.security.ParticipantType;
import org.kinotic.licenses.internal.api.model.OidcRegistration;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Signs staff in through Minds Ignited's Microsoft Entra tenant with the authorization code flow and PKCE. The
 * tenant is discovered from its issuer on the first sign-in. A signed-in account is accepted only when its email is
 * on the allowed domain.
 */
@Component
@RequiredArgsConstructor
public class StaffOidcService {

    /** The route the provider redirects back to, relative to the staff site's origin. */
    public static final String CALLBACK_PATH = "/api/auth/staff/callback";

    private static final String ENTRA_HOST = "login.microsoftonline.com";
    private static final List<String> SCOPES = List.of("openid", "email", "profile");

    private final KinoticLicensesProperties properties;
    private final OidcClientService oidcClientService;

    private final OidcSignInFlow flow = new OidcSignInFlow("staffOidcFlow");

    private OidcRegistration registration;

    /**
     * Starts a sign-in: stores the flow in the session and returns the provider's authorization URL to redirect to.
     *
     * @param ctx        the request that starts the sign-in
     * @param returnPath the staff site path to land on afterwards, or {@code null} for the root
     */
    public Future<String> start(RoutingContext ctx, String returnPath) {
        OidcRegistration staff = registration();
        return staff.client(oidcClientService)
                    .map(client -> flow.start(ctx, client, staff.getCallbackUrl(), staff.getScopes(),
                                              ProviderSignIn.begin(staff, ctx, returnPath)));
    }

    /**
     * Completes a sign-in from the provider's callback: checks the state, redeems the code for the account's verified
     * claims, checks the account's email, and returns the staff participant with the flow it completes.
     *
     * @return the participant and the sign-in, or a failure with {@link OidcCallbackException}
     */
    public Future<StaffSignIn> complete(RoutingContext ctx) {
        return ProviderSignIn.complete(ctx, flow, registration(), oidcClientService)
                             .compose(signIn -> staffParticipant(signIn)
                                     .map(participant -> new StaffSignIn(participant, signIn)));
    }

    private Future<LicenseParticipant> staffParticipant(ProviderSignIn signIn) {
        String allowedDomain = properties.getLicenses().getStaffOidc().getAllowedEmailDomain();
        String email = signIn.email();
        String domain = email != null && email.contains("@") ? email.substring(email.indexOf('@') + 1) : null;
        Future<LicenseParticipant> ret;
        if (domain == null || !domain.equalsIgnoreCase(allowedDomain)) {
            ret = Future.failedFuture(new OidcCallbackException(OidcErrorCodes.ACCOUNT_NOT_ALLOWED));
        } else {
            // Entra sends no email_verified: the tenant's administrator issues its accounts' addresses, and the domain
            // check above confines staff to Minds Ignited's own tenant
            ret = Future.succeededFuture(LicenseParticipant.builder()
                                                           .id(email.toLowerCase())
                                                           .type(ParticipantType.STAFF)
                                                           .email(email)
                                                           .name(signIn.name() != null ? signIn.name() : email)
                                                           .metadata(Map.of())
                                                           .roles(List.of())
                                                           .build());
        }
        return ret;
    }

    // built by the first sign-in, which is when the settings are checked
    private synchronized OidcRegistration registration() {
        if (registration == null) {
            StaffOidcProperties oidc = properties.getLicenses().getStaffOidc();
            Validate.validState(StringUtils.isNoneBlank(oidc.getIssuer(), oidc.getClientId(), oidc.getAllowedEmailDomain()),
                                "kinotic.licenses.staffOidc needs an issuer, a clientId and an allowedEmailDomain");
            Validate.validState(ENTRA_HOST.equalsIgnoreCase(URI.create(oidc.getIssuer()).getHost()),
                                "kinotic.licenses.staffOidc.issuer must be a Microsoft Entra issuer on %s", ENTRA_HOST);
            registration = new OidcRegistration().setProvider(OidcProviderType.AZURE_AD)
                                                 .setAuthority(oidc.getIssuer())
                                                 .setClientId(oidc.getClientId())
                                                 .setClientSecret(oidc.getClientSecret())
                                                 .setAudience(oidc.getClientId())
                                                 .setCallbackUrl(properties.getLicenses().getStaffBaseUrl() + CALLBACK_PATH)
                                                 .setScopes(SCOPES);
        }
        return registration;
    }

    @PreDestroy
    synchronized void stop() {
        if (registration != null) {
            registration.close();
        }
    }
}
