package org.kinotic.licenses;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.api.model.AgreementAcceptance;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.CustomerAccount;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.FreeLicenseCertification;
import org.kinotic.licenses.api.model.FreeLicenseRequest;
import org.kinotic.licenses.api.model.FreeLicenseRequestStatus;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.security.ParticipantType;
import org.kinotic.licenses.api.services.CustomerAccountService;
import org.kinotic.licenses.api.services.StaffCustomerService;
import org.kinotic.licenses.api.services.StaffFreeLicenseRequestService;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
import org.kinotic.licenses.internal.api.repositories.FreeLicenseRequestRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A free license is requested with the click-through acceptance and the self-reported figures, approved or rejected by
 * staff, and renewed by re-certifying from the customer account; staff see the customer with its acceptances.
 */
@ExtendWith(OutputCaptureExtension.class)
class FreeLicenseTest extends ServerTest {

    @Autowired
    private SecurityContext securityContext;
    @Autowired
    private StaffFreeLicenseRequestService staffRequests;
    @Autowired
    private StaffCustomerService staffCustomers;
    @Autowired
    private CustomerAccountService customerAccount;
    @Autowired
    private FreeLicenseRequestRepository requestRepository;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private AgreementAcceptanceRepository acceptanceRepository;
    @Autowired
    private LicenseService licenseService;

    @Test
    void aRequestIsApprovedAndLaterRenewedByReCertifying(CapturedOutput output) throws Exception {
        String email = "grace-" + UUID.randomUUID() + "@tiny.example";
        HttpResponse<Buffer> created = post("/api/free-requests", requestBody(email).put("acceptTerms", true), null);
        assertThat(created.statusCode()).isEqualTo(201);
        String requestId = created.bodyAsJsonObject().getString("id");

        FreeLicenseRequest request = await(requestRepository.findById(requestId));
        assertThat(request.getStatus()).isEqualTo(FreeLicenseRequestStatus.PENDING);
        assertThat(request.getOrganization()).isEqualTo("Tiny Startup LLC");
        assertThat(request.getContactEmail()).isEqualTo(email);
        assertThat(request.getAnnualRevenue()).isEqualByComparingTo(new BigDecimal("250000.00"));
        List<AgreementAcceptance> acceptances = await(acceptanceRepository.findByCustomerId(request.getCustomerId()));
        assertThat(acceptances).hasSize(1);
        assertThat(acceptances.getFirst().getTermsVersion()).isEqualTo(AgreementTerms.VERSION);
        assertThat(acceptances.getFirst().getAcceptedByEmail()).isEqualTo(email);
        assertThat(await(staffRequests.findPending())).extracting(FreeLicenseRequest::getId).contains(requestId);

        FreeLicenseRequest approved = asStaff(() -> staffRequests.approve(requestId));
        assertThat(approved.getStatus()).isEqualTo(FreeLicenseRequestStatus.APPROVED);
        assertThat(approved.getReviewedBy()).isEqualTo("staff@mindsignited.com");
        assertThat(approved.getReviewedAt()).isNotNull();
        License license = await(licenseRepository.findFreeLicense(request.getCustomerId()));
        assertThat(license.getPlan()).isEqualTo(Plan.FREE);
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(license.getStripeSubscriptionId()).isNull();
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
        assertThat(output.getAll()).contains("Your free Kinotic Enterprise license is approved");

        assertThatThrownBy(() -> asStaff(() -> staffRequests.approve(requestId)))
                .hasCauseInstanceOf(IllegalStateException.class);

        // the customer re-certifies from its account, and staff approve the renewal
        CustomerContact contact = await(contactRepository.findByCustomerId(request.getCustomerId())).getFirst();
        FreeLicenseRequest renewal = asCustomer(contact, () -> customerAccount.requestFreeRenewal(
                new FreeLicenseCertification().setAnnualRevenue(new BigDecimal("400000")).setCapitalRaised(BigDecimal.ZERO)
                                              .setIntendedUse("Still running our internal tools")));
        assertThat(renewal.getStatus()).isEqualTo(FreeLicenseRequestStatus.PENDING);
        asStaff(() -> staffRequests.approve(renewal.getId()));
        License renewed = await(licenseRepository.findById(license.getId()));
        assertThat(renewed.getTermStart()).isEqualTo(license.getTermEnd());
        assertThat(renewed.getTermEnd()).isEqualTo(LicenseService.termEnd(license.getTermEnd()));
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(2);

        CustomerAccount account = asCustomer(contact, customerAccount::getAccount);
        assertThat(account.getCustomer().getId()).isEqualTo(request.getCustomerId());
        assertThat(account.getLicenses()).hasSize(1);
        assertThat(account.getLicenses().getFirst().getKeys()).hasSize(2);
        assertThat(account.getFreeLicenseRequests()).hasSize(2);

        var detail = asStaff(() -> staffCustomers.getCustomerDetail(request.getCustomerId()));
        assertThat(detail.getAcceptances()).hasSize(1);
        assertThat(detail.getLicenses().getFirst().getKeys()).hasSize(2);
        assertThat(asStaff(staffCustomers::findCustomerSummaries))
                .anySatisfy(summary -> {
                    assertThat(summary.getId()).isEqualTo(request.getCustomerId());
                    assertThat(summary.getPlan()).isEqualTo(Plan.FREE);
                    assertThat(summary.getLatestKeyJti()).isEqualTo(await(licenseService.keysOf(license.getId())).getFirst().getJti());
                });
    }

