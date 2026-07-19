package com.sbp.dunning;

import com.sbp.subscription.Subscription;
import com.sbp.subscription.SubscriptionStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AccessRestrictionService {

    private final DunningPolicy dunningPolicy;

    public AccessRestrictionService(DunningPolicy dunningPolicy) {
        this.dunningPolicy = dunningPolicy;
    }

    public boolean isAccessAllowed(Subscription subscription) {
        if (subscription.getStatus() == SubscriptionStatus.ACTIVE) return true;
        if (subscription.getStatus() == SubscriptionStatus.PAST_DUE) {
            // Grace period from when it went past due (approximated by updatedAt)
            Instant graceEnd = subscription.getCurrentPeriodEnd() != null
                    ? subscription.getCurrentPeriodEnd().plus(dunningPolicy.getGracePeriod())
                    : Instant.now();
            return Instant.now().isBefore(graceEnd);
        }
        return false;
    }
}
