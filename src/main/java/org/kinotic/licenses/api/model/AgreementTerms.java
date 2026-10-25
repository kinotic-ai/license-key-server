package org.kinotic.licenses.api.model;

/**
 * The order terms and Kinotic Enterprise License a customer accepts by click-through before paying or requesting a
 * free license. The version names the text in force; every acceptance records the version it accepted.
 */
public final class AgreementTerms {

    /** The version of the terms the site shows and the server records. */
    public static final String VERSION = "2026-10";

    private AgreementTerms() {}
}
