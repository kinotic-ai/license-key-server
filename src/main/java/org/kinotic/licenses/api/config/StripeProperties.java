package org.kinotic.licenses.api.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Stripe Billing, bound under {@code kinotic.licenses.stripe}. Test-mode keys select Stripe's test mode.
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class StripeProperties {

    /**
     * The secret API key ({@code sk_test_...} or {@code sk_live_...}).
     */
    private String secretKey;

    /**
     * The signing secret of the webhook endpoint that delivers to {@code POST /api/stripe/webhook}
     * ({@code whsec_...}).
     */
    private String webhookSecret;

}
