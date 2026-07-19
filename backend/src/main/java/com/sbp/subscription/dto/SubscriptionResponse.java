package com.sbp.subscription.dto;

import com.sbp.subscription.Subscription;
import com.sbp.subscription.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionResponse(
        UUID id,
        UUID planId,
        SubscriptionStatus status,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant createdAt
) {
    public static SubscriptionResponse from(Subscription s) {
        return new SubscriptionResponse(s.getId(), s.getPlanId(), s.getStatus(),
                s.getCurrentPeriodStart(), s.getCurrentPeriodEnd(),
                s.isCancelAtPeriodEnd(), s.getCreatedAt());
    }
}
