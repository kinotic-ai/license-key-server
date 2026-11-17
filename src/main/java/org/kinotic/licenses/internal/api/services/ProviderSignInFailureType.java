package org.kinotic.licenses.internal.api.services;

import java.util.Locale;

/**
 * Why a sign-in through a provider failed. The login page the browser returns to is told the type's {@link #code()}
 * and shows the message it keeps for it.
 */
public enum ProviderSignInFailureType {

    /** The person declined the sign-in at the provider. */
    ACCESS_DENIED,

    /** The callback matches no sign-in the browser started. */
    STATE_MISMATCH,

    /** The provider does not prove that the account's email address belongs to the person. */
    EMAIL_NOT_VERIFIED,

    /** The account is not a staff account. */
    NOT_STAFF,

    /** Any other failure, whose detail is logged. */
    FAILED;

    /** The code the login page is told, in the {@code error} of the URL it is returned to with. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
