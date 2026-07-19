package com.sbp.webhook.handlers;

import com.sbp.subscription.SubscriptionRepository;
import com.sbp.subscription.SubscriptionStateMachine;
import com.sbp.subscription.SubscriptionStatus;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class CheckoutSessionCompletedHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSessionCompletedHandler.class);
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionStateMachine stateMachine;

    public CheckoutSessionCompletedHandler(SubscriptionRepository subscriptionRepository,
                                           SubscriptionStateMachine stateMachine) {
        this.subscriptionRepository = subscriptionRepository;
        this.stateMachine = stateMachine;
    }

    @Override
    public String getEventType() { return "checkout.session.completed"; }

    @Override
    @Transactional
    public void handle(Event event) {
        var session = (Session) event.getDataObjectDeserializer().getObject().orElse(null);
        if (session == null) {
            log.warn("Could not deserialize checkout session from event {}", event.getId());
            return;
        }

        String clientRefId = session.getClientReferenceId();
        if (clientRefId == null) {
            log.warn("No client_reference_id in checkout session {}", session.getId());
            return;
        }

        var subscription = subscriptionRepository.findById(UUID.fromString(clientRefId)).orElse(null);
        if (subscription == null) {
            log.warn("No local subscription found for client_reference_id {}", clientRefId);
            return;
        }

        stateMachine.transition(subscription, SubscriptionStatus.ACTIVE);
        subscription.setStripeSubscriptionId(session.getSubscription());
        subscriptionRepository.save(subscription);
        log.info("Subscription {} activated via checkout session {}", subscription.getId(), session.getId());
    }
}
