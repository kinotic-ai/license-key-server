package org.kinotic.licenses.internal.api.rest;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.springframework.stereotype.Component;

/**
 * Serves the one Vue app from the {@code webroot} classpath directory on the API port, so a single port serves both
 * hostnames; the app itself shows the public pages or the staff dashboard by which host it was served on. The tab
 * icon follows the host too: the staff dashboard's is in its sign-in page's color.
 */
@Component
@RequiredArgsConstructor
public class SpaRoutes implements SuppliesGatewayRoutes {

    private static final String WEBROOT = "webroot";
    private static final String INDEX = "/index.html";
    private static final String API_PREFIX = "/api/";
    private static final String FAVICON_PATHS = "/favicon\\.(?:svg|ico)";
    private static final String STAFF_FAVICON_PREFIX = "/staff-";
    // after every API route, whatever order the route beans mount in
    private static final int STATIC_ORDER = 1_000;

    private final KinoticLicensesProperties properties;

    @Override
    public void mountRoutes(Router router) {
        // index.html names one icon for both hostnames, so the staff host answers it with its own file
        router.getWithRegex(FAVICON_PATHS).order(STATIC_ORDER - 1).handler(ctx -> {
            if (RestSupport.addressedTo(ctx, properties.getLicenses().getStaffBaseUrl())) {
                ctx.reroute(STAFF_FAVICON_PREFIX + ctx.normalizedPath().substring(1));
            } else {
                ctx.next();
            }
        });
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
