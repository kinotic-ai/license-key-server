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
import org.kinotic.licenses.internal.api.services.FreeLicenseService;
import org.kinotic.licenses.internal.api.services.LicenseService;
import org.kinotic.licenses.support.ServerTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    @Autowired
    private FreeLicenseService freeLicenseService;

    @Test
    void aRequestIsApprovedAndLaterRenewedByReCertifying(CapturedOutput output) throws Exception {
        String email = "grace-" + UUID.randomUUID() + "@tiny.example";
        HttpResponse<Buffer> created = post("/api/free-requests", requestBody(email).put("acceptTerms", true), confirmEmail(output, email));
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
        String requestId = post("/api/free-requests", requestBody(email).put("acceptTerms", true), confirmEmail(output, email)).bodyAsJsonObject().getString("id");
        FreeLicenseRequest rejected = asStaff(() -> staffRequests.reject(requestId));
        assertThat(rejected.getStatus()).isEqualTo(FreeLicenseRequestStatus.REJECTED);
        assertThat(await(licenseRepository.findFreeLicense(rejected.getCustomerId()))).isNull();
        assertThat(output.getAll()).contains("About your Kinotic free license request");

        // a decision already made is not made again, and nothing is issued
        assertThatThrownBy(() -> asStaff(() -> staffRequests.approve(requestId))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(await(licenseRepository.findFreeLicense(rejected.getCustomerId()))).isNull();
    }

    @Test
    void aSignUpNeedsAConfirmedAddressAndAnAddressWithAnAccountActsForIt(CapturedOutput output) throws Exception {
        String email = "taken-" + UUID.randomUUID() + "@tiny.example";
        // an unconfirmed sign-up names no address the server accepts, so it says nothing about any address
        HttpResponse<Buffer> unconfirmed = post("/api/free-requests", requestBody(email).put("acceptTerms", true), null);
        assertThat(unconfirmed.statusCode()).isEqualTo(401);
        HttpResponse<Buffer> unconfirmedCheckout = post("/api/checkout", new JsonObject().put("plan", "STARTUP").put("legalName", "Other Co")
                                                                                     .put("contactName", "Someone Else").put("acceptTerms", true), null);
        assertThat(unconfirmedCheckout.statusCode()).isEqualTo(401);

        HttpResponse<Buffer> signedUp = post("/api/free-requests", requestBody(email).put("acceptTerms", true), confirmEmail(output, email));
        assertThat(signedUp.statusCode()).isEqualTo(201);
        CustomerContact original = await(contactRepository.findByEmail(email)).getFirst();
        // the sign-up signed the page in to its new account under a fresh session id, so asking again is a request of
        // that account
        String cookie = signedUp.cookies().getFirst().split(";")[0];
        assertThat(get("/api/auth/me", cookie).statusCode()).isEqualTo(204);
        assertThat(post("/api/free-requests", requestBody(email).put("acceptTerms", true), cookie).statusCode()).isEqualTo(409);

        // confirming the address on another page signs that page in to the same account
        String other = signIn(output, email.toUpperCase());
        assertThat(post("/api/free-requests", requestBody(email).put("acceptTerms", true), other).statusCode()).isEqualTo(409);
        assertThat(await(contactRepository.findByEmail(email))).extracting(CustomerContact::getCustomerId)
                                                               .containsExactly(original.getCustomerId());
    }

    @Test
    void aCodeRequestLooksTheSameWhetherOrNotTheAddressHasAnAccount(CapturedOutput output) throws Exception {
        String known = "known-" + UUID.randomUUID() + "@tiny.example";
        submitAnonymously(output, known);
        HttpResponse<Buffer> forKnown = post("/api/auth/code/request", new JsonObject().put("email", known), null);
        HttpResponse<Buffer> forUnknown = post("/api/auth/code/request", new JsonObject().put("email", "unknown-" + UUID.randomUUID() + "@tiny.example"), null);
        assertThat(forKnown.statusCode()).isEqualTo(202);
        assertThat(forUnknown.statusCode()).isEqualTo(202);
        assertThat(forKnown.bodyAsString()).isEqualTo(forUnknown.bodyAsString());
    }

    @Test
    void aSignedInCustomerRequestsAndBuysForItsOwnAccount(CapturedOutput output) throws Exception {
        String email = "own-" + UUID.randomUUID() + "@tiny.example";
        assertThat(post("/api/free-requests", requestBody(email).put("acceptTerms", true), confirmEmail(output, email)).statusCode()).isEqualTo(201);
        String customerId = await(contactRepository.findByEmail(email)).getFirst().getCustomerId();
        FreeLicenseRequest first = await(requestRepository.findByCustomerId(customerId)).getFirst();
        asStaff(() -> staffRequests.reject(first.getId()));

        String cookie = signIn(output, email);
        JsonObject certification = new JsonObject().put("annualRevenue", 100000).put("capitalRaised", 0)
                                                   .put("intendedUse", "Asking again").put("acceptTerms", true);
        HttpResponse<Buffer> again = post("/api/free-requests", certification, cookie);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(await(requestRepository.findById(again.bodyAsJsonObject().getString("id"))).getCustomerId()).isEqualTo(customerId);
        assertThat(await(acceptanceRepository.findByCustomerId(customerId))).hasSize(2);
        // one request at a time
        assertThat(post("/api/free-requests", certification, cookie).statusCode()).isEqualTo(409);

        // a customer holding a paid subscription changes it in the Customer Portal rather than buying a second
        await(licenseService.createLicense(await(customerRepository.findById(customerId)), Plan.STARTUP, "sub_" + UUID.randomUUID(),
                                           "cs_" + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30))));
        HttpResponse<Buffer> checkout = post("/api/checkout", new JsonObject().put("plan", "BUSINESS").put("acceptTerms", true), cookie);
        assertThat(checkout.statusCode()).isEqualTo(409);
        assertThat(checkout.bodyAsJsonObject().getString("error")).contains("already has a subscription");
    }

    @Test
    void aLapsedFreeLicenseRenewsFromToday(CapturedOutput output) throws Exception {
        String requestId = submitAnonymously(output, "lapsed-" + UUID.randomUUID() + "@tiny.example");
        FreeLicenseRequest approved = asStaff(() -> staffRequests.approve(requestId));
        License license = await(licenseRepository.findFreeLicense(approved.getCustomerId()));
        sql("UPDATE license SET term_end = now() - interval '90 days' WHERE id = $1", license.getId());

        FreeLicenseRequest renewal = await(freeLicenseService.requestRenewal(approved.getCustomerId(),
                new FreeLicenseCertification().setAnnualRevenue(BigDecimal.ONE).setCapitalRaised(BigDecimal.ZERO).setIntendedUse("Still small")));
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        asStaff(() -> staffRequests.approve(renewal.getId()));

        License renewed = await(licenseRepository.findById(license.getId()));
        assertThat(renewed.getTermStart()).isAfterOrEqualTo(before);
        assertThat(await(licenseService.keysOf(license.getId())).getFirst().getExpiresAt())
                .isEqualTo(renewed.getTermEnd().plus(LicenseService.KEY_BUFFER));
    }

    @Test
    void anApprovalOfALicenseWithoutAKeyIssuesItsFirstTerm(CapturedOutput output) throws Exception {
        String requestId = submitAnonymously(output, "keyless-" + UUID.randomUUID() + "@tiny.example");
        String customerId = await(requestRepository.findById(requestId)).getCustomerId();
        // what an earlier approval leaves when signing failed after the license was saved
        License keyless = await(licenseService.createAwaitingPayment(await(customerRepository.findById(customerId)), Plan.FREE, null, null, null));
        sql("UPDATE license SET status = 'ACTIVE' WHERE id = $1", keyless.getId());

        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        asStaff(() -> staffRequests.approve(requestId));

        License issued = await(licenseRepository.findById(keyless.getId()));
        assertThat(issued.getTermStart()).isAfterOrEqualTo(before);
        assertThat(issued.getTermEnd()).isBefore(Instant.now().plus(Duration.ofDays(370)));
        assertThat(await(licenseService.keysOf(keyless.getId()))).hasSize(1);
    }

    @Test
    void anApprovalThatStoppedMidWayIsCompletedByApprovingAgain(CapturedOutput output) throws Exception {
        String requestId = submitAnonymously(output, "stalled-" + UUID.randomUUID() + "@tiny.example");
        // what a server that stopped after claiming the approval leaves, once the claim has expired
        sql("UPDATE free_license_request SET status = 'APPROVED', reviewed_by = 'staff@mindsignited.com', reviewed_at = now() - interval '10 minutes',"
                    + " claimed_at = now() - interval '10 minutes' WHERE id = $1", requestId);
        assertThat(await(staffRequests.findPending())).extracting(FreeLicenseRequest::getId).contains(requestId);

        asStaff(() -> staffRequests.approve(requestId));

        FreeLicenseRequest completed = await(requestRepository.findById(requestId));
        License license = await(licenseRepository.findFreeLicense(completed.getCustomerId()));
        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
        assertThat(await(staffRequests.findPending())).extracting(FreeLicenseRequest::getId).doesNotContain(requestId);
        // the approval is complete, so it is decided for good
        assertThatThrownBy(() -> asStaff(() -> staffRequests.approve(requestId))).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void anApprovalThatStoppedAfterIssuingItsKeyIssuesNoOther(CapturedOutput output) throws Exception {
        String requestId = submitAnonymously(output, "issued-" + UUID.randomUUID() + "@tiny.example");
        String customerId = await(requestRepository.findById(requestId)).getCustomerId();
        sql("UPDATE free_license_request SET status = 'APPROVED', reviewed_by = 'staff@mindsignited.com', reviewed_at = now() - interval '10 minutes',"
                    + " claimed_at = now() - interval '10 minutes' WHERE id = $1", requestId);
        // the stopped approval got as far as issuing the key, after it was approved
        License license = await(licenseService.createLicense(await(customerRepository.findById(customerId)), Plan.FREE, null, null, null)).license();

        asStaff(() -> staffRequests.approve(requestId));

        assertThat(await(licenseService.keysOf(license.getId()))).hasSize(1);
        assertThat(await(licenseRepository.findById(license.getId())).getTermEnd()).isEqualTo(license.getTermEnd());
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

    // a free request from a page that confirmed the address first, as the form makes one; answers with the request's id
    private static String submitAnonymously(CapturedOutput output, String email) throws Exception {
        HttpResponse<Buffer> created = post("/api/free-requests", requestBody(email).put("acceptTerms", true), confirmEmail(output, email));
        assertThat(created.statusCode()).isEqualTo(201);
        return created.bodyAsJsonObject().getString("id");
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
