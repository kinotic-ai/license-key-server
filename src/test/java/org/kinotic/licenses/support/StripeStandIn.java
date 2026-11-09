package org.kinotic.licenses.support;

import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.json.JsonObject;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for the Stripe API the server reads subscriptions from: a test reports a subscription as Stripe would
 * have it, and the server's {@link StripeClient} reads it from here, through the same requests and parsing it makes
 * against Stripe.
 */
@TestConfiguration
public class StripeStandIn {

    private static final String SUBSCRIPTIONS_PATH = "/v1/subscriptions/";
    private static final Map<String, JsonObject> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    /** Stripe has the subscription as given from now on. */
    public static JsonObject report(JsonObject subscription) {
        SUBSCRIPTIONS.put(subscription.getString("id"), subscription);
        return subscription;
    }

    @Bean
    HttpServer stripeStandInServer() throws IOException {
        HttpServer ret = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ret.createContext(SUBSCRIPTIONS_PATH, StripeStandIn::subscription);
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

    private static void subscription(HttpExchange exchange) throws IOException {
        JsonObject subscription = SUBSCRIPTIONS.get(exchange.getRequestURI().getPath().substring(SUBSCRIPTIONS_PATH.length()));
        int status = subscription != null ? 200 : 404;
        JsonObject body = subscription != null
                ? subscription
                : new JsonObject().put("error", new JsonObject().put("type", "invalid_request_error").put("message", "No such subscription"));
        byte[] bytes = body.encode().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
