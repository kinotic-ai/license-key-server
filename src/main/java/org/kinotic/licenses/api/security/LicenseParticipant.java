package org.kinotic.licenses.api.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.kinotic.core.api.security.Participant;
import org.kinotic.licenses.api.model.CustomerContact;

import java.util.List;
import java.util.Map;

/**
 * A signed-in staff member or customer contact. Equality is by {@code id}, following the {@link Participant}
 * convention. Nothing sensitive belongs here: the participant travels with every request it sends.
 */
@Getter
@ToString
@EqualsAndHashCode(of = "id")
public class LicenseParticipant implements Participant {

    /** The staff member's email, or the customer contact's id. */
    private final String id;
    private final ParticipantType type;
    /** The customer the contact belongs to; {@code null} for staff. */
    private final String customerId;
    /** The signed-in email address. */
    private final String email;
    /** The signed-in person's name, as the provider or the customer record gives it. */
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

    @Builder
    @JsonCreator
    public LicenseParticipant(@JsonProperty("id") String id,
                              @JsonProperty("type") ParticipantType type,
                              @JsonProperty("customerId") String customerId,
                              @JsonProperty("email") String email,
                              @JsonProperty("name") String name,
                              @JsonProperty("metadata") Map<String, String> metadata,
                              @JsonProperty("roles") List<String> roles) {
        this.id = id;
        this.type = type;
        this.customerId = customerId;
        this.email = email;
        this.name = name;
        this.metadata = metadata;
        this.roles = roles;
    }
}
