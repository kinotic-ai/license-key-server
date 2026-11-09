package org.kinotic.licenses.support;

import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.Plan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for the parts of the Stripe API the server calls: a test reports a subscription as Stripe would have it,
 * the price lists are those {@link #PRICES} seeds, and every request the server makes is recorded for a test to read.
 * The server's {@link StripeClient} reaches it through the same requests and parsing it makes against Stripe.
 */
@TestConfiguration
public class StripeStandIn {

    /** The complete price list a new license starts on: the highest with a Price for every paid plan and period. */
    public static final int CURRENT_PRICE_LIST = 2;
    /** An older complete price list, which a license that started on it keeps. */
    public static final int OLDER_PRICE_LIST = 1;

    private static final String SUBSCRIPTIONS_PATH = "/v1/subscriptions/";
    private static final Map<String, JsonObject> SUBSCRIPTIONS = new ConcurrentHashMap<>();
    private static final List<JsonObject> PRICES = seedPrices();
    private static final List<StripeRequest> REQUESTS = Collections.synchronizedList(new ArrayList<>());

    /** Stripe has the subscription as given from now on. */
    public static JsonObject report(JsonObject subscription) {
        SUBSCRIPTIONS.put(subscription.getString("id"), subscription);
        return subscription;
    }

    /** The subscription as Stripe has it now, or {@code null} when it has none with the id. */
    public static JsonObject subscription(String id) {
        return SUBSCRIPTIONS.get(id);
    }

    /** The id of the Price with the lookup key. */
    public static String priceId(String lookupKey) {
        return "price_" + lookupKey;
    }

    /** The requests the server made with the method to a path starting with the prefix, oldest first. */
    public static List<StripeRequest> requests(String method, String pathPrefix) {
        synchronized (REQUESTS) {
            return REQUESTS.stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).toList();
        }
    }

    @Bean
    HttpServer stripeStandInServer() throws IOException {
        HttpServer ret = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ret.createContext("/v1/", StripeStandIn::handle);
        ret.start();
        return ret;
    }

    @Bean
    @Primary
    StripeClient standInStripeClient(HttpServer stripeStandInServer) {
        return StripeClient.builder()
                           .setApiKey("sk_test_placeholder")
                           .setApiBase("http://localhost:" + stripeStandInServer.getAddress().getPort())
                           .build();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = form(body.isEmpty() ? exchange.getRequestURI().getRawQuery() : body);
        REQUESTS.add(new StripeRequest(method, path, params));
        JsonObject response;
        if (path.startsWith(SUBSCRIPTIONS_PATH)) {
            response = subscriptionRequest(method, path.substring(SUBSCRIPTIONS_PATH.length()));
        } else if (path.equals("/v1/prices")) {
            response = new JsonObject().put("object", "list").put("url", "/v1/prices").put("has_more", false)
                                       .put("data", new JsonArray(new ArrayList<>(PRICES)));
        } else if (path.equals("/v1/customers")) {
            response = new JsonObject().put("id", "cus_" + id()).put("object", "customer");
        } else if (path.equals("/v1/checkout/sessions")) {
            String sessionId = "cs_test_" + id();
            response = new JsonObject().put("id", sessionId).put("object", "checkout.session")
                                       .put("url", "https://checkout.stripe.test/" + sessionId);
        } else {
            response = null;
        }
        respond(exchange, response);
    }

    private static JsonObject subscriptionRequest(String method, String id) {
        JsonObject ret = SUBSCRIPTIONS.get(id);
        if (ret != null && method.equals("DELETE")) {
            ret = ret.copy().put("status", "canceled");
            SUBSCRIPTIONS.put(id, ret);
        }
        return ret;
    }

    private static void respond(HttpExchange exchange, JsonObject response) throws IOException {
        int status = response != null ? 200 : 404;
        JsonObject body = response != null
                ? response
                : new JsonObject().put("error", new JsonObject().put("type", "invalid_request_error").put("message", "No such object"));
        byte[] bytes = body.encode().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> ret = new LinkedHashMap<>();
        if (encoded != null && !encoded.isEmpty()) {
            for (String pair : encoded.split("&")) {
                String[] parts = pair.split("=", 2);
                ret.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
        }
        return ret;
    }

    // price lists 1 and 2 are complete; 3 lacks most Prices, so it is not yet a price list a license starts on
    private static List<JsonObject> seedPrices() {
        List<JsonObject> ret = new ArrayList<>();
        for (int priceList : List.of(OLDER_PRICE_LIST, CURRENT_PRICE_LIST)) {
            for (Plan plan : List.of(Plan.SMALL, Plan.MEDIUM, Plan.LARGE)) {
                for (BillingPeriod period : BillingPeriod.values()) {
                    ret.add(price(lookupKey(priceList, plan, period), monthlyCents(plan, priceList), period));
                }
            }
        }
        ret.add(price(lookupKey(3, Plan.SMALL, BillingPeriod.MONTHLY), 600000, BillingPeriod.MONTHLY));
        // a Price of another product, which no price list counts
        ret.add(new JsonObject().put("id", "price_other").put("object", "price").put("active", true).put("currency", "usd")
                                .put("unit_amount", 100).put("unit_amount_decimal", "100").put("lookup_key", "something-else"));
        return ret;
    }

    /** The lookup key of a paid plan's monthly Price on the current price list. */
    public static String monthly(Plan plan) {
        return lookupKey(CURRENT_PRICE_LIST, plan, BillingPeriod.MONTHLY);
    }

    /** The lookup key of a paid plan's Price, {@code kinotic-<priceList>-<plan>-<period>}. */
    public static String lookupKey(int priceList, Plan plan, BillingPeriod period) {
        return "kinotic-" + priceList + "-" + plan.name().toLowerCase(Locale.ROOT) + "-" + period.name().toLowerCase(Locale.ROOT);
    }

    private static long monthlyCents(Plan plan, int priceList) {
        long dollars = switch (plan) {
            case SMALL -> 5000;
            case MEDIUM -> 10000;
            default -> 20000;
        };
        // the older price list is cheaper, so a test can tell which one a purchase was priced on
        return (priceList == OLDER_PRICE_LIST ? dollars - 1000 : dollars) * 100;
    }

    private static JsonObject price(String lookupKey, long monthlyCents, BillingPeriod period) {
        long cents = period == BillingPeriod.ANNUAL ? new BigDecimal(monthlyCents * 12).multiply(new BigDecimal("0.9")).longValueExact() : monthlyCents;
        return new JsonObject().put("id", priceId(lookupKey)).put("object", "price").put("active", true).put("currency", "usd")
                               .put("unit_amount", cents).put("unit_amount_decimal", Long.toString(cents)).put("lookup_key", lookupKey)
                               .put("recurring", new JsonObject().put("interval", period == BillingPeriod.ANNUAL ? "year" : "month")
                                                                 .put("interval_count", 1));
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
