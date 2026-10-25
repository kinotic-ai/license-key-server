package org.kinotic.licenses.internal.api.services;

import com.stripe.StripeClient;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PriceListParams;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.checkout.SessionCreateParams;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.PlanInfo;
import org.kinotic.licenses.internal.api.model.PlanCatalog;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the server does with Stripe Billing. Customers pay through a monthly subscription started in Stripe
 * Checkout and managed in the Stripe Customer Portal; the server stores Stripe ids only and never sees payment
 * details. Prices live on the Stripe Prices with the plans' lookup keys. Stripe's webhook events drive the license:
 * a completed Checkout creates it and issues its first key, paid and failed invoices move it in and out of good
 * standing, and subscription changes keep its plan, status and billing period current.
 */
@Slf4j
@Component
public class StripeBillingService {

    private static final Duration PLAN_CACHE_LIFETIME = Duration.ofMinutes(10);
    private static final String SUCCESS_PATH = "/buy/success?session_id={CHECKOUT_SESSION_ID}";
    private static final String CANCEL_PATH = "/buy?plan=";
    private static final String ACCOUNT_PATH = "/account";
    private static final String CUSTOMER_ID_METADATA = "kinoticCustomerId";
    private static final String PLAN_METADATA = "kinoticPlan";
    private static final String SUBSCRIPTION_MODE = "subscription";
    private static final BigDecimal MINOR_UNITS = BigDecimal.valueOf(100);

    private final KinoticLicensesProperties properties;
    private final CustomerRepository customerRepository;
    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final Vertx vertx;
    private final StripeClient stripe;

    private volatile PlanCatalog catalog;

    public StripeBillingService(KinoticLicensesProperties properties,
                                CustomerRepository customerRepository,
                                LicenseRepository licenseRepository,
                                LicenseService licenseService,
                                Vertx vertx) {
        this.properties = properties;
        this.customerRepository = customerRepository;
        this.licenseRepository = licenseRepository;
        this.licenseService = licenseService;
        this.vertx = vertx;
        this.stripe = new StripeClient(properties.getLicenses().getStripe().getSecretKey());
    }

    /**
     * The plans with their current prices, read from Stripe and cached for a few minutes.
     */
    public Future<List<PlanInfo>> listPlans() {
        return catalog().map(PlanCatalog::plans);
    }

