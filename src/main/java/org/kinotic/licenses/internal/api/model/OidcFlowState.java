package org.kinotic.licenses.internal.api.model;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.ClusterSerializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.kinotic.common.api.oidc.OidcAuthorization;

/**
 * One in-flight sign-in at a provider: the state, nonce and PKCE verifier the callback checks, and the page the sign-in
 * started from. It lives in the browser session between the redirect to the provider and the callback.
 */
@Getter
@Accessors(fluent = true)
@NoArgsConstructor
@AllArgsConstructor
public class OidcFlowState implements ClusterSerializable {

    /** The state, nonce and PKCE verifier the callback is held to. */
    private OidcAuthorization authorization;
    /** The origin of the page that started the sign-in, which the login is bound to; {@code null} when it named none. */
    private String origin;
    /** The path on the site to land on; {@code null} for the site's default. */
    private String returnPath;

    @Override
    public void writeToBuffer(Buffer buffer) {
        new JsonObject()
                .put("state", authorization.state())
                .put("nonce", authorization.nonce())
                .put("pkceVerifier", authorization.pkceVerifier())
                .put("origin", origin)
                .put("returnPath", returnPath)
                .writeToBuffer(buffer);
    }

    @Override
    public int readFromBuffer(int pos, Buffer buffer) {
        JsonObject json = new JsonObject();
        int ret = json.readFromBuffer(pos, buffer);
        this.authorization = new OidcAuthorization(json.getString("state"), json.getString("nonce"),
                                                   json.getString("pkceVerifier"));
        this.origin = json.getString("origin");
        this.returnPath = json.getString("returnPath");
        return ret;
    }
}
