package org.kinotic.licenses.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.licenses.api.model.IssuedKey;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.services.LicenseRenewalService;
import org.kinotic.licenses.internal.api.repositories.IssuedKeyRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * The key renewal of the license the calling installation signed in with.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultLicenseRenewalService implements LicenseRenewalService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM uuuu").withZone(ZoneOffset.UTC);

    private final SecurityContext securityContext;
    private final LicenseRepository licenseRepository;
    private final IssuedKeyRepository issuedKeyRepository;
    private final LicenseService licenseService;

    @Override
    public CompletableFuture<String> renew() {
        LicenseParticipant caller = securityContext.requireParticipant(LicenseParticipant.class);
        return licenseRepository.findById(caller.getLicenseId())
                                .compose(license -> license.getStatus() == LicenseStatus.ACTIVE
                                        ? licenseService.issueKeyIfExtended(license).compose(issued -> newest(license))
                                        : pastDue(caller, license))
                                .map(IssuedKey::getKey)
                                .onSuccess(key -> log.debug("The license {} renewed its key", caller.getLicenseId()))
                                .toCompletionStage()
                                .toCompletableFuture();
    }

    // A license that is not active gets no new key. An installation holding an older key than the newest gets the newest,
    // as a restored backup does; one holding the newest is told why no newer one comes.
    private Future<IssuedKey> pastDue(LicenseParticipant caller, License license) {
        return newest(license).compose(newest -> {
            Future<IssuedKey> ret;
            if (!newest.getJti().equals(caller.getKeyId())) {
                ret = Future.succeededFuture(newest);
            } else {
                String since = license.getPastDueSince() != null ? " since " + DATE.format(license.getPastDueSince()) : "";
                ret = Future.failedFuture(new IllegalStateException(
                        "The license is " + license.getStatus().name().toLowerCase().replace('_', ' ') + since
                                + ", so no newer key is issued; the newest key expires on " + DATE.format(newest.getExpiresAt())
                                + ". Settle the payment in your account at licenses.kinotic.ai."));
            }
            return ret;
        });
    }

    private Future<IssuedKey> newest(License license) {
        return issuedKeyRepository.findLatestByLicenseId(license.getId());
    }
}
