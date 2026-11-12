package org.kinotic.licenses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import org.kinotic.licenses.api.model.AgreementAcceptance;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.CustomerAccount;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.FreeLicenseRequestStatus;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.PublicityConsent;
import org.kinotic.licenses.api.services.StaffCustomerService;
import org.kinotic.licenses.api.services.StaffFreeLicenseRequestService;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
import org.kinotic.licenses.internal.api.repositories.FreeLicenseRequestRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.FreeLicenseService;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeStandIn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A signed-in customer requests a free license with the terms accepted and the figures it certifies; staff approve or
 * reject it, the customer renews by requesting again, and staff see the customer with its acceptances.
 */
@ExtendWith(OutputCaptureExtension.class)
class FreeLicenseTest extends ServerTest {

    @Autowired
    private StaffFreeLicenseRequestService staffRequests;
    @Autowired
    private StaffCustomerService staffCustomers;
    @Autowired
    private FreeLicenseRequestRepository requestRepository;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private AgreementAcceptanceRepository acceptanceRepository;
    @Autowired
    private LicenseService licenseService;
    @Autowired
    private FreeLicenseService freeLicenseService;

    @Test
    void aRequestIsApprovedAndLaterRenewedByRequestingAgain(CapturedOutput output) throws Exception {
        String email = "grace-" + UUID.randomUUID() + "@tiny.example";
        FreeLicenseRequest request = requestAsNewCustomer(output, email);
        String requestId = request.getId();

        assertThat(request.getStatus()).isEqualTo(FreeLicenseRequestStatus.PENDING);
        assertThat(request.getOrganization()).isEqualTo("Tiny Startup LLC");
        assertThat(request.getContactEmail()).isEqualTo(email);
        assertThat(request.getCertification().getFigures().getAnnualRevenue()).isEqualByComparingTo(new BigDecimal("250000.00"));
        assertThat(request.getCertification().getIntendedUse()).isEqualTo("Internal tools for a five-person team");
        List<AgreementAcceptance> acceptances = await(acceptanceRepository.findByCustomerId(request.getCustomerId()));
        assertThat(acceptances).hasSize(1);
        assertThat(acceptances.getFirst().getTermsVersion()).isEqualTo(AgreementTerms.VERSION);
        assertThat(acceptances.getFirst().getAcceptedByEmail()).isEqualTo(email);
        // the Free terms let Minds Ignited name the customer as a Kinotic customer
        assertThat(acceptances.getFirst().getPublicityConsent()).isEqualTo(PublicityConsent.CUSTOMER_NAME);
        assertThat(await(staffRequests.findPending())).extracting(FreeLicenseRequest::getId).contains(requestId);

        FreeLicenseRequest approved = asStaff(() -> staffRequests.approve(requestId));
        assertThat(approved.getStatus()).isEqualTo(FreeLicenseRequestStatus.APPROVED);
        assertThat(approved.getReviewedBy()).isEqualTo("staff@mindsignited.com");
        assertThat(approved.getReviewedAt()).isNotNull();
        License license = await(licenseRepository.findFreeLicense(request.getCustomerId()));
        assertThat(license.getPlan()).isEqualTo(Plan.FREE);
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(license.getStripeSubscriptionId()).isNull();
        assertThat(license.getSeats()).isEqualTo(1);
        assertThat(await(licenseService.keysOf(license.getId()))).isEmpty();
        assertThat(output.getAll()).contains("Your free Kinotic Enterprise license is approved");

        assertThatThrownBy(() -> asStaff(() -> staffRequests.approve(requestId)))
                .hasCauseInstanceOf(IllegalStateException.class);

        // the customer adds its cluster, which gets its first key
        CustomerContact contact = await(contactRepository.findByEmail(email)).getFirst();
        LicensedCluster cluster = asCustomer(contact, () -> customerAccount.addCluster(license.getId(), UUID.randomUUID().toString(), "production"));
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);

        // a free license renews with its yearly confirmation, not with another request
        assertThatThrownBy(() -> asCustomer(contact, () -> customerAccount.requestFreeLicense(certification("Asking again"), AgreementTerms.VERSION)))
                .hasCauseInstanceOf(IllegalStateException.class);
        sql("UPDATE license SET term_end = now() + interval '10 days' WHERE id = $1", license.getId());
        License due = await(licenseRepository.findById(license.getId()));
        asCustomer(contact, () -> customerAccount.confirmRevenue(due.getId(), new CompanyFigures().setAnnualRevenue(new BigDecimal("400000"))
                                                                                                .setCapitalRaised(BigDecimal.ZERO)));
        FreeLicenseRequest renewal = await(requestRepository.findByCustomerId(request.getCustomerId())).getFirst();
        assertThat(renewal.getStatus()).isEqualTo(FreeLicenseRequestStatus.PENDING);
        assertThat(renewal.getCertification().getIntendedUse()).isEqualTo("Internal tools for a five-person team");
        asStaff(() -> staffRequests.approve(renewal.getId()));
        License renewed = await(licenseRepository.findById(license.getId()));
        assertThat(renewed.getTermStart()).isEqualTo(due.getTermEnd());
        assertThat(renewed.getTermEnd()).isEqualTo(LicenseService.termEnd(due.getTermEnd()));
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(2)
                                                                .allSatisfy(key -> assertThat(key.getLicensedClusterId()).isEqualTo(cluster.getId()));

