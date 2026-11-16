package org.kinotic.licenses.internal.api.rest;

import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.RoutingContext;
import org.kinotic.core.api.security.ConnectedInfo;
import org.kinotic.core.api.security.Participant;
import org.kinotic.core.api.security.SessionBinding;
import org.kinotic.licenses.api.security.LicenseParticipant;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

/**
 * What the REST routes share: reading a JSON body, the client's address, answering with JSON, and the page a request
 * is signed in on.
 */
final class RestSupport {

    private static final String JSON = "application/json";

    private RestSupport() {}

    /**
     * The request body as a JSON object.
     *
     * @throws IllegalArgumentException when the body is missing or not a JSON object, which the gateway's failure
     *                                  handler answers with {@code 400}
     */
    static JsonObject jsonBody(RoutingContext ctx) {
        JsonObject ret;
        try {
            ret = ctx.body().asJsonObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid request body", e);
        }
        if (ret == null) {
            throw new IllegalArgumentException("A JSON body is required");
        }
        return ret;
    }

    /**
     * A string field of a body that is required to be present and non-blank, trimmed.
     *
     * @throws IllegalArgumentException when it is missing, blank or not a string
     */
    static String requiredString(JsonObject body, String name) {
        return requiredUntrimmedString(body, name).trim();
    }

    /**
     * A string field of a body that is required to be present and non-blank, exactly as sent, as a password is.
     *
     * @throws IllegalArgumentException when it is missing, blank or not a string
     */
    static String requiredUntrimmedString(JsonObject body, String name) {
        Object value = body.getValue(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("The field " + name + " is required");
        }
        return string;
    }

    /**
     * The address of the client: the last address of {@code X-Forwarded-For}, which the one reverse proxy in front of
     * the server appends, else the connection's.
     */
    static String clientAddress(RoutingContext ctx) {
        String forwarded = ctx.request().getHeader("X-Forwarded-For");
        String ret;
        if (forwarded != null && !forwarded.isBlank()) {
            // the client writes any entries it likes to the left; only the one the proxy appended can be trusted
            String[] addresses = forwarded.split(",");
            ret = addresses[addresses.length - 1].trim();
        } else {
            ret = ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().hostAddress() : "unknown";
        }
        return ret;
    }

    static void respondJson(RoutingContext ctx, JsonMapper mapper, Object body) {
        ctx.response().putHeader("Content-Type", JSON).end(mapper.writeValueAsString(body));
    }

    static void respondJson(RoutingContext ctx, int status, JsonObject body) {
        ctx.response().setStatusCode(status).putHeader("Content-Type", JSON).end(body.encode());
    }

    /**
     * The participant signed in on the page that sent the request, or {@code null} when it has none.
     */
    static LicenseParticipant signedIn(RoutingContext ctx) {
        ConnectedInfo connectedInfo = SessionBinding.connectedInfo(ctx);
        Participant participant = connectedInfo != null ? connectedInfo.getParticipant() : null;
        return participant instanceof LicenseParticipant licenseParticipant ? licenseParticipant : null;
    }

    /**
     * Binds the participant to the page in the request's session, as a fresh session id.
     */
    static void establishSession(RoutingContext ctx, String origin, Participant participant) {
        // a new id on the privilege change, so a pre-sign-in id cannot ride the signed-in session
        ctx.session().regenerateId();
        ConnectedInfo connectedInfo = new ConnectedInfo();
        connectedInfo.setParticipant(participant);
        SessionBinding.bind(ctx, origin, connectedInfo);
    }

    /**
     * Whether the request was addressed to the authority of {@code baseUrl}: its host, and its port where the two
     * sites share a host and differ by port, as a developer's two vite servers do.
     */
    static boolean addressedTo(RoutingContext ctx, String baseUrl) {
        HostAndPort authority = ctx.request().authority();
        URI base = URI.create(baseUrl);
        int basePort = base.getPort() != -1 ? base.getPort() : "https".equalsIgnoreCase(base.getScheme()) ? 443 : 80;
        int requestPort = authority != null && authority.port() != -1 ? authority.port() : ctx.request().isSSL() ? 443 : 80;
        return authority != null && authority.host().equalsIgnoreCase(base.getHost()) && requestPort == basePort;
    }

    /**
     * A site path safe to redirect to: absolute, with no authority, backslash or control character; {@code null} for
     * anything else.
     */
    static String safeReturnPath(String path) {
        String ret = null;
        if (path != null && path.startsWith("/") && !path.startsWith("//") && path.indexOf('\\') < 0
                && path.chars().noneMatch(Character::isISOControl)) {
            ret = path;
        }
        return ret;
    }
}