    /**
     * Starts a purchase: creates the customer's Stripe customer if it has none and a Checkout Session for a monthly
     * subscription to the plan, which returns to the success page with the session id.
     *
     * @param customer     the buyer
     * @param contactName  the billing contact's name, shown on the Stripe customer
     * @param contactEmail the billing contact's email, which Stripe sends receipts to
     * @param plan         the paid plan
     * @return the Checkout Session URL to send the browser to
     */
    public Future<String> startCheckout(Customer customer, String contactName, String contactEmail, Plan plan) {
        Validate.isTrue(plan != Plan.FREE, "The free plan is requested, not bought");
        String publicBaseUrl = properties.getLicenses().getPublicBaseUrl();
        return catalog().compose(plans -> {
            String priceId = plans.priceIds().get(plan);
            Validate.validState(priceId != null, "Stripe has no active Price with the lookup key %s", plan.lookupKey());
            return ensureStripeCustomer(customer, contactName, contactEmail).compose(stripeCustomerId -> blocking(() -> {
                SessionCreateParams params = SessionCreateParams.builder()
                        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                        .setCustomer(stripeCustomerId)
                        .setClientReferenceId(customer.getId())
                        .addLineItem(SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
                        .setSuccessUrl(publicBaseUrl + SUCCESS_PATH)
                        .setCancelUrl(publicBaseUrl + CANCEL_PATH + plan.name())
                        .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.REQUIRED)
                        .putMetadata(CUSTOMER_ID_METADATA, customer.getId())
                        .putMetadata(PLAN_METADATA, plan.name())
                        .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                                                                                 .putMetadata(CUSTOMER_ID_METADATA, customer.getId())
                                                                                 .putMetadata(PLAN_METADATA, plan.name())
                                                                                 .build())
                        .build();
                return stripe.checkout().sessions().create(params).getUrl();
            }));
        });
    }

    /**
     * Opens a Customer Portal session, which returns to the customer account.
     *
     * @throws IllegalStateException when the customer has no Stripe customer
     */
    public Future<String> createPortalSession(Customer customer) {
        Validate.validState(customer.getStripeCustomerId() != null, "The customer %s has never bought a plan", customer.getId());
        return blocking(() -> stripe.billingPortal().sessions().create(
                com.stripe.param.billingportal.SessionCreateParams.builder()
                                                                   .setCustomer(customer.getStripeCustomerId())
                                                                   .setReturnUrl(properties.getLicenses().getPublicBaseUrl() + ACCOUNT_PATH)
                                                                   .build()).getUrl());
    }

    /**
     * Applies a webhook event to the license it concerns. An event about a subscription this server has no license for
     * is logged and otherwise ignored, as is an event type the server does not handle.
     */
    public Future<Void> handleEvent(Event event) {
        return switch (event.getType()) {
            case "checkout.session.completed" -> checkoutCompleted((Session) dataObject(event));
            case "invoice.paid" -> invoicePaid((Invoice) dataObject(event));
            case "invoice.payment_failed" -> invoicePaymentFailed((Invoice) dataObject(event));
            case "customer.subscription.updated" -> subscriptionUpdated((Subscription) dataObject(event));
            case "customer.subscription.deleted" -> subscriptionDeleted((Subscription) dataObject(event));
            default -> {
                log.debug("Ignoring the Stripe event {} of type {}", event.getId(), event.getType());
                yield Future.succeededFuture();
            }
        };
    }

    private Future<Void> checkoutCompleted(Session session) {
        Future<Void> ret;
        if (!SUBSCRIPTION_MODE.equals(session.getMode()) || session.getSubscription() == null) {
            log.warn("The Checkout Session {} completed without a subscription; no license is created", session.getId());
            ret = Future.succeededFuture();
        } else {
            // the success page can call before this event arrives, and Stripe can deliver it more than once
            ret = licenseRepository.findByCheckoutSessionId(session.getId()).compose(existing -> {
                Future<Void> created;
                if (existing != null) {
                    created = Future.succeededFuture();
                } else {
                    created = customerRepository.findById(session.getClientReferenceId())
                                                .compose(customer -> createLicenseFor(customer, session));
                }
                return created;
            });
        }
        return ret;
    }

    private Future<Void> createLicenseFor(Customer customer, Session session) {
        Validate.validState(customer != null, "The Checkout Session %s names no customer of this server", session.getId());
        return blocking(() -> stripe.subscriptions().retrieve(session.getSubscription(),
                                                              SubscriptionRetrieveParams.builder().addExpand("items.data.price").build()))
                .compose(subscription -> {
                    SubscriptionItem item = subscription.getItems().getData().getFirst();
                    Plan plan = Plan.fromLookupKey(item.getPrice().getLookupKey());
                    return licenseService.createLicense(customer, plan, subscription.getId(), session.getId(),
                                                        Instant.ofEpochSecond(item.getCurrentPeriodEnd()));
                })
                .compose(created -> licenseService.emailNewKey(created.license(), created.key()))
                .onSuccess(v -> log.info("The Checkout Session {} bought a license for customer {}", session.getId(), customer.getId()));
    }

    private Future<Void> invoicePaid(Invoice invoice) {
        Instant paidAt = invoice.getStatusTransitions() != null && invoice.getStatusTransitions().getPaidAt() != null
                ? Instant.ofEpochSecond(invoice.getStatusTransitions().getPaidAt())
                : Instant.now();
        return withLicenseOf(subscriptionOf(invoice), license -> licenseService.recordPayment(license, paidAt).mapEmpty());
    }

    private Future<Void> invoicePaymentFailed(Invoice invoice) {
        return withLicenseOf(subscriptionOf(invoice), license -> {
            // Stripe retries the payment and sends this on each failure; the contacts hear from this server once
            boolean firstFailure = license.getStatus() != LicenseStatus.PAST_DUE;
            return licenseService.markPastDue(license)
                                 .compose(updated -> firstFailure ? licenseService.emailPastDue(updated) : Future.succeededFuture());
        });
    }

    private Future<Void> subscriptionUpdated(Subscription subscription) {
        return withLicenseOf(subscription.getId(), license -> {
            SubscriptionItem item = subscription.getItems().getData().getFirst();
            Plan plan = item.getPrice().getLookupKey() != null ? Plan.fromLookupKey(item.getPrice().getLookupKey()) : license.getPlan();
            Instant periodEnd = item.getCurrentPeriodEnd() != null ? Instant.ofEpochSecond(item.getCurrentPeriodEnd()) : license.getCurrentPeriodEnd();
            return licenseService.syncSubscription(license, plan, statusOf(subscription.getStatus()), periodEnd).mapEmpty();
        });
    }

    private Future<Void> subscriptionDeleted(Subscription subscription) {
        return withLicenseOf(subscription.getId(), license -> licenseService.cancel(license).mapEmpty());
    }

    private Future<Void> withLicenseOf(String subscriptionId, java.util.function.Function<License, Future<Void>> action) {
        Future<Void> ret;
        if (subscriptionId == null) {
            log.debug("A Stripe event named no subscription; nothing to apply it to");
            ret = Future.succeededFuture();
        } else {
            ret = licenseRepository.findByStripeSubscriptionId(subscriptionId).compose(license -> {
                Future<Void> applied;
                if (license == null) {
                    log.info("No license has the Stripe subscription {}; the event is ignored", subscriptionId);
                    applied = Future.succeededFuture();
                } else {
                    applied = action.apply(license);
                }
                return applied;
            });
        }
        return ret;
    }

    private static String subscriptionOf(Invoice invoice) {
        return invoice.getParent() != null && invoice.getParent().getSubscriptionDetails() != null
                ? invoice.getParent().getSubscriptionDetails().getSubscription()
                : null;
    }

    private static LicenseStatus statusOf(String stripeStatus) {
        return switch (stripeStatus) {
            case "active", "trialing" -> LicenseStatus.ACTIVE;
            case "canceled", "incomplete_expired" -> LicenseStatus.CANCELED;
            default -> LicenseStatus.PAST_DUE;
        };
    }

    // an event whose API version differs from the library's deserializes through the unsafe path, which maps what it can
    private static StripeObject dataObject(Event event) {
        return event.getDataObjectDeserializer().getObject().orElseGet(() -> {
            try {
                return event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                throw new IllegalStateException("The Stripe event " + event.getId() + " could not be read", e);
            }
        });
    }

    private Future<String> ensureStripeCustomer(Customer customer, String contactName, String contactEmail) {
        Future<String> ret;
        if (customer.getStripeCustomerId() != null) {
            ret = Future.succeededFuture(customer.getStripeCustomerId());
        } else {
            ret = blocking(() -> stripe.customers().create(CustomerCreateParams.builder()
                                                                               .setName(customer.getLegalName())
                                                                               .setEmail(contactEmail)
                                                                               .setDescription("Billing contact: " + contactName)
                                                                               .putMetadata(CUSTOMER_ID_METADATA, customer.getId())
                                                                               .build()).getId())
                    .compose(stripeCustomerId -> customerRepository.updateStripeCustomerId(customer.getId(), stripeCustomerId)
                                                                   .map(stripeCustomerId));
        }
        return ret;
    }

    private Future<PlanCatalog> catalog() {
        PlanCatalog current = catalog;
        Future<PlanCatalog> ret;
        if (current != null && current.fetchedAt().plus(PLAN_CACHE_LIFETIME).isAfter(Instant.now())) {
            ret = Future.succeededFuture(current);
        } else {
            ret = blocking(this::readCatalog).onSuccess(read -> catalog = read);
        }
        return ret;
    }

    private PlanCatalog readCatalog() throws Exception {
        List<String> lookupKeys = Arrays.stream(Plan.values()).filter(plan -> plan != Plan.FREE).map(Plan::lookupKey).toList();
        List<Price> prices = stripe.prices().list(PriceListParams.builder()
                                                                 .setActive(true)
                                                                 .addAllLookupKey(lookupKeys)
                                                                 .addExpand("data.product")
                                                                 .build()).getData();
        List<PlanInfo> plans = new ArrayList<>();
        Map<Plan, String> priceIds = new EnumMap<>(Plan.class);
        String currency = "usd";
        for (Price price : prices) {
            Plan plan = Plan.fromLookupKey(price.getLookupKey());
            Product product = price.getProductObject();
            currency = price.getCurrency();
            priceIds.put(plan, price.getId());
            plans.add(new PlanInfo()
                              .setId(plan)
                              .setName(product != null ? product.getName() : plan.name())
                              .setMonthlyPrice(price.getUnitAmountDecimal().divide(MINOR_UNITS))
                              .setCurrency(price.getCurrency())
                              .setFeatures(plan.features())
                              .setHighlights(product != null && product.getMarketingFeatures() != null
                                                     ? product.getMarketingFeatures().stream().map(Product.MarketingFeature::getName).toList()
                                                     : List.of())
                              .setRequiresApproval(false));
        }
        plans.add(new PlanInfo()
                          .setId(Plan.FREE)
                          .setName("Free")
                          .setMonthlyPrice(BigDecimal.ZERO)
                          .setCurrency(currency)
                          .setFeatures(Plan.FREE.features())
                          .setHighlights(List.of())
                          .setRequiresApproval(true));
        plans.sort(Comparator.comparing(PlanInfo::getId));
        return new PlanCatalog(List.copyOf(plans), priceIds, Instant.now());
    }

    // stripe-java blocks on its HTTP calls, so every call runs on a worker thread
    private <T> Future<T> blocking(java.util.concurrent.Callable<T> call) {
        return vertx.executeBlocking(call);
    }
}
