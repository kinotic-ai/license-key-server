package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.internal.api.model.ContactCredential;
import org.kinotic.licenses.internal.api.model.EmailLink;
import org.kinotic.licenses.internal.api.model.EmailLinkType;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.EmailLinkRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;

/**
 * Signs customers up and in. Sign-up starts with an email address only: the link emailed to it opens the form that
 * names the organization and sets the password. Contacts sign in with their email and password, and set a new password
 * with a link emailed to them. A link is used once and only its token's hash is stored. Requests for links and sign-in
 * attempts are limited per email address and per client address, and a request for a link answers alike whether or
 * not the address has an account.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerSignInService {

    private static final int MIN_PASSWORD_LENGTH = 12;
    // bcrypt reads no further than 72 bytes
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final Duration SIGN_UP_LINK_LIFETIME = Duration.ofHours(24);
    private static final Duration PASSWORD_RESET_LINK_LIFETIME = Duration.ofHours(1);
    private static final Duration LIMIT_WINDOW = Duration.ofMinutes(15);
    private static final String TOO_MANY_REQUESTS = "Too many requests; try again later";
    private static final String INVALID_CREDENTIALS = "The email or password is not valid";
    private static final String INVALID_LINK = "The link is not valid or has expired; ask for a new one";
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    // compared against when the address has no password, so an unknown address takes as long to refuse as a known one
    private static final String UNMATCHABLE_HASH = PASSWORD_ENCODER.encode(UUID.randomUUID().toString());

    private final SlidingWindowRateLimiter linkRequestsPerEmail = new SlidingWindowRateLimiter(5, LIMIT_WINDOW);
    private final SlidingWindowRateLimiter linkRequestsPerAddress = new SlidingWindowRateLimiter(30, LIMIT_WINDOW);
    private final SlidingWindowRateLimiter attemptsPerEmail = new SlidingWindowRateLimiter(10, LIMIT_WINDOW);
    private final SlidingWindowRateLimiter attemptsPerAddress = new SlidingWindowRateLimiter(50, LIMIT_WINDOW);
    private final SecureRandom random = new SecureRandom();

    private final CustomerContactRepository contactRepository;
    private final EmailLinkRepository linkRepository;
    private final CustomerService customerService;
    private final EmailService emailService;
    private final Vertx vertx;

    /**
     * Emails the address a link to create its account, or, when it has one, a link to set a new password for it.
     *
     * @param email         the address entered
     * @param returnPath    the site path the link's page continues to once the account is created, or {@code null}
     * @param clientAddress the address of the client asking
     * @return a future that fails with {@link RateLimitedException} when the address or client is over its limit
     */
    public Future<Void> requestSignUp(String email, String returnPath, String clientAddress) {
        return requestLink(email, clientAddress)
                .compose(v -> contactRepository.findCredentialByEmail(email))
                .compose(existing -> existing == null
                        ? sendLink(EmailLinkType.SIGN_UP, email, SIGN_UP_LINK_LIFETIME,
                                   token -> emailService.sendSignUpLink(email, token, returnPath))
                        : sendLink(EmailLinkType.PASSWORD_RESET, existing.contact().getEmail(), PASSWORD_RESET_LINK_LIFETIME,
                                   token -> emailService.sendPasswordResetLink(existing.contact(), token, returnPath, true)));
    }

    /**
     * Creates the account of the address a sign-up link was emailed to, with the organization, contact name and
     * password given.
     *
     * @param token the token of the sign-up link
     * @return the contact the account is signed in as, or a failure with {@link AuthenticationException} when the token
     *         is not of a usable sign-up link, or {@link org.kinotic.core.api.exceptions.AlreadyExistsException} when the
     *         address has an account by now
     */
    public Future<LicenseParticipant> completeSignUp(String token, String organization, String contactName, String password) {
        Validate.notBlank(organization, "The organization's legal name is required");
        Validate.notBlank(contactName, "Your name is required");
        validatePassword(password);
        // the link is used before the hash, so a request without a usable link costs no bcrypt work
        return useLink(EmailLinkType.SIGN_UP, token)
                .compose(link -> hashPassword(password)
                        .compose(hash -> customerService.createCustomer(organization, contactName, link.getEmail(), hash)))
                .map(LicenseParticipant::ofContact);
    }

    /**
     * Signs in the contact with the email address and password.
     *
     * @return the contact signed in, or a failure with {@link AuthenticationException} when the address has no contact
     *         or the password does not match, or {@link RateLimitedException} when attempts are over the limit
     */
    public Future<LicenseParticipant> signIn(String email, String password, String clientAddress) {
        Instant now = Instant.now();
        Future<LicenseParticipant> ret;
        if (!attemptsPerEmail.tryAcquire(email.toLowerCase(), now) || !attemptsPerAddress.tryAcquire(clientAddress, now)) {
            ret = Future.failedFuture(new RateLimitedException(TOO_MANY_REQUESTS));
        } else {
            ret = contactRepository.findCredentialByEmail(email)
                                   .compose(credential -> matches(password, credential)
                                           .compose(matched -> matched
                                                   ? Future.succeededFuture(LicenseParticipant.ofContact(credential.contact()))
                                                   : Future.failedFuture(new AuthenticationException(INVALID_CREDENTIALS))));
        }
        return ret;
    }

    /**
     * Emails the contact with the address a link to set a new password; an address with no contact is sent nothing,
     * and the answer is the same.
     *
     * @param returnPath the site path the link's page continues to once the password is set, or {@code null}
     * @return a future that fails with {@link RateLimitedException} when the address or client is over its limit
     */
    public Future<Void> requestPasswordReset(String email, String returnPath, String clientAddress) {
        return requestLink(email, clientAddress)
                .compose(v -> contactRepository.findCredentialByEmail(email))
                .compose(existing -> existing == null
                        ? Future.succeededFuture()
                        : sendLink(EmailLinkType.PASSWORD_RESET, existing.contact().getEmail(), PASSWORD_RESET_LINK_LIFETIME,
                                   token -> emailService.sendPasswordResetLink(existing.contact(), token, returnPath, false)));
    }

    /**
     * Sets the password of the contact a password reset link was emailed to.
     *
     * @param token the token of the password reset link
     * @return the contact, now signed in, or a failure with {@link AuthenticationException} when the token is not of a
     *         usable password reset link
     */
    public Future<LicenseParticipant> resetPassword(String token, String password) {
        validatePassword(password);
        return useLink(EmailLinkType.PASSWORD_RESET, token)
                .compose(link -> hashPassword(password)
                        .compose(hash -> contactRepository.updatePasswordHash(link.getEmail(), hash)))
                .compose(contact -> contact == null
                        ? Future.failedFuture(new AuthenticationException(INVALID_LINK))
                        : Future.succeededFuture(LicenseParticipant.ofContact(contact)));
    }

    private Future<Void> requestLink(String email, String clientAddress) {
        Validate.isTrue(email != null && email.contains("@"), "A valid email address is required");
        Instant now = Instant.now();
        Future<Void> ret;
        if (!linkRequestsPerEmail.tryAcquire(email.trim().toLowerCase(), now) || !linkRequestsPerAddress.tryAcquire(clientAddress, now)) {
            ret = Future.failedFuture(new RateLimitedException(TOO_MANY_REQUESTS));
        } else {
            ret = Future.succeededFuture();
        }
        return ret;
    }

    private Future<Void> sendLink(EmailLinkType type, String email, Duration lifetime, Function<String, Future<Void>> send) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        EmailLink link = new EmailLink()
                .setId(UUID.randomUUID().toString())
                .setType(type)
                .setEmail(email.trim())
                .setTokenHash(hash(token))
                .setExpiresAt(Instant.now().plus(lifetime));
        // the response ends once the link is stored, and the send runs on
        return linkRepository.save(link)
                             .onSuccess(v -> send.apply(token).onFailure(t -> log.error("The {} link could not be emailed", type, t)));
    }

    private Future<EmailLink> useLink(EmailLinkType type, String token) {
        Future<EmailLink> ret;
        if (token == null || token.isBlank()) {
            ret = Future.failedFuture(new AuthenticationException(INVALID_LINK));
        } else {
            ret = linkRepository.use(type, hash(token), Instant.now())
                                .compose(link -> link == null
                                        ? Future.failedFuture(new AuthenticationException(INVALID_LINK))
                                        : Future.succeededFuture(link));
        }
        return ret;
    }

    private static void validatePassword(String password) {
        Validate.isTrue(password != null && password.length() >= MIN_PASSWORD_LENGTH,
                        "The password must have at least %d characters", MIN_PASSWORD_LENGTH);
        Validate.isTrue(password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES,
                        "The password must be at most %d bytes long", MAX_PASSWORD_BYTES);
    }

    // bcrypt is CPU-bound for tens of milliseconds, so it runs on a worker
    private Future<String> hashPassword(String password) {
        return vertx.executeBlocking(() -> PASSWORD_ENCODER.encode(password));
    }

    private Future<Boolean> matches(String password, ContactCredential credential) {
        String hash = credential != null && credential.passwordHash() != null ? credential.passwordHash() : UNMATCHABLE_HASH;
        boolean known = credential != null && credential.passwordHash() != null;
        return vertx.executeBlocking(() -> PASSWORD_ENCODER.matches(password == null ? "" : password, hash) && known);
    }

    // the token carries 256 random bits, so an unsalted digest is as strong as the token
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
