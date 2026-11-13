package org.kinotic.licenses.api.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.kinotic.core.api.security.Participant;
import org.kinotic.licenses.api.model.CustomerContact;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicensedCluster;

import java.util.List;
import java.util.Map;

/**
 * A signed-in staff member, customer contact or Kinotic installation. Equality is by {@code id}, following the {@link Participant}
 * convention. Nothing sensitive belongs here: the participant travels with every request it sends.
 */
@Getter
@ToString
@EqualsAndHashCode(of = "id")
public class LicenseParticipant implements Participant {

    /** The staff member's email, the customer contact's id, or the installation's licensed cluster id. */
    private final String id;
    private final ParticipantType type;
    /** The customer the contact or installation belongs to; {@code null} for staff. */
    private final String customerId;
    /** The license an installation signed in with; {@code null} for a person. */
    private final String licenseId;
    /** The licensed cluster an installation signed in as; {@code null} for a person. */
    private final String licensedClusterId;
    /** The id of the key an installation signed in with; {@code null} for a person. */
    private final String keyId;
    /** The signed-in email address; {@code null} for an installation. */
    private final String email;
    /** The signed-in person's name, as the provider or the customer record gives it, or the licensee's legal name. */
    private final String name;
    private final Map<String, String> metadata;
    private final List<String> roles;

    /**
     * The participant a customer contact signs in as.
     */
    public static LicenseParticipant ofContact(CustomerContact contact) {
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

    /**
     * The participant a Kinotic installation signs in as with a key issued for one of the license's clusters.
     */
    public static LicenseParticipant ofLicensee(License license, LicensedCluster cluster, String keyId, String licensee) {
        return LicenseParticipant.builder()
                                 .id(cluster.getId())
                                 .type(ParticipantType.LICENSEE)
                                 .customerId(license.getCustomerId())
                                 .licenseId(license.getId())
                                 .licensedClusterId(cluster.getId())
                                 .keyId(keyId)
                                 .name(licensee)
                                 .metadata(Map.of())
                                 .roles(List.of())
                                 .build();
    }

    @Builder
    @JsonCreator
    public LicenseParticipant(@JsonProperty("id") String id,
                              @JsonProperty("type") ParticipantType type,
                              @JsonProperty("customerId") String customerId,
                              @JsonProperty("licenseId") String licenseId,
                              @JsonProperty("licensedClusterId") String licensedClusterId,
                              @JsonProperty("keyId") String keyId,
                              @JsonProperty("email") String email,
                              @JsonProperty("name") String name,
                              @JsonProperty("metadata") Map<String, String> metadata,
                              @JsonProperty("roles") List<String> roles) {
        this.id = id;
        this.type = type;
        this.customerId = customerId;
        this.licenseId = licenseId;
        this.licensedClusterId = licensedClusterId;
        this.keyId = keyId;
        this.email = email;
        this.name = name;
        this.metadata = metadata;
        this.roles = roles;
    }
}
