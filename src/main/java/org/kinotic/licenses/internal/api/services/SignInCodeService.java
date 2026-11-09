package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.internal.api.model.SignInCode;
import org.kinotic.licenses.internal.api.model.VerifiedEmail;
import org.kinotic.licenses.internal.api.repositories.CustomerContactRepository;
import org.kinotic.licenses.internal.api.repositories.SignInCodeRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Verifies email addresses with a one-time code emailed to them, which signs a customer contact in and lets anyone
 * else sign up. A code lives ten minutes and is used once; only its hash is stored. Requests and attempts are limited
 * per email address and per client address, and every address is sent a code, so a request says nothing about whether
 * the address has an account.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignInCodeService {

    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final int CODE_DIGITS = 8;
    private static final Duration LIMIT_WINDOW = Duration.ofMinutes(15);
    private static final String TOO_MANY_REQUESTS = "Too many sign-in requests; try again later";
    private static final String INVALID_CODE = "The code is not valid";

    private final SlidingWindowRateLimiter requestsPerEmail = new SlidingWindowRateLimiter(5, LIMIT_WINDOW);
    private final SlidingWindowRateLimiter requestsPerAddress = new SlidingWindowRateLimiter(30, LIMIT_WINDOW);
    private final SlidingWindowRateLimiter attemptsPerEmail = new SlidingWindowRateLimiter(10, LIMIT_WINDOW);
    private final SlidingWindowRateLimiter attemptsPerAddress = new SlidingWindowRateLimiter(50, LIMIT_WINDOW);
    private final SecureRandom random = new SecureRandom();

    private final CustomerContactRepository contactRepository;
    private final SignInCodeRepository codeRepository;
    private final EmailService emailService;

    /**
     * Emails a code to the address, whether or not it belongs to an account, so the response says nothing about it.
     *
     * @param email         the address entered
     * @param clientAddress the address of the client asking
     * @return a future that fails with {@link RateLimitedException} when the address or client is over its limit
     */
    public Future<Void> requestCode(String email, String clientAddress) {
        Instant now = Instant.now();
        Future<Void> ret;
        if (!requestsPerEmail.tryAcquire(email.toLowerCase(), now) || !requestsPerAddress.tryAcquire(clientAddress, now)) {
            ret = Future.failedFuture(new RateLimitedException(TOO_MANY_REQUESTS));
        } else {
            String code = generateCode();
            SignInCode signInCode = new SignInCode()
                    .setId(UUID.randomUUID().toString())
                    .setEmail(email.trim())
                    .setExpiresAt(now.plus(CODE_LIFETIME));
            signInCode.setCodeHash(hash(signInCode.getId(), code));
            // the response ends once the code is stored, and the send runs on
            ret = codeRepository.save(signInCode)
                                .onSuccess(v -> emailService.sendSignInCode(signInCode.getEmail(), code)
                                                            .onFailure(t -> log.error("The sign-in code could not be emailed", t)));
        }
        return ret;
    }

    /**
     * Verifies the address with a code emailed to it, and finds the account it belongs to.
     *
     * @return the verified address with the contact it signs in as, or none when it has no account yet; or a failure
     *         with {@link AuthenticationException} when the code is not a usable code of the address, or
     *         {@link RateLimitedException} when attempts are over the limit
     */
    public Future<VerifiedEmail> verifyCode(String email, String code, String clientAddress) {
        Instant now = Instant.now();
        Future<VerifiedEmail> ret;
        if (!attemptsPerEmail.tryAcquire(email.toLowerCase(), now) || !attemptsPerAddress.tryAcquire(clientAddress, now)) {
            ret = Future.failedFuture(new RateLimitedException(TOO_MANY_REQUESTS));
        } else {
            ret = codeRepository.findUsable(email, now)
                                .compose(codes -> useMatching(codes, code, now))
                                .compose(v -> contactRepository.findByEmail(email))
                                .map(contacts -> contacts.isEmpty()
                                        ? new VerifiedEmail(email.trim(), null)
                                        : new VerifiedEmail(contacts.getFirst().getEmail(), LicenseParticipant.ofContact(contacts.getFirst())));
        }
        return ret;
    }

    private Future<Void> useMatching(List<SignInCode> codes, String code, Instant now) {
        SignInCode match = null;
        for (SignInCode candidate : codes) {
            if (MessageDigest.isEqual(candidate.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                                      hash(candidate.getId(), code).getBytes(StandardCharsets.US_ASCII))) {
                match = candidate;
            }
        }
        Future<Void> ret;
        if (match == null) {
            ret = Future.failedFuture(new AuthenticationException(INVALID_CODE));
        } else {
            // the UPDATE is the one-use guard: two verifications racing on one code see one succeed
            ret = codeRepository.markUsed(match.getId(), now).compose(used -> used
                    ? Future.succeededFuture()
                    : Future.failedFuture(new AuthenticationException(INVALID_CODE)));
        }
        return ret;
    }


    private String generateCode() {
        StringBuilder ret = new StringBuilder(CODE_DIGITS);
        for (int i = 0; i < CODE_DIGITS; i++) {
            ret.append(random.nextInt(10));
        }
        return ret.toString();
    }

    // salted with the row id so equal codes of different rows never share a hash
    private static String hash(String id, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((id + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
