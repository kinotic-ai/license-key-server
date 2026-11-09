package org.kinotic.licenses.api.config;

import jakarta.validation.Valid;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.kinotic.core.api.config.KinoticProperties;
import org.springframework.stereotype.Component;

/**
 * The {@code kinotic.*} properties of this server: Kinotic RPC's own settings, and the license server's under
 * {@code kinotic.licenses}.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
@Component
public class KinoticLicensesProperties extends KinoticProperties {

    /**
     * The license key server's settings, {@code kinotic.licenses.*}.
     */
    @Valid
    private LicensesProperties licenses = new LicensesProperties();

}
