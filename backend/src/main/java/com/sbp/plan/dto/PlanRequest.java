package com.sbp.plan.dto;

import com.sbp.plan.BillingInterval;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PlanRequest(
        @NotBlank String name,
        @NotBlank String stripePriceId,
        @NotNull @Positive Long priceCents,
        String currency,
        @NotNull BillingInterval billingInterval
) {}
