package org.kinotic.licenses.internal.api.rest;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.springframework.stereotype.Component;

/**
 * Serves the one Vue app from the {@code webroot} classpath directory on the API port, so a single port serves both
 * hostnames; the app itself shows the public pages or the staff dashboard by which host it was served on.
 */
@Component
public class SpaRoutes implements SuppliesGatewayRoutes {

    private static final String WEBROOT = "webroot";
    private static final String INDEX = "/index.html";
    private static final String API_PREFIX = "/api/";
    // after every API route, whatever order the route beans mount in
    private static final int STATIC_ORDER = 1_000;

    @Override
    public void mountRoutes(Router router) {
        router.route().order(STATIC_ORDER).handler(StaticHandler.create(WEBROOT));
        // a deep link into the app is answered with the app, which routes it client-side
        router.get().order(STATIC_ORDER + 1).handler(ctx -> {
            if (ctx.normalizedPath().startsWith(API_PREFIX)) {
                ctx.next();
            } else {
                ctx.reroute(INDEX);
            }
        });
    }
}
