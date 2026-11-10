package org.kinotic.licenses.support;

import java.util.Map;

/**
 * A request the server made to {@link StripeStandIn}: its method, path and form parameters, keyed as Stripe encodes
 * them, such as {@code line_items[0][price]}.
 */
public record StripeRequest(String method, String path, Map<String, String> params) {
}
