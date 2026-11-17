package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.kinotic.common.api.oidc.OAuth2Util;
import org.kinotic.common.api.oidc.OidcClientService;
import org.kinotic.common.api.oidc.OidcProviderType;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.licenses.api.config.CustomerOidcProperties;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.config.OidcClientProperties;
import org.kinotic.licenses.internal.api.model.OidcRegistration;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs customers in at GitHub, Microsoft or Google with the authorization code flow and PKCE, and answers the email
 * address the provider proves belongs to the person. A provider is offered once the license server's client
 * registration at it is configured.
 */
@Component
@RequiredArgsConstructor
public class CustomerOidcService {

    private static final String FLOW_SESSION_KEY_PREFIX = "customerOidcFlow.";
    private static final List<String> OIDC_SCOPES = List.of("openid", "email", "profile");
    private static final List<String> GITHUB_SCOPES = List.of("read:user", "user:email");
    private static final String GITHUB_AUTHORITY = "https://github.com/login";
    private static final String GITHUB_AUTHORIZATION_URI = "https://github.com/login/oauth/authorize";
    private static final String GITHUB_TOKEN_URI = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USER_URI = "https://api.github.com/user";
    private static final String GITHUB_USER_EMAILS_URI = "https://api.github.com/user/emails";
    // personal Microsoft accounts and the accounts of every directory
    private static final String MICROSOFT_AUTHORITY = "https://login.microsoftonline.com/common/v2.0";
    private static final String GOOGLE_AUTHORITY = "https://accounts.google.com";

    private final KinoticLicensesProperties properties;
    private final OidcClientService oidcClientService;

    // the sign-in flow of each provider configured, in the order the pages show the providers
    private final Map<OidcProviderType, OidcSignInFlow> flows = new LinkedHashMap<>();

    @PostConstruct
    void registerProviders() {
        CustomerOidcProperties oidc = properties.getLicenses().getCustomerOidc();
        // GitHub publishes no discovery document and issues no id token
        register(oidc.getGithub(), new OidcRegistration().setProvider(OidcProviderType.GITHUB)
                                                         .setAuthority(GITHUB_AUTHORITY)
                                                         .setAuthorizationUri(GITHUB_AUTHORIZATION_URI)
                                                         .setTokenUri(GITHUB_TOKEN_URI)
                                                         .setUserInfoUri(GITHUB_USER_URI)
                                                         .setUserEmailsUri(GITHUB_USER_EMAILS_URI)
                                                         .setScopes(GITHUB_SCOPES));
        register(oidc.getMicrosoft(), new OidcRegistration().setProvider(OidcProviderType.AZURE_AD)
                                                            .setAuthority(MICROSOFT_AUTHORITY)
                                                            .setAudience(oidc.getMicrosoft().getClientId())
                                                            .setScopes(OIDC_SCOPES));
        register(oidc.getGoogle(), new OidcRegistration().setProvider(OidcProviderType.GOOGLE)
                                                         .setAuthority(GOOGLE_AUTHORITY)
                                                         .setAudience(oidc.getGoogle().getClientId())
                                                         .setScopes(OIDC_SCOPES));
    }

    /**
     * The providers customers are offered, in the order the pages show them.
     */
    public List<OidcProviderType> providers() {
        return List.copyOf(flows.keySet());
    }

    /**
     * Starts a sign-in: stores the flow in the session and returns the provider's authorization URL to redirect to.
     *
     * @param ctx        the request that starts the sign-in
     * @param provider   one of the {@link #providers()}
     * @param returnPath the public site path to land on afterwards, or {@code null} for the site's default
     */
    public Future<String> start(RoutingContext ctx, OidcProviderType provider, String returnPath) {
        return flows.get(provider).start(ctx, returnPath);
    }

    /**
     * Completes a sign-in from the provider's callback: checks the state, redeems the code for the person's verified
     * claims, and requires that the provider proves the email address belongs to the person.
     *
     * @param provider one of the {@link #providers()}
     * @return the person signed in with the flow it completes, or a failure with {@link AuthenticationException}
     */
    public Future<ProviderSignIn> complete(RoutingContext ctx, OidcProviderType provider) {
        return flows.get(provider).complete(ctx)
                    .compose(signIn -> OAuth2Util.isEmailProven(signIn.claims(), provider)
                            ? Future.succeededFuture(signIn)
                            : Future.failedFuture(new AuthenticationException(
                                    "The provider does not confirm that the account's email address is verified; "
                                    + "sign up with your email address instead")));
    }

    @PreDestroy
    void stop() {
        flows.values().forEach(OidcSignInFlow::close);
    }

    private void register(OidcClientProperties configured, OidcRegistration registration) {
        if (StringUtils.isNotBlank(configured.getClientId())) {
            String providerKey = registration.getProvider().key();
            registration.setClientId(configured.getClientId())
                        .setClientSecret(configured.getClientSecret())
                        .setCallbackUrl(properties.getLicenses().getPublicBaseUrl() + "/api/auth/provider/" + providerKey + "/callback");
            flows.put(registration.getProvider(),
                      new OidcSignInFlow(oidcClientService, registration, FLOW_SESSION_KEY_PREFIX + providerKey));
        }
    }
}
