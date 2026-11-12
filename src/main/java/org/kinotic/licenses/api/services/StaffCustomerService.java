package org.kinotic.licenses.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.licenses.api.model.CustomerDetail;
import org.kinotic.licenses.api.model.CustomerSummary;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.security.LicenseZones;

import java.util.List;

/**
 * The staff dashboard's view of customers and the actions staff take on a license.
 */
@Publish
@Version("1.0.0")
@Zone(LicenseZones.STAFF)
public interface StaffCustomerService {

    /**
     * Every customer with the state of its newest license (plan, billing period, price list, paid-through date, next
     * anniversary and flags) and that license's newest key, newest customer first.
     */
    Future<List<CustomerSummary>> findCustomerSummaries();

    /**
     * The customers whose newest license reached its anniversary without its figures confirmed, oldest anniversary
     * first. The license went on as it was; staff follow up with the customer.
     */
    Future<List<CustomerSummary>> findRevenueUnconfirmed();

    /**
     * A customer with its contacts, licenses, their clusters and keys, and agreement acceptances.
     *
     * @throws IllegalArgumentException when no customer has the id
     */
    Future<CustomerDetail> getCustomerDetail(String customerId);

    /**
     * Revokes a license: no further key is issued for it. Issued keys work until they expire.
     *
     * @throws IllegalArgumentException when no license has the id
     */
    Future<License> revokeLicense(String licenseId);

    /**
     * The public key license keys are signed with: the X.509 SubjectPublicKeyInfo of the EC P-256 key in Base64, the
     * form of {@code LicenseVerifier.TRUSTED_PUBLIC_KEY} in kinotic-ai/kinotic, which must equal it.
     */
    Future<String> getSigningPublicKey();
}