    @Test
    void aRequestIsRejectedAndTheContactTold(CapturedOutput output) throws Exception {
        String email = "rich-" + UUID.randomUUID() + "@big.example";
        String requestId = post("/api/free-requests", requestBody(email).put("acceptTerms", true), null).bodyAsJsonObject().getString("id");
        FreeLicenseRequest rejected = asStaff(() -> staffRequests.reject(requestId));
        assertThat(rejected.getStatus()).isEqualTo(FreeLicenseRequestStatus.REJECTED);
        assertThat(await(licenseRepository.findFreeLicense(rejected.getCustomerId()))).isNull();
        assertThat(output.getAll()).contains("About your Kinotic free license request");

        // a decision already made is not made again, and nothing is issued
        assertThatThrownBy(() -> asStaff(() -> staffRequests.approve(requestId))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(await(licenseRepository.findFreeLicense(rejected.getCustomerId()))).isNull();
    }

    @Test
    void nothingIsRecordedWithoutTheAcceptance() throws Exception {
        HttpResponse<Buffer> response = post("/api/free-requests", requestBody("nobody-" + UUID.randomUUID() + "@x.example"), null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.bodyAsJsonObject().getString("error")).contains("terms");
    }

    @Test
    void aCustomerOnlySeesItself() throws Exception {
        CustomerContact first = await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
        CustomerContact second = await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
        await(licenseService.createLicense(await(customerRepository.findById(second.getCustomerId())), Plan.FREE, null, null, null));
        CustomerAccount account = asCustomer(first, customerAccount::getAccount);
        assertThat(account.getCustomer().getId()).isEqualTo(first.getCustomerId());
        assertThat(account.getLicenses()).isEmpty();
    }

    private static JsonObject requestBody(String email) {
        return new JsonObject()
                .put("organization", "Tiny Startup LLC")
                .put("contactName", "Grace Hopper")
                .put("contactEmail", email)
                .put("annualRevenue", 250000)
                .put("capitalRaised", 1000000)
                .put("intendedUse", "Internal tools for a five-person team");
    }

    private <T> T asStaff(Supplier<Future<T>> call) throws Exception {
        LicenseParticipant staff = LicenseParticipant.builder().id("staff@mindsignited.com").type(ParticipantType.STAFF)
                                                     .email("staff@mindsignited.com").name("Staff").metadata(Map.of()).roles(List.of()).build();
        return callAs(staff, call);
    }

    private <T> T asCustomer(CustomerContact contact, Supplier<Future<T>> call) throws Exception {
        LicenseParticipant customer = LicenseParticipant.builder().id(contact.getId()).type(ParticipantType.CUSTOMER)
                                                        .customerId(contact.getCustomerId()).email(contact.getEmail()).name(contact.getName())
                                                        .metadata(Map.of()).roles(List.of()).build();
        return callAs(customer, call);
    }

    // the services read the caller from the Vert.x context, as they do for an RPC the gateway dispatches
    private <T> T callAs(LicenseParticipant participant, Supplier<Future<T>> call) throws Exception {
        try {
            return securityContext.callAs(participant, () -> (CompletionStage<T>) call.get().toCompletionStage()).get();
        } catch (ExecutionException e) {
            throw e;
        }
    }
}
