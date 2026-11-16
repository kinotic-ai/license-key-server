package org.kinotic.licenses.internal.api.model;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.ClusterSerializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import java.time.Instant;

/**
 * A sign-up a provider started: the person signed in at the provider, which proved the email address is theirs, and
 * the address has no account yet. It lives in the browser session until the person names the organization, which
 * creates the account, or until it expires.
 */
@Getter
@Accessors(fluent = true)
@NoArgsConstructor
@AllArgsConstructor
public class PendingProviderSignUp implements ClusterSerializable {

    /** The email address the provider proved belongs to the person. */
    private String email;
    /** The person's name as the provider gives it; {@code null} when it gives none. */
    private String name;
    private Instant expiresAt;

    @Override
    public void writeToBuffer(Buffer buffer) {
        new JsonObject()
                .put("email", email)
                .put("name", name)
                .put("expiresAt", expiresAt)
                .writeToBuffer(buffer);
    }

    @Override
    public int readFromBuffer(int pos, Buffer buffer) {
        JsonObject json = new JsonObject();
        int ret = json.readFromBuffer(pos, buffer);
        this.email = json.getString("email");
        this.name = json.getString("name");
        this.expiresAt = json.getInstant("expiresAt");
        return ret;
    }
}
