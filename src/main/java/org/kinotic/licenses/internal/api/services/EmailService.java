package org.kinotic.licenses.internal.api.services;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.communication.email.models.EmailSendStatus;
import com.azure.core.util.polling.SyncPoller;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.context.MapValueResolver;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.licenses.api.config.EmailProperties;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.DesignPartnerApplication;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.LicensedCluster;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the server's transactional emails through Azure Communication Services, rendered from Handlebars templates
 * branded like kinotic.ai. No email carries a key: each cluster's keys are in the account. With
 * {@code kinotic.licenses.email.enabled=false} nothing is sent and each email is logged instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailService {

    private static final String ACCOUNT_PATH = "/account";

    // {{var}} is HTML-escaped in the HTML bodies, where the templates write URLs as {{{var}}}; text is never escaped
    private static final Handlebars HTML_TEMPLATES = new Handlebars(new ClassPathTemplateLoader("/templates/email", ".html"));
    private static final Handlebars TEXT_TEMPLATES = new Handlebars(new ClassPathTemplateLoader("/templates/email", ".txt"))
            .with(EscapingStrategy.NOOP);

    private static final Template SIGN_IN_CODE_HTML = compile(HTML_TEMPLATES, "sign-in-code");
    private static final Template SIGN_IN_CODE_TEXT = compile(TEXT_TEMPLATES, "sign-in-code");
    private static final Template LICENSE_READY_HTML = compile(HTML_TEMPLATES, "license-ready");
    private static final Template LICENSE_READY_TEXT = compile(TEXT_TEMPLATES, "license-ready");
    private static final Template LICENSE_RENEWED_HTML = compile(HTML_TEMPLATES, "license-renewed");
    private static final Template LICENSE_RENEWED_TEXT = compile(TEXT_TEMPLATES, "license-renewed");
    private static final Template PAST_DUE_HTML = compile(HTML_TEMPLATES, "past-due");
    private static final Template PAST_DUE_TEXT = compile(TEXT_TEMPLATES, "past-due");
    private static final Template KEY_EXPIRING_HTML = compile(HTML_TEMPLATES, "key-expiring");
    private static final Template KEY_EXPIRING_TEXT = compile(TEXT_TEMPLATES, "key-expiring");
    private static final Template CONFIRMATION_DUE_HTML = compile(HTML_TEMPLATES, "confirmation-due");
    private static final Template CONFIRMATION_DUE_TEXT = compile(TEXT_TEMPLATES, "confirmation-due");
    private static final Template PLAN_CHANGED_HTML = compile(HTML_TEMPLATES, "plan-changed");
    private static final Template PLAN_CHANGED_TEXT = compile(TEXT_TEMPLATES, "plan-changed");
    private static final Template PARTNER_APPROVED_HTML = compile(HTML_TEMPLATES, "design-partner-approved");
    private static final Template PARTNER_APPROVED_TEXT = compile(TEXT_TEMPLATES, "design-partner-approved");
    private static final Template PARTNER_REJECTED_HTML = compile(HTML_TEMPLATES, "design-partner-rejected");
    private static final Template PARTNER_REJECTED_TEXT = compile(TEXT_TEMPLATES, "design-partner-rejected");
    private static final Template LAPSE_WARNING_HTML = compile(HTML_TEMPLATES, "lapse-warning");
    private static final Template LAPSE_WARNING_TEXT = compile(TEXT_TEMPLATES, "lapse-warning");
    private static final Template LAPSED_HTML = compile(HTML_TEMPLATES, "lapsed");
    private static final Template LAPSED_TEXT = compile(TEXT_TEMPLATES, "lapsed");
    private static final Template FREE_APPROVED_HTML = compile(HTML_TEMPLATES, "free-request-approved");
    private static final Template FREE_APPROVED_TEXT = compile(TEXT_TEMPLATES, "free-request-approved");
    private static final Template FREE_REJECTED_HTML = compile(HTML_TEMPLATES, "free-request-rejected");
    private static final Template FREE_REJECTED_TEXT = compile(TEXT_TEMPLATES, "free-request-rejected");

    private static final Template TRIAL_STARTED_HTML = compile(HTML_TEMPLATES, "trial-started");
    private static final Template TRIAL_STARTED_TEXT = compile(TEXT_TEMPLATES, "trial-started");
    private static final Template TRIAL_EXPIRED_HTML = compile(HTML_TEMPLATES, "trial-expired");
    private static final Template TRIAL_EXPIRED_TEXT = compile(TEXT_TEMPLATES, "trial-expired");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM uuuu").withZone(ZoneOffset.UTC);

    private final KinoticLicensesProperties properties;
    private final Vertx vertx;

    private volatile EmailClient emailClient;

    /**
     * Emails a one-time sign-in code.
     */
    public Future<Void> sendSignInCode(String email, String code) {
        Map<String, Object> variables = Map.of("code", code);
        return send(email, email, "Your Kinotic sign-in code", SIGN_IN_CODE_HTML, SIGN_IN_CODE_TEXT, variables);
    }

    /**
     * Tells a contact a bought license is ready for its clusters, and how to add one.
     */
    public Future<Void> sendLicenseReady(CustomerContact contact, String organization, License license) {
        Map<String, Object> variables = licenseVariables(organization, license);
        variables.put("seats", license.getSeats());
        return send(contact.getEmail(), contact.getName(), "Your Kinotic Enterprise license is ready",
                    LICENSE_READY_HTML, LICENSE_READY_TEXT, variables);
    }

    /**
     * Tells a contact a license is renewed and its clusters' new keys are issued.
     */
    public Future<Void> sendLicenseRenewed(CustomerContact contact, String organization, License license) {
        return send(contact.getEmail(), contact.getName(), "Your Kinotic Enterprise license is renewed",
                    LICENSE_RENEWED_HTML, LICENSE_RENEWED_TEXT, licenseVariables(organization, license));
    }

    /**
     * Warns a contact that a cluster's newest key expires with no newer one issued, saying why.
     */
    public Future<Void> sendKeyExpiring(CustomerContact contact, String organization, License license, LicensedCluster cluster,
                                        IssuedKey key) {
        Map<String, Object> variables = licenseVariables(organization, license);
        variables.put("expiresOn", DATE.format(key.getExpiresAt()));
        variables.put("clusterName", cluster.getName());
        variables.put("reason", expiryReason(license));
        return send(contact.getEmail(), contact.getName(), "Your Kinotic Enterprise license key expires soon",
                    KEY_EXPIRING_HTML, KEY_EXPIRING_TEXT, variables);
    }

    /**
     * Asks a contact to confirm the company's figures before the license's next anniversary.
     */
    public Future<Void> sendConfirmationDue(CustomerContact contact, String organization, License license) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("organization", organization);
        variables.put("planName", planName(license.getPlan()));
        variables.put("anniversaryOn", DATE.format(license.getTermEnd()));
        variables.put("figuresAsked", license.getPlan() == Plan.FREE ? "annual revenue and the capital it has raised" : "annual revenue");
        variables.put("accountUrl", accountUrl());
        return send(contact.getEmail(), contact.getName(), "Confirm your figures for your Kinotic license",
                    CONFIRMATION_DUE_HTML, CONFIRMATION_DUE_TEXT, variables);
    }

    /**
     * Tells a contact what the figures confirmed for the license's anniversary change: the plan it moves to, the paid
     * subscription ending as the license continues as Free, or the paid plan a free license must buy.
     *
     * @param plan the plan the figures set
     * @param at   the anniversary the change takes effect at
     */
    public Future<Void> sendPlanChanged(CustomerContact contact, String organization, License license, Plan plan, Instant at) {
        String on = DATE.format(at);
        String summary;
        if (plan == Plan.FREE) {
            summary = "Your paid subscription ends on " + on + ", and your license continues on the Free plan from then.";
        } else if (license.getPlan() == Plan.FREE) {
            summary = "Your company's figures now set the " + planName(plan) + " plan. Buy it from your account by " + on
                    + " to keep your license, with the price list it keeps.";
        } else {
            summary = "Your license moves from the " + planName(license.getPlan()) + " plan to the " + planName(plan) + " plan on " + on + ".";
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("organization", organization);
        variables.put("summary", summary);
        variables.put("accountUrl", accountUrl());
        return send(contact.getEmail(), contact.getName(), "Your Kinotic plan is changing", PLAN_CHANGED_HTML, PLAN_CHANGED_TEXT, variables);
    }

    /**
     * Tells a contact a payment failed and no renewal key is issued until it is paid.
     */
    public Future<Void> sendPastDue(CustomerContact contact, String organization, License license) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("organization", organization);
        variables.put("planName", planName(license.getPlan()));
        variables.put("accountUrl", accountUrl());
        return send(contact.getEmail(), contact.getName(), "Action needed: your Kinotic payment didn't go through",
                    PAST_DUE_HTML, PAST_DUE_TEXT, variables);
    }

    /**
     * Warns a contact that the past-due license lapses at {@code lapsesAt} unless its payment is settled.
     */
    public Future<Void> sendLapseWarning(CustomerContact contact, String organization, License license, Instant lapsesAt) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("organization", organization);
        variables.put("planName", planName(license.getPlan()));
        variables.put("pastDueSince", DATE.format(license.getPastDueSince()));
        variables.put("lapsesOn", DATE.format(lapsesAt));
        variables.put("accountUrl", accountUrl());
        return send(contact.getEmail(), contact.getName(), "Action needed: your Kinotic license lapses on " + DATE.format(lapsesAt),
                    LAPSE_WARNING_HTML, LAPSE_WARNING_TEXT, variables);
    }

    /**
     * Tells a contact the license lapsed and its subscription was cancelled.
     */
    public Future<Void> sendLapsed(CustomerContact contact, String organization) {
        Map<String, Object> variables = Map.of("organization", organization, "accountUrl", accountUrl());
        return send(contact.getEmail(), contact.getName(), "Your Kinotic license has lapsed", LAPSED_HTML, LAPSED_TEXT, variables);
    }

    /**
     * Tells a contact a trial started, how to add its cluster, and how to buy its plan before the trial ends.
     */
    public Future<Void> sendTrialStarted(CustomerContact contact, String organization, License license) {
        return send(contact.getEmail(), contact.getName(), "Your Kinotic Enterprise trial has started",
                    TRIAL_STARTED_HTML, TRIAL_STARTED_TEXT, licenseVariables(organization, license));
    }

    /**
     * Tells a contact the trial ended without its plan being bought.
     */
    public Future<Void> sendTrialExpired(CustomerContact contact, String organization, License license) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("organization", organization);
        variables.put("planName", planName(license.getPlan()));
        variables.put("accountUrl", accountUrl());
        return send(contact.getEmail(), contact.getName(), "Your Kinotic Enterprise trial has ended",
                    TRIAL_EXPIRED_HTML, TRIAL_EXPIRED_TEXT, variables);
    }

    /**
     * Tells a contact its free license, or its renewal, is approved, and how to add its cluster.
     */
    public Future<Void> sendFreeRequestApproved(CustomerContact contact, String organization, License license) {
        return send(contact.getEmail(), contact.getName(), "Your free Kinotic Enterprise license is approved",
                    FREE_APPROVED_HTML, FREE_APPROVED_TEXT, licenseVariables(organization, license));
    }

    /**
     * Tells a contact a free license request was not approved.
     */
    public Future<Void> sendFreeRequestRejected(CustomerContact contact, String organization) {
        Map<String, Object> variables = Map.of("organization", organization,
                                               "siteUrl", properties.getLicenses().getPublicBaseUrl());
        return send(contact.getEmail(), contact.getName(), "About your Kinotic free license request",
                    FREE_REJECTED_HTML, FREE_REJECTED_TEXT, variables);
    }

    /**
     * Tells a contact its design partner application is approved, with the link that starts its discounted subscription.
     */
    public Future<Void> sendDesignPartnerApproved(CustomerContact contact, String organization, DesignPartnerApplication application,
                                                  String checkoutUrl) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("organization", organization);
        variables.put("planName", planName(application.getPlan()));
        variables.put("billing", application.getBillingPeriod() == BillingPeriod.ANNUAL ? "annually" : "monthly");
        variables.put("checkoutUrl", checkoutUrl);
        return send(contact.getEmail(), contact.getName(), "You are a Kinotic design partner",
                    PARTNER_APPROVED_HTML, PARTNER_APPROVED_TEXT, variables);
    }

    /**
     * Tells a contact its design partner application was not approved.
     */
    public Future<Void> sendDesignPartnerRejected(CustomerContact contact, String organization) {
        Map<String, Object> variables = Map.of("organization", organization, "accountUrl", accountUrl());
        return send(contact.getEmail(), contact.getName(), "About your Kinotic design partner application",
                    PARTNER_REJECTED_HTML, PARTNER_REJECTED_TEXT, variables);
    }

    // expiresOn is when a key issued for the license now expires, termEnd when its license year ends
    private Map<String, Object> licenseVariables(String organization, License license) {
        Map<String, Object> ret = new HashMap<>();
        ret.put("organization", organization);
        ret.put("planName", planName(license.getPlan()));
        ret.put("expiresOn", DATE.format(LicenseService.keyExpiry(license)));
        ret.put("termEnd", DATE.format(license.getTermEnd()));
        ret.put("accountUrl", accountUrl());
        return ret;
    }

    // why no newer key comes by itself, for the license states KeyEmailJob warns about
    private static String expiryReason(License license) {
        String ret;
        if (license.getStatus() == LicenseStatus.TRIAL) {
            ret = "your trial of the " + planName(license.getPlan()) + " plan ends on " + DATE.format(license.getTermEnd())
                    + ". Buy the plan from your account to keep your license, its price list and its terms.";
        } else if (license.getStatus() == LicenseStatus.PAST_DUE) {
            ret = "a payment failed. Update your payment details from your account, and a new key is issued once it is paid.";
        } else if (license.getCancelsAt() != null) {
            ret = "your subscription is set to end on " + DATE.format(license.getCancelsAt()) + ".";
        } else if (license.getPlan() == Plan.FREE) {
            ret = "your free license's year is ending. Confirm your company's figures in your account so we can renew it.";
        } else {
            ret = "the cluster is beyond your license's " + license.getSeats() + (license.getSeats() == 1 ? " seat" : " seats")
                    + ". Release a cluster or add seats in your account to keep it licensed.";
        }
        return ret;
    }

    private String accountUrl() {
        return properties.getLicenses().getPublicBaseUrl() + ACCOUNT_PATH;
    }

    private static String planName(Plan plan) {
        String name = plan.name();
        return name.charAt(0) + name.substring(1).toLowerCase();
    }

    private Future<Void> send(String toEmail, String toName, String subject, Template html, Template text, Map<String, Object> variables) {
        EmailProperties email = properties.getLicenses().getEmail();
        Future<Void> ret;
        if (!email.isEnabled()) {
            log.warn("Email sending is disabled; not sending '{}' to {}:\n{}", subject, toEmail, render(text, variables));
            ret = Future.succeededFuture();
        } else {
            EmailMessage message = new EmailMessage()
                    .setSenderAddress(email.getSenderAddress())
                    .setToRecipients(List.of(new EmailAddress(toEmail).setDisplayName(toName)))
                    .setSubject(subject)
                    .setBodyHtml(render(html, variables))
                    .setBodyPlainText(render(text, variables));
            // the ACS client blocks while it polls the send, so it runs on a worker
            ret = vertx.executeBlocking(() -> {
                SyncPoller<EmailSendResult, EmailSendResult> poller = client().beginSend(message);
                poller.waitForCompletion(email.getSendTimeout());
                EmailSendResult result = poller.getFinalResult();
                if (result.getStatus() != EmailSendStatus.SUCCEEDED) {
                    throw new IllegalStateException("Azure Communication Services rejected the send: status="
                                                            + result.getStatus() + " messageId=" + result.getId());
                }
                log.info("Sent '{}' to {} (messageId={})", subject, toEmail, result.getId());
                return null;
            });
        }
        return ret;
    }

    // built on the first send: a server with email disabled never authenticates to Azure
    private EmailClient client() {
        EmailClient ret = emailClient;
        if (ret == null) {
            synchronized (this) {
                ret = emailClient;
                if (ret == null) {
                    ret = new EmailClientBuilder()
                            .endpoint(properties.getLicenses().getEmail().getEndpoint())
                            .credential(new DefaultAzureCredentialBuilder().build())
                            .buildClient();
                    emailClient = ret;
                }
            }
        }
        return ret;
    }

    private static String render(Template template, Map<String, Object> variables) {
        // map-only resolution: no reflective traversal of context objects, the known handlebars.java injection vector
        Context context = Context.newBuilder(variables).resolver(MapValueResolver.INSTANCE).build();
        try {
            return template.apply(context);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed rendering email template", e);
        } finally {
            context.destroy();
        }
    }

    // compiled at class initialization so a missing or broken template fails startup, not the first send
    private static Template compile(Handlebars handlebars, String name) {
        try {
            return handlebars.compile(name);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed compiling email template: " + name, e);
        }
    }
}
