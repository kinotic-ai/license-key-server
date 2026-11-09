package org.kinotic.licenses;

import org.kinotic.core.api.annotations.EnableKinotic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Minds Ignited license key server: sells, issues and tracks Kinotic Enterprise license keys. It runs on
 * Kinotic RPC alone (kinotic-core and the API gateway) with its own single-node Ignite cluster and a Postgres
 * database, and never requires a Kinotic OS server.
 */
@SpringBootApplication
@EnableKinotic
public class LicenseKeyServerApplication {

    static void main(String[] args) {
        SpringApplication.run(LicenseKeyServerApplication.class, args);
    }

}
