package org.kinotic.licenses.internal.api.security;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.core.api.exceptions.AuthenticationException;
import org.kinotic.core.api.security.AuthenticationContext;
import org.kinotic.core.api.security.Participant;
import org.kinotic.core.api.security.SecurityService;
import org.kinotic.licenses.api.model.LicenseFlag;
import org.kinotic.licenses.api.model.LicenseKeyClaims;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.kinotic.licenses.internal.api.services.LicenseKeyIssuer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The gateway's {@link SecurityService}. A person signs in in a browser, bound to the browser session by the sign-in
 * routes, so a connection that presents that session is authenticated before it gets here. A Kinotic installation
 * signs in with a license key in the {@value #LICENSE_KEY_HEADER} header of its WebSocket upgrade, and is a licensee
 * of the key's license when the key is signed by this server, was issued by it, expired no more than
 * {@link #EXPIRED_KEY_ACCEPTED_FOR} ago, and its license is in good standing, past due or a trial. A key older than its
 * license's newest still signs in, and flags the license {@link LicenseFlag#POSSIBLY_COPIED}, so a restored backup
 * is never locked out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseSecurityService implements SecurityService {

    /** The upgrade header a Kinotic installation presents its license key in. */
    public static final String LICENSE_KEY_HEADER = "license-key";
    /** How long after its expiry a key still signs in. */
    public static final Duration EXPIRED_KEY_ACCEPTED_FOR = Duration.ofDays(30);

    private static final String SIGN_IN_FIRST = "Sign in first";
    private static final String KEY_REFUSED = "The license key is not accepted";

    private final LicenseKeyIssuer keyIssuer;
    private final IssuedKeyRepository issuedKeyRepository;
    private final LicenseRepository licenseRepository;

    @Override
    public Future<Participant> authenticate(Map<String, String> authenticationInfo, AuthenticationContext context) {
        String key = authenticationInfo.get(LICENSE_KEY_HEADER);
        Future<Participant> ret;
        if (key == null || key.isBlank()) {
            ret = Future.failedFuture(new AuthenticationException(SIGN_IN_FIRST));
        } else {
            ret = licensee(key);
        }
        return ret;
    }

    // every refusal answers alike, so a caller learns nothing about why its key was refused; the log says why
    private Future<Participant> licensee(String key) {
        LicenseKeyClaims claims;
        try {
            claims = keyIssuer.verify(key);
        } catch (IllegalArgumentException e) {
            log.info("A license key sign-in was refused: {}", e.getMessage());
            return Future.failedFuture(new AuthenticationException(KEY_REFUSED));
        }
        if (claims.expiresAt().plus(EXPIRED_KEY_ACCEPTED_FOR).isBefore(Instant.now())) {
            log.info("The key {} signed in more than {} after it expired", claims.keyId(), EXPIRED_KEY_ACCEPTED_FOR);
            return Future.failedFuture(new AuthenticationException(KEY_REFUSED));
        }
        return issuedKeyRepository.findByJti(claims.keyId()).compose(issued -> {
            if (issued == null) {
                log.warn("The key {} is signed by this server but was never issued", claims.keyId());
                return Future.failedFuture(new AuthenticationException(KEY_REFUSED));
            }
            return licenseRepository.findById(issued.getLicenseId()).compose(license -> {
                if (license.getStatus() != LicenseStatus.ACTIVE && license.getStatus() != LicenseStatus.PAST_DUE
                        && license.getStatus() != LicenseStatus.TRIAL) {
                    log.info("The key {} signed in for license {}, which is {}", claims.keyId(), license.getId(), license.getStatus());
                    return Future.failedFuture(new AuthenticationException(KEY_REFUSED));
                }
                return issuedKeyRepository.findLatestByLicenseId(license.getId()).compose(newest -> {
                    Future<Void> flagged = Future.succeededFuture();
                    if (!newest.getJti().equals(issued.getJti())) {
                        log.info("The license {} signed in with its older key {}; its newest is {}", license.getId(), issued.getJti(), newest.getJti());
                        flagged = licenseRepository.addFlag(license.getId(), LicenseFlag.POSSIBLY_COPIED).mapEmpty();
                    }
                    return flagged.map(v -> (Participant) LicenseParticipant.ofLicensee(license, issued.getJti(), claims.subject()));
                });
            });
        });
    }
}
