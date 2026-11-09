package org.kinotic.licenses.api.services;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.licenses.api.model.CustomerDetail;
import org.kinotic.licenses.api.model.CustomerSummary;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.security.LicenseZones;

import java.util.List;
import java.util.Map;

/**
 * The staff dashboard's view of customers and the actions staff take on a license.
 */
@Publish
@Version("1.0.0")
@Zone(LicenseZones.STAFF)
public interface StaffCustomerService {

    /**
     * Every customer with the state of its newest license and that license's newest key, newest customer first.
     */
    Future<List<CustomerSummary>> findCustomerSummaries();

    /**
     * A customer with its contacts, licenses, keys and agreement acceptances.
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
     * Issues and emails another key for the license's current term.
     *
     * @throws IllegalArgumentException when no license has the id
     */
    Future<IssuedKey> issueReplacementKey(String licenseId);

    /**
     * Emails the license's newest key to the customer's contacts again.
     *
     * @throws IllegalArgumentException when no license has the id or it has no key
     */
    Future<Void> resendKey(String licenseId);

    /**
     * Issues the trial key: a key for development and testing with every feature that never expires, which Kinotic's
     * own repository commits for its CI and tests.
     */
    Future<IssuedKey> issueTrialKey();

    /**
     * The public key license keys are signed with, as an EC P-256 JSON Web Key, for verifiers to trust: committed to
     * kinotic-ai/kinotic with the trial key.
     */
    Future<Map<String, Object>> getSigningKey();
}
