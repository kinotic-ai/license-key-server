package org.kinotic.licenses.api.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.Duration;

/**
 * Outbound email through Azure Communication Services, bound under {@code kinotic.licenses.email}.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class EmailProperties {

    /**
     * When {@code false}, no email is sent: each email's recipient, subject and links are logged instead, for
     * development without an Azure login.
     */
    private boolean enabled = true;

    /**
     * The Communication Services endpoint, such as {@code https://acs-kinotic.communication.azure.com}.
     */
    private String endpoint;

    /**
     * The verified sender address on the kinotic.ai domain, such as {@code licenses@kinotic.ai}.
     */
    private String senderAddress;

    /**
     * How long a send may take before it is failed.
     */
    private Duration sendTimeout = Duration.ofSeconds(30);

}
