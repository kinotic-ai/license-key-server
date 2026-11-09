package org.kinotic.licenses.internal.api.rest;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.SiteType;
import org.kinotic.licenses.internal.api.services.StripeBillingService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a page reads before anyone signs in: which site it was served on with the terms version it shows, and the
 * plans with their prices, which the kinotic.ai pricing page reads as well.
 */
@Component
@RequiredArgsConstructor
public class PublicRoutes implements SuppliesGatewayRoutes {

    private final KinoticLicensesProperties properties;
    private final StripeBillingService billingService;
    private final JsonMapper jsonMapper;

    @Override
    public void mountRoutes(Router router) {
        router.get("/api/site").handler(this::site);
        router.get("/api/plans").handler(this::plans);
    }

    private void site(RoutingContext ctx) {
        SiteType site = RestSupport.addressedTo(ctx, properties.getLicenses().getStaffBaseUrl()) ? SiteType.STAFF : SiteType.PUBLIC;
        RestSupport.respondJson(ctx, 200, new JsonObject().put("site", site.name()).put("termsVersion", AgreementTerms.VERSION));
    }

    private void plans(RoutingContext ctx) {
        billingService.listPlans()
                      .onSuccess(plans -> RestSupport.respondJson(ctx, jsonMapper, plans))
                      .onFailure(ctx::fail);
    }
}
