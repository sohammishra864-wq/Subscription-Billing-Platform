package com.sbp.webhook.handlers;

import com.sbp.subscription.SubscriptionRepository;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class CustomerSubscriptionUpdatedHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerSubscriptionUpdatedHandler.class);
    private final SubscriptionRepository subscriptionRepository;

    public CustomerSubscriptionUpdatedHandler(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    @Override
    public String getEventType() { return "customer.subscription.updated"; }

    @Override
    @Transactional
    public void handle(Event event) {
        var stripeSub = (Subscription) event.getDataObjectDeserializer().getObject().orElse(null);
        if (stripeSub == null) return;

        var localSub = subscriptionRepository.findByStripeSubscriptionId(stripeSub.getId()).orElse(null);
        if (localSub == null) {
            log.warn("Received update for unknown subscription {}", stripeSub.getId());
            return;
        }

        Instant eventTime = Instant.ofEpochSecond(event.getCreated());
        if (localSub.getLastSyncedFromStripeAt() != null && !eventTime.isAfter(localSub.getLastSyncedFromStripeAt())) {
            log.info("Ignoring stale webhook for subscription {}", localSub.getId());
            return;
        }

        if (stripeSub.getCurrentPeriodStart() != null) {
            localSub.setCurrentPeriodStart(Instant.ofEpochSecond(stripeSub.getCurrentPeriodStart()));
        }
        if (stripeSub.getCurrentPeriodEnd() != null) {
            localSub.setCurrentPeriodEnd(Instant.ofEpochSecond(stripeSub.getCurrentPeriodEnd()));
        }
        localSub.setLastSyncedFromStripeAt(eventTime);
        subscriptionRepository.save(localSub);
    }
}
