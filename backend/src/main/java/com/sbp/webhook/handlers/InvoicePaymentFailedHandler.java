package com.sbp.webhook.handlers;

import com.sbp.billing.invoice.InvoiceService;
import com.sbp.dunning.DunningService;
import com.sbp.notification.NotificationSender;
import com.sbp.notification.NotificationTemplate;
import com.sbp.subscription.SubscriptionRepository;
import com.sbp.subscription.SubscriptionStateMachine;
import com.sbp.subscription.SubscriptionStatus;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Component
public class InvoicePaymentFailedHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InvoicePaymentFailedHandler.class);
    private final InvoiceService invoiceService;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionStateMachine stateMachine;
    private final DunningService dunningService;
    private final NotificationSender notificationSender;

    public InvoicePaymentFailedHandler(InvoiceService invoiceService,
                                       SubscriptionRepository subscriptionRepository,
                                       SubscriptionStateMachine stateMachine,
                                       DunningService dunningService,
                                       NotificationSender notificationSender) {
        this.invoiceService = invoiceService;
        this.subscriptionRepository = subscriptionRepository;
        this.stateMachine = stateMachine;
        this.dunningService = dunningService;
        this.notificationSender = notificationSender;
    }

    @Override
    public String getEventType() { return "invoice.payment_failed"; }

    @Override
    @Transactional
    public void handle(Event event) {
        var stripeInvoice = (Invoice) event.getDataObjectDeserializer().getObject().orElse(null);
        if (stripeInvoice == null) return;

        var subscription = subscriptionRepository.findByStripeSubscriptionId(stripeInvoice.getSubscription())
                .orElse(null);
        if (subscription == null) {
            log.warn("Invoice payment failed for unknown subscription {}", stripeInvoice.getSubscription());
            return;
        }

        var invoice = invoiceService.syncFromStripe(subscription.getId(), stripeInvoice.getId(),
                "open", stripeInvoice.getAmountDue(), 0L,
                stripeInvoice.getPeriodStart(), stripeInvoice.getPeriodEnd());

        // Only schedule dunning on first failure (ACTIVE -> PAST_DUE)
        if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
            stateMachine.transition(subscription, SubscriptionStatus.PAST_DUE);
            subscriptionRepository.save(subscription);
            dunningService.scheduleRetries(subscription.getId(), invoice.getId(), Instant.now());
            notificationSender.send(subscription.getCustomerId(), NotificationTemplate.PAYMENT_FAILED, Map.of());
            log.info("Dunning started for subscription {}", subscription.getId());
        }
    }
}
