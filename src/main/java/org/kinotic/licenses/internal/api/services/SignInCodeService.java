package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.security.ParticipantType;
import org.kinotic.licenses.internal.api.model.SignInCode;
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
import java.util.Map;
import java.util.UUID;

/**
 * Signs customer contacts in with a one-time code emailed to them. A code lives ten minutes and is used once; only
 * its hash is stored. Requests and attempts are limited per email address and per client address, and a request
 * for an address that belongs to no contact completes exactly like one that does.
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
     * Emails a code to the address if a contact has it. Completes the same way whether or not one does.
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
            ret = contactRepository.findByEmail(email).compose(contacts -> {
                Future<Void> sent;
                if (contacts.isEmpty()) {
                    log.info("A sign-in code was requested for an address with no contact");
                    sent = Future.succeededFuture();
                } else {
                    String code = generateCode();
                    SignInCode signInCode = new SignInCode()
                            .setId(UUID.randomUUID().toString())
                            .setEmail(contacts.getFirst().getEmail())
                            .setExpiresAt(now.plus(CODE_LIFETIME));
                    signInCode.setCodeHash(hash(signInCode.getId(), code));
                    sent = codeRepository.save(signInCode)
                                         .compose(v -> emailService.sendSignInCode(signInCode.getEmail(), code));
                }
                return sent;
            });
        }
        return ret;
    }

    /**
     * Signs the contact in with a code emailed to the address.
     *
     * @return the customer participant, or a failure with {@link AuthenticationException} when the code is not a
     *         usable code of the address, or {@link RateLimitedException} when attempts are over the limit
     */
    public Future<LicenseParticipant> verifyCode(String email, String code, String clientAddress) {
        Instant now = Instant.now();
        Future<LicenseParticipant> ret;
        if (!attemptsPerEmail.tryAcquire(email.toLowerCase(), now) || !attemptsPerAddress.tryAcquire(clientAddress, now)) {
            ret = Future.failedFuture(new RateLimitedException(TOO_MANY_REQUESTS));
        } else {
            ret = codeRepository.findUsable(email, now)
                                .compose(codes -> useMatching(codes, code, now))
                                .compose(v -> contactRepository.findByEmail(email))
                                .compose(contacts -> {
                                    Future<LicenseParticipant> participant;
                                    if (contacts.isEmpty()) {
                                        participant = Future.failedFuture(new AuthenticationException(INVALID_CODE));
                                    } else {
                                        participant = Future.succeededFuture(toParticipant(contacts.getFirst()));
                                    }
                                    return participant;
                                });
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

    private static LicenseParticipant toParticipant(CustomerContact contact) {
        return LicenseParticipant.builder()
                                 .id(contact.getId())
                                 .type(ParticipantType.CUSTOMER)
                                 .customerId(contact.getCustomerId())
                                 .email(contact.getEmail())
                                 .name(contact.getName())
                                 .metadata(Map.of())
                                 .roles(List.of())
                                 .build();
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
