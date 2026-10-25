package org.kinotic.licenses.support;

import com.stripe.Stripe;
import com.stripe.net.Webhook;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds Stripe webhook events as Stripe would deliver them, signed with the test profile's webhook secret, so the
 * webhook route is exercised end to end without Stripe.
 */
public final class StripeEvents {

    /** The webhook secret of the test profile. */
    public static final String WEBHOOK_SECRET = "whsec_test_placeholder";

    private StripeEvents() {}

    public static JsonObject event(String type, JsonObject object) {
        return new JsonObject()
                .put("id", "evt_" + UUID.randomUUID().toString().replace("-", ""))
                .put("object", "event")
                .put("api_version", Stripe.API_VERSION)
                .put("created", Instant.now().getEpochSecond())
                .put("type", type)
                .put("data", new JsonObject().put("object", object));
    }

    public static JsonObject subscription(String id, String status, String lookupKey, Instant periodEnd) {
        JsonObject price = new JsonObject().put("id", "price_" + lookupKey).put("object", "price").put("lookup_key", lookupKey);
        JsonObject item = new JsonObject().put("id", "si_1").put("object", "subscription_item")
                                          .put("current_period_end", periodEnd.getEpochSecond()).put("price", price);
        return new JsonObject()
                .put("id", id)
                .put("object", "subscription")
                .put("status", status)
                .put("items", new JsonObject().put("object", "list").put("data", new JsonArray().add(item)));
    }

    public static JsonObject invoice(String subscriptionId, Instant paidAt) {
        JsonObject ret = new JsonObject()
                .put("id", "in_" + UUID.randomUUID().toString().replace("-", ""))
                .put("object", "invoice")
                .put("parent", new JsonObject().put("type", "subscription_details")
                                               .put("subscription_details", new JsonObject().put("subscription", subscriptionId)));
        if (paidAt != null) {
            ret.put("status_transitions", new JsonObject().put("paid_at", paidAt.getEpochSecond()));
        }
        return ret;
    }

    /** The {@code Stripe-Signature} header Stripe sends for the payload, signed now. */
    public static String signature(String payload) throws Exception {
        return signature(payload, WEBHOOK_SECRET);
    }

    public static String signature(String payload, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        return "t=" + timestamp + ",v1=" + Webhook.Util.computeHmacSha256(secret, timestamp + "." + payload);
    }
}
