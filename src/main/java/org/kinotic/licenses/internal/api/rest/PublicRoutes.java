package org.kinotic.licenses.internal.api.rest;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;
import org.kinotic.common.api.oidc.OidcProviderType;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.SiteType;
import org.kinotic.licenses.internal.api.services.CustomerOidcService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * What a page reads before anyone signs in: which site it was served on, with the terms version it shows and the
 * providers customers sign up and sign in with.
 */
@Component
@RequiredArgsConstructor
public class PublicRoutes implements SuppliesGatewayRoutes {

    private final KinoticLicensesProperties properties;
    private final CustomerOidcService customerOidcService;

    @Override
    public void mountRoutes(Router router) {
        router.get("/api/site").handler(this::site);
    }

    private void site(RoutingContext ctx) {
        SiteType site = RestSupport.addressedTo(ctx, properties.getLicenses().getStaffBaseUrl()) ? SiteType.STAFF : SiteType.PUBLIC;
        List<String> signInProviders = customerOidcService.providers().stream().map(OidcProviderType::key).toList();
        RestSupport.respondJson(ctx, 200, new JsonObject().put("site", site.name())
                                                          .put("termsVersion", AgreementTerms.VERSION)
                                                          .put("signInProviders", signInProviders));
    }
}
