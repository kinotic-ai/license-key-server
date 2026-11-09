package org.kinotic.licenses.internal.api.rest;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import io.vertx.core.Future;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.core.api.service.SuppliesGatewayRoutes;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.internal.api.repositories.StripeEventRepository;
import org.kinotic.licenses.internal.api.services.StripeBillingService;
import org.springframework.stereotype.Component;

/**
 * Stripe's webhook endpoint. Every delivery is verified against the endpoint's signing secret, handled once however
 * many times Stripe delivers it, and answered with a failure when handling fails so that Stripe redelivers it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeWebhookRoutes implements SuppliesGatewayRoutes {

    /** The route Stripe delivers to, registered on the endpoint in the Stripe Dashboard. */
    public static final String PATH = "/api/stripe/webhook";

    private static final String SIGNATURE_HEADER = "Stripe-Signature";
    // an invoice event with its lines runs well past the gateway's 16 KB body limit
    private static final long MAX_EVENT_BYTES = 1024 * 1024;

    private final KinoticLicensesProperties properties;
    private final StripeEventRepository eventRepository;
    private final StripeBillingService billingService;

    @Override
    public void mountRoutes(Router router) {
        // Ahead of the gateway's own /api/* handlers (order 0), so this route reads the body with its own limit and
        // Stripe's server-to-server call creates no browser session
        router.post(PATH)
              .order(-1)
              .handler(BodyHandler.create(false).setBodyLimit(MAX_EVENT_BYTES))
              .handler(this::deliver);
    }

    private void deliver(RoutingContext ctx) {
        String payload = ctx.body().asString();
        String signature = ctx.request().getHeader(SIGNATURE_HEADER);
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, properties.getLicenses().getStripe().getWebhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("A Stripe webhook delivery failed signature verification: {}", e.getMessage());
            ctx.response().setStatusCode(400).end();
            return;
        }
        eventRepository.claim(event.getId(), event.getType())
                       .compose(claimed -> {
                           Future<Void> handled;
                           if (claimed) {
                               // A failure releases the claim, so Stripe's redelivery handles the event afresh; a claim left
                               // by a delivery that never finished expires for the same reason. The handler runs inside the
                               // chain so a synchronous throw, as an unreadable payload raises, is a failure like any other.
                               handled = Future.<Void>succeededFuture()
                                               .compose(v -> billingService.handleEvent(event))
                                               .compose(v -> eventRepository.markProcessed(event.getId()))
                                               .recover(t -> eventRepository.release(event.getId())
                                                                            .compose(v -> Future.failedFuture(t)));
                           } else {
                               log.debug("The Stripe event {} was processed already, or is being handled", event.getId());
                               handled = Future.succeededFuture();
                           }
                           return handled;
                       })
                       .onSuccess(v -> ctx.response().setStatusCode(200).end())
                       .onFailure(t -> {
                           log.error("Handling the Stripe event {} ({}) failed", event.getId(), event.getType(), t);
                           ctx.response().setStatusCode(500).end();
                       });
    }
}
