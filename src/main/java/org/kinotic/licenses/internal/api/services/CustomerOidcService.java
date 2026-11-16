package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.kinotic.common.api.oidc.OAuth2Util;
import org.kinotic.common.api.oidc.OidcAuthorization;
import org.kinotic.common.api.oidc.OidcClient;
import org.kinotic.common.api.oidc.OidcClientService;
import org.kinotic.common.api.oidc.OidcProviderType;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.core.api.security.SessionBinding;
import org.kinotic.licenses.api.config.CustomerOidcProperties;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.config.OidcClientProperties;
import org.kinotic.licenses.internal.api.model.CustomerOidcRegistration;
import org.kinotic.licenses.internal.api.model.OidcFlowState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs customers in at GitHub, Microsoft or Google with the authorization code flow and PKCE, and answers the email
 * address the provider proves belongs to the person. A provider is offered once the license server's client
 * registration at it is configured.
 */
@Slf4j
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

    // the registrations configured, in the order the pages show their providers
    private final Map<OidcProviderType, CustomerOidcRegistration> registrations = new LinkedHashMap<>();
    private final Map<OidcProviderType, Future<OidcClient>> clients = new ConcurrentHashMap<>();

    @PostConstruct
    void start() {
        CustomerOidcProperties oidc = properties.getLicenses().getCustomerOidc();
        // GitHub publishes no discovery document and issues no id token
        register(OidcProviderType.GITHUB, oidc.getGithub(), new CustomerOidcRegistration().setAuthority(GITHUB_AUTHORITY)
                                                                                          .setAuthorizationUri(GITHUB_AUTHORIZATION_URI)
                                                                                          .setTokenUri(GITHUB_TOKEN_URI)
                                                                                          .setUserInfoUri(GITHUB_USER_URI)
                                                                                          .setUserEmailsUri(GITHUB_USER_EMAILS_URI));
        register(OidcProviderType.AZURE_AD, oidc.getMicrosoft(), new CustomerOidcRegistration().setAuthority(MICROSOFT_AUTHORITY)
                                                                                               .setAudience(oidc.getMicrosoft().getClientId()));
        register(OidcProviderType.GOOGLE, oidc.getGoogle(), new CustomerOidcRegistration().setAuthority(GOOGLE_AUTHORITY)
                                                                                          .setAudience(oidc.getGoogle().getClientId()));
    }

    /**
     * The providers customers are offered, in the order the pages show them.
     */
    public List<OidcProviderType> providers() {
        return List.copyOf(registrations.keySet());
    }

    /**
     * Starts a sign-in: stores the flow in the session and returns the provider's authorization URL to redirect to.
     *
     * @param ctx        the request that starts the sign-in
     * @param provider   one of the {@link #providers()}
     * @param returnPath the public site path to land on afterwards, or {@code null} for the site's default
     */
    public Future<String> start(RoutingContext ctx, OidcProviderType provider, String returnPath) {
        OidcAuthorization authorization = OidcAuthorization.create();
        return client(provider)
                .map(client -> client.authorizationUrl(authorization, callbackUrl(provider), scopes(provider)))
                // stored once the provider is reachable, so a failed start leaves any pending flow intact
                .onSuccess(url -> {
                    Session session = ctx.session();
                    session.regenerateId();
                    session.put(flowSessionKey(provider), new OidcFlowState(authorization, SessionBinding.origin(ctx), returnPath));
                });
    }

    /**
     * Completes a sign-in from the provider's callback: checks the state, redeems the code for the person's verified
     * claims, and requires that the provider proves the email address belongs to the person.
     *
     * @param provider one of the {@link #providers()}
     * @return the person signed in with the flow it completes, or a failure with {@link AuthenticationException}
     */
    public Future<ProviderSignIn> complete(RoutingContext ctx, OidcProviderType provider) {
        String code = ctx.request().getParam("code");
        String state = ctx.request().getParam("state");
        String providerError = ctx.request().getParam("error");
        // consumed first, so a replayed callback finds no flow
        OidcFlowState flow = ctx.session().remove(flowSessionKey(provider));

        Future<ProviderSignIn> ret;
        if (providerError != null) {
            ret = Future.failedFuture(new AuthenticationException("The sign-in provider reported: " + providerError));
        } else if (flow == null || code == null || state == null || !flow.authorization().state().equals(state)) {
            ret = Future.failedFuture(new AuthenticationException("The sign-in did not complete; start again"));
        } else {
            ret = client(provider)
                    .compose(client -> client.verifiedClaims(code, callbackUrl(provider), flow.authorization()))
                    .recover(t -> {
                        log.warn("A sign-in with {} could not be verified: {}", provider.key(), t.getMessage());
                        return Future.failedFuture(new AuthenticationException("The sign-in could not be verified; start again", t));
                    })
                    .compose(claims -> OAuth2Util.isEmailProven(claims, provider)
                            ? Future.succeededFuture(new ProviderSignIn(provider, OAuth2Util.stringClaim(claims, "email"),
                                                                        OAuth2Util.stringClaim(claims, "name"), flow))
                            : Future.failedFuture(new AuthenticationException(
                                    "The provider does not confirm that the account's email address is verified; "
                                    + "sign up with your email address instead")));
        }
        return ret;
    }

    @PreDestroy
    void stop() {
        clients.values().forEach(client -> {
            if (client.succeeded()) {
                client.result().close();
            }
        });
    }

    private void register(OidcProviderType provider, OidcClientProperties configured, CustomerOidcRegistration registration) {
        if (StringUtils.isNotBlank(configured.getClientId())) {
            registrations.put(provider, registration.setProvider(provider)
                                                    .setClientId(configured.getClientId())
                                                    .setClientSecret(configured.getClientSecret()));
        }
    }

    // created on the provider's first sign-in; a discovery that fails is retried by the next
    private Future<OidcClient> client(OidcProviderType provider) {
        CustomerOidcRegistration registration = registrations.get(provider);
        return clients.compute(provider, (key, existing) -> existing == null || existing.failed()
                ? oidcClientService.createClient(registration, registration.getClientSecret())
                : existing);
    }

    private static List<String> scopes(OidcProviderType provider) {
        return provider == OidcProviderType.GITHUB ? GITHUB_SCOPES : OIDC_SCOPES;
    }

    private String callbackUrl(OidcProviderType provider) {
        return properties.getLicenses().getPublicBaseUrl() + "/api/auth/provider/" + provider.key() + "/callback";
    }

    private static String flowSessionKey(OidcProviderType provider) {
        return FLOW_SESSION_KEY_PREFIX + provider.key();
    }
}
