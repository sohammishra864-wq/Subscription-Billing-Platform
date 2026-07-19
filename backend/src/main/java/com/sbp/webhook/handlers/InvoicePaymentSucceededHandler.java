package com.sbp.webhook.handlers;

import com.sbp.billing.invoice.InvoiceService;
import com.sbp.dunning.DunningAttemptRepository;
import com.sbp.subscription.SubscriptionRepository;
import com.sbp.subscription.SubscriptionStateMachine;
import com.sbp.subscription.SubscriptionStatus;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InvoicePaymentSucceededHandler implements WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InvoicePaymentSucceededHandler.class);
    private final InvoiceService invoiceService;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionStateMachine stateMachine;
    private final DunningAttemptRepository dunningAttemptRepository;

    public InvoicePaymentSucceededHandler(InvoiceService invoiceService,
                                          SubscriptionRepository subscriptionRepository,
                                          SubscriptionStateMachine stateMachine,
                                          DunningAttemptRepository dunningAttemptRepository) {
        this.invoiceService = invoiceService;
        this.subscriptionRepository = subscriptionRepository;
        this.stateMachine = stateMachine;
        this.dunningAttemptRepository = dunningAttemptRepository;
    }

    @Override
    public String getEventType() { return "invoice.payment_succeeded"; }

    @Override
    @Transactional
    public void handle(Event event) {
        var stripeInvoice = (Invoice) event.getDataObjectDeserializer().getObject().orElse(null);
        if (stripeInvoice == null) return;

        var subscription = subscriptionRepository.findByStripeSubscriptionId(stripeInvoice.getSubscription())
                .orElse(null);
        if (subscription == null) {
            log.warn("Invoice payment succeeded for unknown subscription {}", stripeInvoice.getSubscription());
            return;
        }

        var invoice = invoiceService.syncFromStripe(subscription.getId(), stripeInvoice.getId(),
                "paid", stripeInvoice.getAmountDue(), stripeInvoice.getAmountPaid(),
                stripeInvoice.getPeriodStart(), stripeInvoice.getPeriodEnd());

        // Recovery path: if subscription was past due/unpaid, restore it
        if (subscription.getStatus() == SubscriptionStatus.PAST_DUE ||
            subscription.getStatus() == SubscriptionStatus.UNPAID) {
            stateMachine.transition(subscription, SubscriptionStatus.ACTIVE);
            subscriptionRepository.save(subscription);
            dunningAttemptRepository.cancelRemaining(invoice.getId());
            log.info("Subscription {} recovered via payment success webhook", subscription.getId());
        }
    }
}
