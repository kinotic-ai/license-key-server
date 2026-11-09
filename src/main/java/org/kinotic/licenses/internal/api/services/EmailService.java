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
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the server's transactional emails through Azure Communication Services, rendered from Handlebars templates
 * branded like kinotic.ai. A key only ever appears in a body, never in a subject. With
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
    private static final Template NEW_KEY_HTML = compile(HTML_TEMPLATES, "new-key");
    private static final Template NEW_KEY_TEXT = compile(TEXT_TEMPLATES, "new-key");
    private static final Template RENEWAL_KEY_HTML = compile(HTML_TEMPLATES, "renewal-key");
    private static final Template RENEWAL_KEY_TEXT = compile(TEXT_TEMPLATES, "renewal-key");
    private static final Template PAST_DUE_HTML = compile(HTML_TEMPLATES, "past-due");
    private static final Template PAST_DUE_TEXT = compile(TEXT_TEMPLATES, "past-due");
    private static final Template KEY_EXPIRING_HTML = compile(HTML_TEMPLATES, "key-expiring");
    private static final Template KEY_EXPIRING_TEXT = compile(TEXT_TEMPLATES, "key-expiring");
    private static final Template FREE_APPROVED_HTML = compile(HTML_TEMPLATES, "free-request-approved");
    private static final Template FREE_APPROVED_TEXT = compile(TEXT_TEMPLATES, "free-request-approved");
    private static final Template FREE_REJECTED_HTML = compile(HTML_TEMPLATES, "free-request-rejected");
    private static final Template FREE_REJECTED_TEXT = compile(TEXT_TEMPLATES, "free-request-rejected");

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
     * Emails a contact the first key of a license.
     */
    public Future<Void> sendNewKey(CustomerContact contact, String organization, License license, IssuedKey key) {
        return send(contact.getEmail(), contact.getName(), "Your Kinotic Enterprise license key",
                    NEW_KEY_HTML, NEW_KEY_TEXT, keyVariables(organization, license, key));
    }

    /**
     * Emails a contact the renewal key of a license, issued ahead of the current key's expiry.
     */
    public Future<Void> sendRenewalKey(CustomerContact contact, String organization, License license, IssuedKey key) {
        return send(contact.getEmail(), contact.getName(), "Your renewed Kinotic Enterprise license key",
                    RENEWAL_KEY_HTML, RENEWAL_KEY_TEXT, keyVariables(organization, license, key));
    }

    /**
     * Warns a contact that the license's newest key expires with no newer one issued, saying why.
     */
    public Future<Void> sendKeyExpiring(CustomerContact contact, String organization, License license, IssuedKey key) {
        Map<String, Object> variables = keyVariables(organization, license, key);
        variables.put("reason", expiryReason(license));
        return send(contact.getEmail(), contact.getName(), "Your Kinotic Enterprise license key expires soon",
                    KEY_EXPIRING_HTML, KEY_EXPIRING_TEXT, variables);
    }

    /**
     * Tells a contact a payment failed and no renewal key is issued until it is paid.
     */
    public Future<Void> sendPastDue(CustomerContact contact, String organization, License license) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("organization", organization);
        variables.put("planName", planName(license));
        variables.put("accountUrl", accountUrl());
        return send(contact.getEmail(), contact.getName(), "Action needed: your Kinotic payment didn't go through",
                    PAST_DUE_HTML, PAST_DUE_TEXT, variables);
    }

    /**
     * Emails a contact the key of an approved free license.
     */
    public Future<Void> sendFreeRequestApproved(CustomerContact contact, String organization, License license, IssuedKey key) {
        return send(contact.getEmail(), contact.getName(), "Your free Kinotic Enterprise license is approved",
                    FREE_APPROVED_HTML, FREE_APPROVED_TEXT, keyVariables(organization, license, key));
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

    private Map<String, Object> keyVariables(String organization, License license, IssuedKey key) {
        Map<String, Object> ret = new HashMap<>();
        ret.put("organization", organization);
        ret.put("planName", planName(license));
        ret.put("key", key.getKey());
        ret.put("expiresOn", DATE.format(key.getExpiresAt()));
        ret.put("accountUrl", accountUrl());
        return ret;
    }

    // why no newer key comes by itself, for the license states KeyEmailJob warns about
    private static String expiryReason(License license) {
        String ret;
        if (license.getStatus() == LicenseStatus.PAST_DUE) {
            ret = "a payment failed. Update your payment details from your account, and a new key is issued once it is paid.";
        } else if (license.getCancelsAt() != null) {
            ret = "your subscription is set to end on " + DATE.format(license.getCancelsAt()) + ".";
        } else {
            ret = "your free license's year is ending. Confirm your company's figures in your account so we can renew it.";
        }
        return ret;
    }

    private String accountUrl() {
        return properties.getLicenses().getPublicBaseUrl() + ACCOUNT_PATH;
    }

    private static String planName(License license) {
        String name = license.getPlan().name();
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
