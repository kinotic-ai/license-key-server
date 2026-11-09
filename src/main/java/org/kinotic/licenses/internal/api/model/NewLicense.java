package org.kinotic.licenses.internal.api.model;

import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;

/**
 * A license just created with its first key.
 */
public record NewLicense(License license, IssuedKey key) {
}
