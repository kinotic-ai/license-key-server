package org.kinotic.licenses.internal.api.rest;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.internal.api.services.LicenseSigningKey;
import org.springframework.stereotype.Component;

/**
 * The routes anyone may call on the public host.
 */
@Component
@RequiredArgsConstructor
public class PublicRoutes implements SuppliesGatewayRoutes {

    private final LicenseSigningKey signingKey;

    @Override
    public void mountRoutes(Router router) {
        router.get("/api/signing-key").handler(this::signingKey);
    }

    /** The public half of the key license keys are signed with, as a JSON Web Key. */
    private void signingKey(RoutingContext ctx) {
        RestSupport.respondJson(ctx, 200, signingKey.publicJwk());
    }
}
