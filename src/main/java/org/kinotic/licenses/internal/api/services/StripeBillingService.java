package org.kinotic.licenses.internal.api.services;

import com.stripe.StripeClient;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import com.stripe.service.V1Services;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PriceListParams;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.checkout.SessionCreateParams;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.exceptions.AlreadyExistsException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.kinotic.licenses.api.config.KinoticLicensesProperties;
import org.kinotic.licenses.api.model.Customer;
import org.kinotic.licenses.api.model.License;
import org.kinotic.licenses.api.model.LicenseStatus;
import org.kinotic.licenses.api.model.BillingPeriod;
import org.kinotic.licenses.api.model.CheckoutRequest;
import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.PlanPrice;
import org.kinotic.licenses.internal.api.model.PaidSubscription;
import org.kinotic.licenses.internal.api.model.PriceCatalog;
import org.kinotic.licenses.internal.api.model.PriceLookupKey;
import org.kinotic.licenses.internal.api.repositories.CustomerRepository;
import org.kinotic.licenses.internal.api.repositories.LicenseRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Everything the server does with Stripe Billing. Customers pay through a monthly or annual subscription started in
 * Stripe Checkout and managed in the Stripe Customer Portal; the server stores Stripe ids only and never sees payment
 * details. Prices live on Stripe Prices named by {@link PriceLookupKey}: a price list is the set of Prices with one
 * version, and the current price list is the highest version with an active Price for every paid plan and billing
 * period. Stripe's webhook events drive the license: a completed Checkout creates it, or moves the customer's free
 * license onto the subscription, and issues its first key once the first invoice is paid, and every event about the
 * subscription brings the license's plan, billing period, status and scheduled cancellation to what Stripe has now.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeBillingService {

    private static final Duration PRICE_CACHE_LIFETIME = Duration.ofMinutes(10);
    private static final String SUCCESS_PATH = "/buy/success?session_id={CHECKOUT_SESSION_ID}";
    private static final String CANCEL_PATH = "/buy?plan=%s&billing=%s";
    private static final String ACCOUNT_PATH = "/account";
    private static final String CUSTOMER_ID_METADATA = "kinoticCustomerId";
    // the free license a purchase moves onto the subscription it buys
    private static final String LICENSE_ID_METADATA = "kinoticLicenseId";
    private static final String SUBSCRIPTION_MODE = "subscription";
    private static final BigDecimal MINOR_UNITS = BigDecimal.valueOf(100);
    private static final String PAID = "paid";
    private static final String ALREADY_SUBSCRIBED = "This account already has a subscription. Change its plan from your account.";

    private final KinoticLicensesProperties properties;
    private final CustomerRepository customerRepository;
    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final Vertx vertx;
    private final ObjectProvider<StripeClient> stripeClient;

    // the work under way for each subscription, which the next event for it waits for
    private final Map<String, Future<Void>> reconciling = new ConcurrentHashMap<>();
    private volatile PriceCatalog catalog;

    /**
     * The prices the customer buys at: those of the price list its active free license keeps, or of the current price
     * list. Read from Stripe and cached for a few minutes.
     */
    public Future<List<PlanPrice>> findPrices(Customer customer) {
        return freeLicenseOf(customer).compose(free -> catalog().map(prices -> {
            int priceList = priceListFor(free, prices);
            List<PlanPrice> ret = new ArrayList<>();
            prices.prices().forEach((key, price) -> {
                if (key.priceList() == priceList) {
                    ret.add(new PlanPrice().setPlan(key.plan())
                                           .setBillingPeriod(key.period())
                                           .setPriceList(priceList)
                                           .setAmount(price.getUnitAmountDecimal().divide(MINOR_UNITS))
                                           .setCurrency(price.getCurrency()));
                }
            });
            ret.sort(Comparator.comparing(PlanPrice::getPlan).thenComparing(PlanPrice::getBillingPeriod));
            return ret;
        }));
    }

    /**
     * The current price list, which a new license starts on.
     *
     * @return the version, or a failure with {@link IllegalStateException} when Stripe holds no complete price list
     */
    public Future<Integer> currentPriceList() {
        return catalog().map(prices -> {
            Validate.validState(prices.currentPriceList() > 0, "Stripe has no price list with an active Price for every paid plan and billing period");
            return prices.currentPriceList();
        });
    }

    /**
     * Starts a purchase: creates the customer's Stripe customer if it has none and a Checkout Session for a
     * subscription to the plan, billed for the period chosen, at the price list the customer buys at (see
     * {@link #findPrices(Customer)}). It returns to the success page with the session id.
     *
     * @param customer     the buyer
     * @param contactName  the billing contact's name, shown on the Stripe customer
     * @param contactEmail the billing contact's email, which Stripe sends receipts to
     * @param request      the plan, billing period and the company's figures, which must match the plan
     * @return the Checkout Session URL to send the browser to, or a failure with {@link AlreadyExistsException} when
     *         the customer already holds a paid subscription, or with {@link IllegalArgumentException} when the request
     *         is not valid or the figures set another plan
     */
    public Future<String> startCheckout(Customer customer, String contactName, String contactEmail, CheckoutRequest request) {
        validate(request);
        String publicBaseUrl = properties.getLicenses().getPublicBaseUrl();
        return licenseRepository.findByCustomerId(customer.getId()).compose(licenses -> {
            boolean subscribed = licenses.stream().anyMatch(license -> license.getPlan() != Plan.FREE
                    && (license.getStatus() == LicenseStatus.ACTIVE || license.getStatus() == LicenseStatus.PAST_DUE));
            return subscribed
                    ? Future.<License>failedFuture(new AlreadyExistsException(ALREADY_SUBSCRIBED))
                    : freeLicenseOf(customer);
        }).compose(free -> catalog().compose(prices -> {
            PriceLookupKey key = new PriceLookupKey(priceListFor(free, prices), request.getPlan(), request.getBillingPeriod());
            Price price = prices.prices().get(key);
            Validate.validState(price != null, "Stripe has no active Price with the lookup key %s", key.lookupKey());
            return ensureStripeCustomer(customer, contactName, contactEmail).compose(stripeCustomerId -> blocking(() -> {
                Map<String, String> metadata = new HashMap<>();
                metadata.put(CUSTOMER_ID_METADATA, customer.getId());
                if (free != null) {
                    metadata.put(LICENSE_ID_METADATA, free.getId());
                }
                String cancelPath = CANCEL_PATH.formatted(request.getPlan().name().toLowerCase(Locale.ROOT),
                                                          request.getBillingPeriod().name().toLowerCase(Locale.ROOT));
                SessionCreateParams params = SessionCreateParams.builder()
                        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                        .setCustomer(stripeCustomerId)
                        .setClientReferenceId(customer.getId())
                        .addLineItem(SessionCreateParams.LineItem.builder().setPrice(price.getId()).setQuantity(1L).build())
                        .setSuccessUrl(publicBaseUrl + SUCCESS_PATH)
                        .setCancelUrl(publicBaseUrl + cancelPath)
                        .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.REQUIRED)
                        .putAllMetadata(metadata)
                        .setSubscriptionData(SessionCreateParams.SubscriptionData.builder().putAllMetadata(metadata).build())
                        .build();
                return stripe().checkout().sessions().create(params).getUrl();
            }));
        }));
    }

    /**
     * Cancels the license's Stripe subscription at once if Stripe still has it unpaid, as when the license lapses.
     *
     * @return whether it was cancelled; {@code false} when Stripe has it in good standing or ended by now
     */
    public Future<Boolean> cancelIfUnpaid(License license) {
        String subscriptionId = license.getStripeSubscriptionId();
        return oneAtATime(subscriptionId, () -> currentSubscription(subscriptionId).compose(subscription -> {
            Future<Boolean> ret;
            if (statusOf(subscription.getStatus()) == LicenseStatus.PAST_DUE) {
                ret = blocking(() -> stripe().subscriptions().cancel(subscriptionId)).map(true);
            } else {
                ret = Future.succeededFuture(false);
            }
            return ret;
        }));
    }

    /**
     * Opens a Customer Portal session, which returns to the customer account.
     *
     * @throws IllegalStateException when the customer has no Stripe customer
     */
    public Future<String> createPortalSession(Customer customer) {
        Validate.validState(customer.getStripeCustomerId() != null, "The customer %s has never bought a plan", customer.getId());
        return blocking(() -> stripe().billingPortal().sessions().create(
                com.stripe.param.billingportal.SessionCreateParams.builder()
                                                                   .setCustomer(customer.getStripeCustomerId())
                                                                   .setReturnUrl(properties.getLicenses().getPublicBaseUrl() + ACCOUNT_PATH)
                                                                   .build()).getUrl());
    }

    /**
     * Applies a webhook event to the license it concerns. An event only names the subscription that changed: the
     * license takes the subscription's state from Stripe as it is when the event is handled, so events delivered late,
     * twice or out of order leave the license as Stripe has it. An event about a subscription this server has no
     * license for is logged and otherwise ignored, as is an event type the server does not handle.
     */
    public Future<Void> handleEvent(Event event) {
        return switch (event.getType()) {
            case "checkout.session.completed" -> checkoutCompleted((Session) dataObject(event));
            case "invoice.paid" -> invoicePaid((Invoice) dataObject(event));
            case "invoice.payment_failed" -> invoicePaymentFailed((Invoice) dataObject(event));
            case "customer.subscription.updated", "customer.subscription.deleted" ->
                    withLicenseOf(((Subscription) dataObject(event)).getId(), (license, subscription) -> sync(license, subscription).mapEmpty());
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
            ret = oneAtATime(session.getSubscription(), () -> currentSubscription(session.getSubscription()).compose(subscription ->
                    licenseRepository.findByCheckoutSessionId(session.getId()).compose(existing -> {
                        Future<Void> fulfilled;
                        // Stripe redelivers an event this server failed, so a license that exists is one whose key or
                        // email failed
                        if (existing != null) {
                            fulfilled = sync(existing, subscription).compose(synced -> finishFulfillment(synced, isPaid(subscription)));
                        } else if (session.getMetadata() != null && session.getMetadata().get(LICENSE_ID_METADATA) != null) {
                            fulfilled = moveFreeLicense(session.getMetadata().get(LICENSE_ID_METADATA), session, subscription);
                        } else {
                            fulfilled = customerRepository.findById(session.getClientReferenceId())
                                                          .compose(customer -> createLicenseFor(customer, session, subscription));
                        }
                        return fulfilled;
                    })));
        }
        return ret;
    }

    private Future<Void> createLicenseFor(Customer customer, Session session, Subscription subscription) {
        Validate.validState(customer != null, "The Checkout Session %s names no customer of this server", session.getId());
        SubscriptionItem item = subscription.getItems().getData().getFirst();
        PriceLookupKey price = PriceLookupKey.parse(item.getPrice().getLookupKey());
        Validate.validState(price != null, "The subscription %s bills the Price %s, which is no paid plan's", subscription.getId(),
                            item.getPrice().getId());
        PaidSubscription paid = new PaidSubscription(price, subscription.getId(), session.getId(),
                                                     Instant.ofEpochSecond(item.getCurrentPeriodEnd()));
        Future<Void> ret;
        if (isPaid(subscription)) {
            ret = licenseService.createLicense(customer, paid)
                                .compose(created -> sync(created.license(), subscription)
                                        .compose(synced -> licenseService.emailNewKey(synced, created.key())));
        } else {
            // a payment that clears later, as a bank debit does, gets its key from invoice.paid
            ret = licenseService.createAwaitingPayment(customer, paid).mapEmpty();
        }
        return ret.onSuccess(v -> log.info("The Checkout Session {} bought a license for customer {}", session.getId(), customer.getId()));
    }

    // The free license the purchase was started from moves onto the subscription, keeping its price list and terms. One
    // that is no longer an active free license by now, as when staff revoked it, leaves the purchase a license of its own.
    private Future<Void> moveFreeLicense(String licenseId, Session session, Subscription subscription) {
        SubscriptionItem item = subscription.getItems().getData().getFirst();
        PriceLookupKey price = PriceLookupKey.parse(item.getPrice().getLookupKey());
        Validate.validState(price != null, "The subscription %s bills the Price %s, which is no paid plan's", subscription.getId(),
                            item.getPrice().getId());
        return licenseRepository.attachSubscription(licenseId, price.plan(), price.period(), subscription.getId(), session.getId(),
                                                    Instant.ofEpochSecond(item.getCurrentPeriodEnd()))
                                .compose(moved -> {
                                    Future<Void> ret;
                                    if (moved != null) {
                                        log.info("The Checkout Session {} moved the free license {} onto a paid plan", session.getId(), licenseId);
                                        ret = sync(moved, subscription).mapEmpty();
                                    } else {
                                        ret = customerRepository.findById(session.getClientReferenceId())
                                                                .compose(customer -> createLicenseFor(customer, session, subscription));
                                    }
                                    return ret;
                                });
    }

    // issues the key a failed first attempt never got to, or emails again the one it issued but never sent; a payment
    // that has not cleared waits for invoice.paid
    private Future<Void> finishFulfillment(License license, boolean paid) {
        return licenseService.keysOf(license.getId()).compose(keys -> {
            Future<Void> ret;
            if (!keys.isEmpty()) {
                ret = licenseService.resendLatestKey(license);
            } else if (paid) {
                ret = licenseService.issueFirstKey(license).compose(issued -> licenseService.emailNewKey(issued.license(), issued.key()));
            } else {
                ret = Future.succeededFuture();
            }
            return ret;
        });
    }

    private Future<Void> invoicePaid(Invoice invoice) {
        Instant paidAt = invoice.getStatusTransitions() != null && invoice.getStatusTransitions().getPaidAt() != null
                ? Instant.ofEpochSecond(invoice.getStatusTransitions().getPaidAt())
                : Instant.now();
        return withLicenseOf(subscriptionOf(invoice), (license, subscription) -> licenseRepository.recordPayment(license.getId(), paidAt)
                .compose(paid -> sync(paid, subscription))
                .compose(synced -> synced.getStatus() == LicenseStatus.ACTIVE
                        ? issueFirstKeyIfNone(synced)
                        : Future.succeededFuture()));
    }

    // the first payment of a checkout paid by bank debit clears here, and so does the license's first key
    private Future<Void> issueFirstKeyIfNone(License license) {
        return licenseService.keysOf(license.getId()).compose(keys -> keys.isEmpty()
                ? licenseService.issueFirstKey(license).compose(issued -> licenseService.emailNewKey(issued.license(), issued.key()))
                : Future.succeededFuture());
    }

    private Future<Void> invoicePaymentFailed(Invoice invoice) {
        return withLicenseOf(subscriptionOf(invoice), (license, subscription) -> sync(license, subscription).compose(synced ->
                // Stripe retries the payment and sends this on each failure; the contacts hear from this server once,
                // when the license leaves good standing
                license.getStatus() == LicenseStatus.ACTIVE && synced.getStatus() == LicenseStatus.PAST_DUE
                        ? licenseService.emailPastDue(synced)
                        : Future.succeededFuture()));
    }

    // applies the action to the subscription's license and the subscription as Stripe has it now
    private Future<Void> withLicenseOf(String subscriptionId, BiFunction<License, Subscription, Future<Void>> action) {
        Future<Void> ret;
        if (subscriptionId == null) {
            log.debug("A Stripe event named no subscription; nothing to apply it to");
            ret = Future.succeededFuture();
        } else {
            ret = oneAtATime(subscriptionId, () -> licenseRepository.findByStripeSubscriptionId(subscriptionId).compose(license -> {
                Future<Void> applied;
                if (license == null) {
                    log.info("No license has the Stripe subscription {}; the event is ignored", subscriptionId);
                    applied = Future.succeededFuture();
                } else {
                    applied = currentSubscription(subscriptionId).compose(subscription -> action.apply(license, subscription));
                }
                return applied;
            }));
        }
        return ret;
    }

    // Stripe delivers events in no set order and several at once, so the work for one subscription, from reading it to
    // writing its license, runs after the work already started for it: a newer read is never written over by an older
    private <T> Future<T> oneAtATime(String subscriptionId, Supplier<Future<T>> work) {
        Promise<T> ret = Promise.promise();
        Future<Void> slot = reconciling.compute(subscriptionId, (id, previous) -> {
            Future<T> run = (previous == null ? Future.<Void>succeededFuture() : previous.<Void>transform(done -> Future.succeededFuture()))
                    .compose(v -> work.get());
            run.onComplete(ret);
            return run.transform(done -> Future.succeededFuture());
        });
        slot.onComplete(done -> reconciling.remove(subscriptionId, slot));
        return ret.future();
    }

    private Future<Subscription> currentSubscription(String subscriptionId) {
        return blocking(() -> stripe().subscriptions().retrieve(subscriptionId, SubscriptionRetrieveParams.builder()
                                                                                                        .addExpand("items.data.price")
                                                                                                        .addExpand("latest_invoice")
                                                                                                        .build()));
    }

    // writes the subscription as Stripe has it onto its license
    private Future<License> sync(License license, Subscription subscription) {
        SubscriptionItem item = subscription.getItems().getData().getFirst();
        Optional<PriceLookupKey> price = Optional.ofNullable(PriceLookupKey.parse(item.getPrice().getLookupKey()));
        Plan plan = price.map(PriceLookupKey::plan).orElse(license.getPlan());
        BillingPeriod period = price.map(PriceLookupKey::period).orElse(license.getBillingPeriod());
        Instant periodEnd = item.getCurrentPeriodEnd() != null ? Instant.ofEpochSecond(item.getCurrentPeriodEnd()) : license.getCurrentPeriodEnd();
        return licenseRepository.syncSubscription(license.getId(), plan, period, statusOf(subscription.getStatus()), periodEnd,
                                                  cancelsAt(subscription, periodEnd));
    }

    // the subscription's latest invoice is paid, by card at Checkout or by a bank debit that has cleared since
    private static boolean isPaid(Subscription subscription) {
        Invoice latest = subscription.getLatestInvoiceObject();
        return latest != null && PAID.equals(latest.getStatus());
    }

    // when the customer has cancelled, the subscription ends at cancel_at, or at the end of the period
    private static Instant cancelsAt(Subscription subscription, Instant periodEnd) {
        Instant ret = null;
        if (subscription.getCancelAt() != null) {
            ret = Instant.ofEpochSecond(subscription.getCancelAt());
        } else if (Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
            ret = periodEnd;
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
            ret = blocking(() -> stripe().customers().create(CustomerCreateParams.builder()
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

    private Future<PriceCatalog> catalog() {
        PriceCatalog current = catalog;
        Future<PriceCatalog> ret;
        if (current != null && current.fetchedAt().plus(PRICE_CACHE_LIFETIME).isAfter(Instant.now())) {
            ret = Future.succeededFuture(current);
        } else {
            ret = blocking(this::readCatalog).onSuccess(read -> catalog = read);
        }
        return ret;
    }

    private PriceCatalog readCatalog() throws Exception {
        Map<PriceLookupKey, Price> prices = new HashMap<>();
        Map<Integer, Integer> pricesPerList = new HashMap<>();
        for (Price price : stripe().prices().list(PriceListParams.builder().setActive(true).setLimit(100L).build()).autoPagingIterable()) {
            PriceLookupKey key = PriceLookupKey.parse(price.getLookupKey());
            if (key != null) {
                prices.put(key, price);
                pricesPerList.merge(key.priceList(), 1, Integer::sum);
            }
        }
        // a lookup key names one Price at a time, so a version with a Price per paid plan and period is complete
        int complete = (Plan.values().length - 1) * BillingPeriod.values().length;
        int current = pricesPerList.entrySet().stream()
                                   .filter(entry -> entry.getValue() == complete)
                                   .mapToInt(Map.Entry::getKey)
                                   .max()
                                   .orElse(0);
        return new PriceCatalog(current, Map.copyOf(prices), Instant.now());
    }

    // the customer's active free license, which a purchase moves onto the subscription; null when it holds none
    private Future<License> freeLicenseOf(Customer customer) {
        return licenseRepository.findFreeLicense(customer.getId())
                                .map(free -> free != null && free.getStatus() == LicenseStatus.ACTIVE ? free : null);
    }

    // the price list a purchase buys at: the one the free license keeps, else the current one
    private static int priceListFor(License free, PriceCatalog prices) {
        int ret = free != null ? free.getPriceList() : prices.currentPriceList();
        Validate.validState(ret > 0, "Stripe has no price list with an active Price for every paid plan and billing period");
        return ret;
    }

    private static void validate(CheckoutRequest request) {
        Validate.isTrue(request != null, "The purchase is required");
        Validate.isTrue(request.getPlan() != null && request.getPlan() != Plan.FREE, "A paid plan is required; the free plan is requested, not bought");
        Validate.isTrue(request.getBillingPeriod() != null, "The billing period is required");
        Plan matching = Plan.matching(request.getFigures());
        Validate.isTrue(matching == request.getPlan(), "Your company's figures fit the %s plan, not %s", matching, request.getPlan());
    }

    private V1Services stripe() {
        StripeClient ret = stripeClient.getIfAvailable();
        Validate.validState(ret != null, "kinotic.licenses.stripe.secretKey is not set");
        return ret.v1();
    }

    // stripe-java blocks on its HTTP calls, so every call runs on a worker thread
    private <T> Future<T> blocking(java.util.concurrent.Callable<T> call) {
        return vertx.executeBlocking(call);
    }
}
