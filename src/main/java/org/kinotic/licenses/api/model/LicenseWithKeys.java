package org.kinotic.licenses.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * A license with every key issued for it, newest first.
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
public class LicenseWithKeys {
    private License license;
    private List<IssuedKey> keys;
}
