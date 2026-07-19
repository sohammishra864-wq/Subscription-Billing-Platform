package com.sbp.webhook.handlers;

import com.sbp.subscription.SubscriptionRepository;
import com.sbp.subscription.SubscriptionStateMachine;
import com.sbp.subscription.SubscriptionStatus;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class CustomerSubscriptionDeletedHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerSubscriptionDeletedHandler.class);
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionStateMachine stateMachine;

    public CustomerSubscriptionDeletedHandler(SubscriptionRepository subscriptionRepository,
                                              SubscriptionStateMachine stateMachine) {
        this.subscriptionRepository = subscriptionRepository;
        this.stateMachine = stateMachine;
    }

    @Override
    public String getEventType() { return "customer.subscription.deleted"; }

    @Override
    @Transactional
    public void handle(Event event) {
        var stripeSub = (Subscription) event.getDataObjectDeserializer().getObject().orElse(null);
        if (stripeSub == null) return;

        var localSub = subscriptionRepository.findByStripeSubscriptionId(stripeSub.getId()).orElse(null);
        if (localSub == null) {
            log.warn("Received delete for unknown subscription {}", stripeSub.getId());
            return;
        }

        stateMachine.transition(localSub, SubscriptionStatus.CANCELED);
        localSub.setCanceledAt(Instant.now());
        subscriptionRepository.save(localSub);
    }
}
