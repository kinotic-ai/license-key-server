package org.kinotic.licenses.internal.api.services;

import lombok.Getter;
import org.kinotic.core.api.exceptions.AuthenticationException;

/**
 * A sign-in through a provider failed. The message is for the log; the person is told only the {@link #getType() type}.
 */
@Getter
public class ProviderSignInException extends AuthenticationException {

    private final ProviderSignInFailureType type;

    public ProviderSignInException(ProviderSignInFailureType type, String message) {
        super(message);
        this.type = type;
    }

    public ProviderSignInException(ProviderSignInFailureType type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }
}
