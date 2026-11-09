package org.kinotic.licenses.support;

import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;

import java.util.concurrent.CompletableFuture;

/**
 * The renewal service as Kinotic declares it in its own code, from the contract in the README alone: the same
 * namespace, name, zone and version, and no type of this server's.
 */
@Publish(namespace = "org.kinotic.license", name = "LicenseRenewalService")
@Version("1.0.0")
@Zone("licensee")
public interface KinoticLicenseRenewalService {

    CompletableFuture<String> renew();
}
