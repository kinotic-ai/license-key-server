package org.kinotic.licenses.internal.api.model;

import org.kinotic.licenses.api.model.Plan;
import org.kinotic.licenses.api.model.PlanInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The plans as read from Stripe at {@code fetchedAt}: what the site shows, and the Stripe Price each paid plan is
 * bought with.
 */
public record PlanCatalog(List<PlanInfo> plans, Map<Plan, String> priceIds, Instant fetchedAt) {
}
