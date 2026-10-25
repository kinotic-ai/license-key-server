package org.kinotic.licenses.internal.api.security;

import io.vertx.core.Future;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.core.api.security.Participant;
import org.kinotic.core.api.security.SecurityService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The gateway's {@link SecurityService}. Every sign-in to this server happens in a browser and is bound to the
 * browser session by the sign-in routes, so a connection that presents that session is already authenticated when
 * the gateway upgrades it. A connection with no signed-in session carries no credentials this server accepts.
 */
@Component
public class LicenseSecurityService implements SecurityService {

    @Override
    public Future<Participant> authenticate(Map<String, String> authenticationInfo) {
        return Future.failedFuture(new AuthenticationException("Sign in first"));
    }
}
