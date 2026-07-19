package com.sbp.subscription;

import com.sbp.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class SubscriptionStateMachine {

    private static final Map<SubscriptionStatus, Set<SubscriptionStatus>> VALID_TRANSITIONS = Map.of(
            SubscriptionStatus.INCOMPLETE, Set.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELED),
            SubscriptionStatus.ACTIVE, Set.of(SubscriptionStatus.PAST_DUE, SubscriptionStatus.CANCELED),
            SubscriptionStatus.PAST_DUE, Set.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.UNPAID, SubscriptionStatus.CANCELED),
            SubscriptionStatus.UNPAID, Set.of(SubscriptionStatus.CANCELED)
    );

    public void transition(Subscription subscription, SubscriptionStatus target) {
        var current = subscription.getStatus();
        if (current == target) return; // idempotent no-op

        var allowed = VALID_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new BadRequestException(
                    "Invalid subscription transition: " + current + " -> " + target);
        }
        subscription.setStatus(target);
    }
}
