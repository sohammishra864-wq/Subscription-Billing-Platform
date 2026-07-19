package com.sbp.plan.dto;

import com.sbp.plan.BillingInterval;
import com.sbp.plan.Plan;

import java.util.UUID;

public record PlanResponse(
        UUID id,
        String name,
        long priceCents,
        String currency,
        BillingInterval billingInterval
) {
    public static PlanResponse from(Plan plan) {
        return new PlanResponse(plan.getId(), plan.getName(), plan.getPriceCents(),
                plan.getCurrency(), plan.getBillingInterval());
    }
}
