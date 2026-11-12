package org.kinotic.licenses;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kinotic.licenses.api.model.AgreementTerms;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.CaseStudyStatus;
import org.kinotic.licenses.api.model.CompanyFigures;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.DesignPartner;
import org.kinotic.licenses.api.model.DesignPartnerApplication;
import org.kinotic.licenses.api.model.DesignPartnerApplicationForm;
import org.kinotic.licenses.api.model.DesignPartnerApplicationStatus;
import org.kinotic.licenses.api.model.DesignPartnerPublication;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.PublicationType;
import org.kinotic.licenses.api.model.PublicityConsent;
import org.kinotic.licenses.api.services.StaffDesignPartnerService;
import org.kinotic.licenses.internal.api.repositories.AgreementAcceptanceRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.support.ServerTest;
import org.kinotic.licenses.support.StripeEvents;
import org.kinotic.licenses.support.StripeRequest;
import org.kinotic.licenses.support.StripeStandIn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A company on a paid plan applies to be a design partner with its figures and the design partner terms; staff approve
 * it, and its discounted subscription is bought through a Checkout Session carrying the 50%, 36-month coupon, which
 * follows the subscription through a plan move. Staff track its publications, and its case study is published only once
 * the customer approves it.
 */
@ExtendWith(OutputCaptureExtension.class)
class DesignPartnerTest extends ServerTest {

    @Autowired
    private StaffDesignPartnerService staffDesignPartners;
    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private AgreementAcceptanceRepository acceptanceRepository;

