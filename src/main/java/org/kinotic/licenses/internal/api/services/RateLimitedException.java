package org.kinotic.licenses.internal.api.services;

/**
 * A caller made more sign-in requests or attempts than the limit allows within the window.
 */
public class RateLimitedException extends RuntimeException {

    public RateLimitedException(String message) {
        super(message);
    }
}