        CustomerAccount account = asCustomer(contact, customerAccount::getAccount);
        assertThat(account.getCustomer().getId()).isEqualTo(request.getCustomerId());
        assertThat(account.getLicenses()).hasSize(1);
        assertThat(account.getLicenses().getFirst().getKeys()).hasSize(2);
        assertThat(account.getLicenses().getFirst().getClusters()).extracting(LicensedCluster::getId).containsExactly(cluster.getId());
        assertThat(account.getFreeLicenseRequests()).hasSize(2);

        var detail = asStaff(() -> staffCustomers.getCustomerDetail(request.getCustomerId()));
        assertThat(detail.getAcceptances()).hasSize(1);
        assertThat(detail.getLicenses().getFirst().getConfirmations()).hasSize(1);
        assertThat(detail.getLicenses().getFirst().getKeys()).hasSize(2);
        assertThat(asStaff(staffCustomers::findCustomerSummaries))
                .anySatisfy(summary -> {
                    assertThat(summary.getId()).isEqualTo(request.getCustomerId());
                    assertThat(summary.getPlan()).isEqualTo(Plan.FREE);
                    assertThat(summary.getSeats()).isEqualTo(1);
                    assertThat(summary.getClusters()).isEqualTo(1);
                    assertThat(summary.getLatestKeyJti()).isEqualTo(await(licenseService.keysOf(license.getId())).getFirst().getJti());
                });
    }

    @Test
    void aRequestIsRejectedAndTheContactTold(CapturedOutput output) throws Exception {
        String requestId = requestAsNewCustomer(output, "rich-" + UUID.randomUUID() + "@big.example").getId();
        FreeLicenseRequest rejected = asStaff(() -> staffRequests.reject(requestId));
        assertThat(rejected.getStatus()).isEqualTo(FreeLicenseRequestStatus.REJECTED);
        assertThat(await(licenseRepository.findFreeLicense(rejected.getCustomerId()))).isNull();
        assertThat(output.getAll()).contains("About your Kinotic free license request");

        // a decision already made is not made again, and nothing is issued
        assertThatThrownBy(() -> asStaff(() -> staffRequests.approve(requestId))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(await(licenseRepository.findFreeLicense(rejected.getCustomerId()))).isNull();
    }

    @Test
    void aCustomerHasOneOpenRequestAtATime(CapturedOutput output) throws Exception {
        String email = "own-" + UUID.randomUUID() + "@tiny.example";
        FreeLicenseRequest first = requestAsNewCustomer(output, email);
        CustomerContact contact = await(contactRepository.findByEmail(email)).getFirst();

        assertThatThrownBy(() -> asCustomer(contact, () -> customerAccount.requestFreeLicense(certification("Asking twice"), AgreementTerms.VERSION)))
                .hasCauseInstanceOf(AlreadyExistsException.class);

        asStaff(() -> staffRequests.reject(first.getId()));
        FreeLicenseRequest again = asCustomer(contact, () -> customerAccount.requestFreeLicense(certification("Asking again"), AgreementTerms.VERSION));
        assertThat(again.getCustomerId()).isEqualTo(first.getCustomerId());
        // each request that was recorded recorded its acceptance
        assertThat(await(acceptanceRepository.findByCustomerId(first.getCustomerId()))).hasSize(2);
    }

    @Test
    void figuresThatSetAPaidPlanAreRefused() throws Exception {
        CustomerContact contact = await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
        for (CompanyFigures figures : List.of(new CompanyFigures().setAnnualRevenue(new BigDecimal("1000000")).setCapitalRaised(BigDecimal.ZERO),
                                              new CompanyFigures().setAnnualRevenue(new BigDecimal("400000")).setCapitalRaised(new BigDecimal("5000000")))) {
            assertThatThrownBy(() -> asCustomer(contact, () -> customerAccount.requestFreeLicense(
                    new FreeLicenseCertification().setIntendedUse("Too big").setFigures(figures), AgreementTerms.VERSION)))
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SMALL");
        }
        assertThat(await(requestRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();
    }

    @Test
    void nothingIsRecordedWithoutTheCurrentTerms() throws Exception {
        CustomerContact contact = await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
        assertThatThrownBy(() -> asCustomer(contact, () -> customerAccount.requestFreeLicense(certification("No terms"), null)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> asCustomer(contact, () -> customerAccount.requestFreeLicense(certification("Old terms"), "2000-01-01")))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(await(requestRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();
    }

    @Test
    void aLateRenewalKeepsTheLicensesAnniversary(CapturedOutput output) throws Exception {
        String requestId = requestAsNewCustomer(output, "lapsed-" + UUID.randomUUID() + "@tiny.example").getId();
        FreeLicenseRequest approved = asStaff(() -> staffRequests.approve(requestId));
        License license = await(licenseRepository.findFreeLicense(approved.getCustomerId()));
        sql("UPDATE license SET term_end = now() - interval '90 days' WHERE id = $1", license.getId());
        License late = await(licenseRepository.findById(license.getId()));
        // a cluster added with the year past holds a key that expired with it
        addCluster(late);

        await(freeLicenseService.requestRenewal(approved.getCustomerId(), new CompanyFigures().setAnnualRevenue(BigDecimal.ONE).setCapitalRaised(BigDecimal.ZERO)));
        FreeLicenseRequest renewal = await(requestRepository.findByCustomerId(approved.getCustomerId())).getFirst();
        asStaff(() -> staffRequests.approve(renewal.getId()));

        License renewed = await(licenseRepository.findById(license.getId()));
        assertThat(renewed.getTermStart()).isEqualTo(late.getTermEnd());
        assertThat(renewed.getTermEnd()).isEqualTo(LicenseService.termEnd(late.getTermEnd()));
        assertThat(await(licenseService.keysOf(license.getId())).getFirst().getExpiresAt())
                .isEqualTo(LicenseService.keyExpiry(renewed.getTermEnd()));
    }

    @Test
    void anApprovalThatStoppedMidWayIsCompletedByApprovingAgain(CapturedOutput output) throws Exception {
        String requestId = requestAsNewCustomer(output, "stalled-" + UUID.randomUUID() + "@tiny.example").getId();
        // what a server that stopped after claiming the approval leaves, once the claim has expired
        sql("UPDATE free_license_request SET status = 'APPROVED', reviewed_by = 'staff@mindsignited.com', reviewed_at = now() - interval '10 minutes',"
                    + " claimed_at = now() - interval '10 minutes' WHERE id = $1", requestId);
        assertThat(await(staffRequests.findPending())).extracting(FreeLicenseRequest::getId).contains(requestId);

        asStaff(() -> staffRequests.approve(requestId));

        FreeLicenseRequest completed = await(requestRepository.findById(requestId));
        assertThat(await(licenseRepository.findFreeLicense(completed.getCustomerId()))).isNotNull();
        assertThat(await(staffRequests.findPending())).extracting(FreeLicenseRequest::getId).doesNotContain(requestId);
        // the approval is complete, so it is decided for good
        assertThatThrownBy(() -> asStaff(() -> staffRequests.approve(requestId))).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void anApprovalThatStoppedAfterCreatingItsLicenseIsDecided(CapturedOutput output) throws Exception {
        String requestId = requestAsNewCustomer(output, "issued-" + UUID.randomUUID() + "@tiny.example").getId();
        String customerId = await(requestRepository.findById(requestId)).getCustomerId();
        sql("UPDATE free_license_request SET status = 'APPROVED', reviewed_by = 'staff@mindsignited.com', reviewed_at = now() - interval '10 minutes',"
                    + " claimed_at = now() - interval '10 minutes' WHERE id = $1", requestId);
        // the stopped approval got as far as creating the license, which completed the request with it
        License license = await(licenseService.createFreeLicense(await(customerRepository.findById(customerId)), StripeStandIn.CURRENT_PRICE_LIST, requestId));

        assertThat(await(staffRequests.findPending())).extracting(FreeLicenseRequest::getId).doesNotContain(requestId);
        assertThatThrownBy(() -> asStaff(() -> staffRequests.approve(requestId))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(await(licenseRepository.findById(license.getId())).getTermEnd()).isEqualTo(license.getTermEnd());
    }

    @Test
    void aCustomerOnlySeesItself() throws Exception {
        CustomerContact first = await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
        CustomerContact second = await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
        await(licenseService.createFreeLicense(await(customerRepository.findById(second.getCustomerId())), StripeStandIn.CURRENT_PRICE_LIST, null));
        CustomerAccount account = asCustomer(first, customerAccount::getAccount);
        assertThat(account.getCustomer().getId()).isEqualTo(first.getCustomerId());
        assertThat(account.getLicenses()).isEmpty();
    }

    // a new customer signed up as the page does, whose contact then requests a free license
    private FreeLicenseRequest requestAsNewCustomer(CapturedOutput output, String email) throws Exception {
        signUp(output, email, "Tiny Startup LLC");
        CustomerContact contact = await(contactRepository.findByEmail(email)).getFirst();
        return asCustomer(contact, () -> customerAccount.requestFreeLicense(certification("Internal tools for a five-person team"),
                                                                            AgreementTerms.VERSION));
    }

    private static FreeLicenseCertification certification(String intendedUse) {
        return new FreeLicenseCertification().setIntendedUse(intendedUse).setFigures(new CompanyFigures().setAnnualRevenue(new BigDecimal("250000")).setCapitalRaised(new BigDecimal("1000000")));
    }
}