    @Test
    void anApprovedDesignPartnerBuysItsPlanWithTheCouponWhichFollowsAPlanMove(CapturedOutput output) throws Exception {
        CustomerContact contact = contactOf();
        DesignPartnerApplication application = apply(contact, "5000000", null, BillingPeriod.ANNUAL);
        assertThat(application.getPlan()).isEqualTo(Plan.SMALL);
        assertThat(application.getStatus()).isEqualTo(DesignPartnerApplicationStatus.PENDING);
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId())))
                .anySatisfy(acceptance -> assertThat(acceptance.getPublicityConsent()).isEqualTo(PublicityConsent.DESIGN_PARTNER));
        assertThat(await(staffDesignPartners.findPendingApplications())).extracting(DesignPartnerApplication::getId).contains(application.getId());

        DesignPartner partner = asStaff(() -> staffDesignPartners.approveApplication(application.getId()));
        assertThat(partner.getLicenseId()).isNull();
        assertThat(partner.getCaseStudyStatus()).isEqualTo(CaseStudyStatus.NOT_STARTED);
        assertThat(partner.getPublications()).extracting(DesignPartnerPublication::getType)
                                             .containsExactlyInAnyOrder(PublicationType.values());
        assertThat(output.getAll()).contains("You are a Kinotic design partner").contains("/design-partner/checkout?application=" + application.getId());

        String url = asCustomer(contact, () -> customerAccount.startDesignPartnerCheckout(application.getId()));
        assertThat(url).startsWith("https://checkout.stripe.test/");
        StripeRequest coupon = StripeStandIn.requests("POST", "/v1/coupons").getFirst();
        assertThat(coupon.params()).containsEntry("id", "kinotic-design-partner").containsEntry("percent_off", "50")
                                   .containsEntry("duration", "repeating").containsEntry("duration_in_months", "36");
        StripeRequest created = sessionOf(contact);
        assertThat(created.params()).containsEntry("discounts[0][coupon]", "kinotic-design-partner")
                                    .containsEntry("line_items[0][price]", StripeStandIn.priceId(StripeStandIn.lookupKey(StripeStandIn.CURRENT_PRICE_LIST,
                                                                                                                              Plan.SMALL, BillingPeriod.ANNUAL)));

        // Stripe completes the Checkout, with the coupon's discount on the subscription
        String subscriptionId = "sub_" + UUID.randomUUID();
        JsonObject subscription = StripeEvents.subscription(subscriptionId, "active",
                                                            StripeStandIn.lookupKey(StripeStandIn.CURRENT_PRICE_LIST, Plan.SMALL, BillingPeriod.ANNUAL),
                                                            Instant.now().plus(Duration.ofDays(365)).truncatedTo(ChronoUnit.SECONDS))
                                                .put("discounts", new JsonArray().add("di_design_partner"));
        StripeStandIn.report(subscription);
        assertThat(deliver(StripeEvents.event("checkout.session.completed", StripeEvents.completedSession(created, subscriptionId, "paid")))
                           .statusCode()).isEqualTo(200);
        License license = await(licenseRepository.findByStripeSubscriptionId(subscriptionId));
        DesignPartner bought = asCustomer(contact, customerAccount::getAccount).getDesignPartner();
        assertThat(bought.getLicenseId()).isEqualTo(license.getId());
        Instant start = Instant.ofEpochSecond(subscription.getLong("start_date"));
        assertThat(bought.getDiscountEndsAt()).isEqualTo(start.atZone(ZoneOffset.UTC).plusMonths(36).toInstant());
        assertThatThrownBy(() -> asCustomer(contact, () -> customerAccount.startDesignPartnerCheckout(application.getId())))
                .hasCauseInstanceOf(IllegalStateException.class);

        // the company grows into Medium: the move keeps the discount
        sql("UPDATE license SET term_end = now() + interval '10 days' WHERE id = $1", license.getId());
        String licenseId = license.getId();
        asCustomer(contact, () -> customerAccount.confirmRevenue(licenseId, figures("30000000", null)));
        StripeRequest move = StripeStandIn.requests("POST", "/v1/subscription_schedules/").getLast();
        assertThat(move.params()).containsEntry("phases[0][discounts][0][discount]", "di_design_partner")
                                 .containsEntry("phases[1][discounts][0][discount]", "di_design_partner");
    }

    @Test
    void aCompanyThatQualifiesForFreeIsOfferedFreeInstead() throws Exception {
        CustomerContact contact = contactOf();
        assertThatThrownBy(() -> apply(contact, "300000", "100000", BillingPeriod.MONTHLY))
                .hasCauseInstanceOf(IllegalArgumentException.class).hasMessageContaining("free license");
        assertThatThrownBy(() -> asCustomer(contact, () -> customerAccount.applyAsDesignPartner(form("5000000", null, BillingPeriod.MONTHLY), "2000-01-01")))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(await(acceptanceRepository.findByCustomerId(contact.getCustomerId()))).isEmpty();
    }

    @Test
    void aRejectedApplicantIsTold(CapturedOutput output) throws Exception {
        DesignPartnerApplication application = apply(contactOf(), "25000000", null, BillingPeriod.MONTHLY);
        assertThat(application.getPlan()).isEqualTo(Plan.MEDIUM);
        DesignPartnerApplication rejected = asStaff(() -> staffDesignPartners.rejectApplication(application.getId()));
        assertThat(rejected.getStatus()).isEqualTo(DesignPartnerApplicationStatus.REJECTED);
        assertThat(rejected.getReviewedBy()).isEqualTo("staff@mindsignited.com");
        assertThat(output.getAll()).contains("About your Kinotic design partner application");
        assertThatThrownBy(() -> asStaff(() -> staffDesignPartners.approveApplication(application.getId())))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCaseStudyIsPublishedOnlyOnceTheCustomerApprovesIt() throws Exception {
        DesignPartnerApplication application = apply(contactOf(), "2000000", null, BillingPeriod.MONTHLY);
        DesignPartner partner = asStaff(() -> staffDesignPartners.approveApplication(application.getId()));

        assertThatThrownBy(() -> asStaff(() -> staffDesignPartners.setCaseStudyStatus(partner.getId(), CaseStudyStatus.PUBLISHED)))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(asStaff(() -> staffDesignPartners.setCaseStudyStatus(partner.getId(), CaseStudyStatus.IN_PROGRESS)).getCaseStudyStatus())
                .isEqualTo(CaseStudyStatus.IN_PROGRESS);
        // the logo being approved does not approve the case study
        String logo = publication(partner, PublicationType.LOGO).getId();
        asStaff(() -> staffDesignPartners.approvePublication(logo, "Jane Doe, CTO"));
        assertThatThrownBy(() -> asStaff(() -> staffDesignPartners.setCaseStudyStatus(partner.getId(), CaseStudyStatus.PUBLISHED)))
                .hasCauseInstanceOf(IllegalStateException.class);

        String caseStudy = publication(partner, PublicationType.CASE_STUDY).getId();
        DesignPartner approved = asStaff(() -> staffDesignPartners.approvePublication(caseStudy, "Jane Doe, CTO"));
        DesignPartnerPublication publication = publication(approved, PublicationType.CASE_STUDY);
        assertThat(publication.getApprovedBy()).isEqualTo("Jane Doe, CTO");
        assertThat(publication.getApprovedAt()).isNotNull();
        assertThat(asStaff(() -> staffDesignPartners.setCaseStudyStatus(partner.getId(), CaseStudyStatus.PUBLISHED)).getCaseStudyStatus())
                .isEqualTo(CaseStudyStatus.PUBLISHED);
        assertThat(asStaff(staffDesignPartners::findDesignPartners)).extracting(DesignPartner::getId).contains(partner.getId());
    }

    private DesignPartnerApplication apply(CustomerContact contact, String revenue, String raised, BillingPeriod period) throws Exception {
        return asCustomer(contact, () -> customerAccount.applyAsDesignPartner(form(revenue, raised, period), AgreementTerms.VERSION));
    }

    private StripeRequest sessionOf(CustomerContact contact) {
        return StripeStandIn.requests("POST", "/v1/checkout/sessions").stream()
                            .filter(request -> contact.getCustomerId().equals(request.params().get("client_reference_id")))
                            .reduce((first, second) -> second)
                            .orElseThrow();
    }

    private CustomerContact contactOf() throws Exception {
        return await(contactRepository.findByCustomerId(seedCustomer().getId())).getFirst();
    }

    private static DesignPartnerPublication publication(DesignPartner partner, PublicationType type) {
        return partner.getPublications().stream().filter(publication -> publication.getType() == type).findFirst().orElseThrow();
    }

    private static DesignPartnerApplicationForm form(String revenue, String raised, BillingPeriod period) {
        return new DesignPartnerApplicationForm().setFigures(figures(revenue, raised)).setBillingPeriod(period).setIntendedUse("Our logistics platform");
    }

    private static CompanyFigures figures(String revenue, String raised) {
        return new CompanyFigures().setAnnualRevenue(new BigDecimal(revenue)).setCapitalRaised(raised != null ? new BigDecimal(raised) : null);
    }
}
