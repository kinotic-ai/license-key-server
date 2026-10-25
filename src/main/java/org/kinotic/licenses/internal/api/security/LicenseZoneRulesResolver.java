package org.kinotic.licenses.internal.api.security;

import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.event.ZoneRules;
import org.kinotic.core.api.event.ZoneRulesResolver;
import org.kinotic.core.api.security.Participant;
import org.kinotic.licenses.api.security.LicenseParticipant;
import org.kinotic.licenses.api.security.LicenseZones;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The zones each participant type may address: staff the staff and customer zones, a customer the customer zone.
 */
@Component
public class LicenseZoneRulesResolver implements ZoneRulesResolver {

    private static final Set<String> STAFF_ZONES = Set.of(LicenseZones.STAFF, LicenseZones.CUSTOMER);
    private static final Set<String> CUSTOMER_ZONES = Set.of(LicenseZones.CUSTOMER);

    @Override
    public ZoneRules resolve(Participant participant) {
        Validate.notNull(participant, "participant must not be null");
        if (!(participant instanceof LicenseParticipant licenseParticipant)) {
            throw new IllegalArgumentException("Unknown participant type " + participant.getClass().getName()
                                                       + ", no zone routing rules exist for it");
        }
        return switch (licenseParticipant.getType()) {
            case STAFF -> ZoneRules.of(STAFF_ZONES, STAFF_ZONES);
            case CUSTOMER -> ZoneRules.of(CUSTOMER_ZONES, CUSTOMER_ZONES);
        };
    }
}
