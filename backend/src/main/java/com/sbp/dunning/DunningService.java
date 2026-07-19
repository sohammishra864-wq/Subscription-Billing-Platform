package com.sbp.dunning;

import com.sbp.billing.invoice.Invoice;
import com.sbp.billing.invoice.InvoiceRepository;
import com.sbp.notification.NotificationSender;
import com.sbp.notification.NotificationTemplate;
import com.sbp.payment.StripePaymentGateway;
import com.sbp.subscription.Subscription;
import com.sbp.subscription.SubscriptionRepository;
import com.sbp.subscription.SubscriptionStateMachine;
import com.sbp.subscription.SubscriptionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class DunningService {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    private final DunningAttemptRepository dunningAttemptRepository;
    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionStateMachine stateMachine;
    private final StripePaymentGateway stripeGateway;
    private final NotificationSender notificationSender;
    private final DunningPolicy dunningPolicy;

    public DunningService(DunningAttemptRepository dunningAttemptRepository,
                          InvoiceRepository invoiceRepository,
                          SubscriptionRepository subscriptionRepository,
                          SubscriptionStateMachine stateMachine,
                          StripePaymentGateway stripeGateway,
                          NotificationSender notificationSender,
                          DunningPolicy dunningPolicy) {
        this.dunningAttemptRepository = dunningAttemptRepository;
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.stateMachine = stateMachine;
        this.stripeGateway = stripeGateway;
        this.notificationSender = notificationSender;
        this.dunningPolicy = dunningPolicy;
    }

    @Transactional
    public void scheduleRetries(UUID subscriptionId, UUID invoiceId, Instant failureTime) {
        var offsets = dunningPolicy.getRetryOffsets();
        for (int i = 0; i < offsets.size(); i++) {
            var attempt = new DunningAttempt(subscriptionId, invoiceId, i + 1,
                    failureTime.plus(offsets.get(i)));
            dunningAttemptRepository.save(attempt);
        }
        log.info("Scheduled {} dunning retries for subscription {}", offsets.size(), subscriptionId);
    }

    @Transactional
    public void executeAttempt(DunningAttempt attempt) {
        var invoice = invoiceRepository.findById(attempt.getInvoiceId()).orElse(null);
        if (invoice == null || "PAID".equals(invoice.getStatus().name())) {
            attempt.markSkipped();
            dunningAttemptRepository.save(attempt);
            return;
        }

        var subscription = subscriptionRepository.findById(attempt.getSubscriptionId()).orElse(null);
        if (subscription == null || subscription.getStatus() == SubscriptionStatus.CANCELED) {
            attempt.markSkipped();
            dunningAttemptRepository.save(attempt);
            return;
        }

        var result = stripeGateway.payInvoice(invoice.getStripeInvoiceId());

        if ("paid".equals(result.status())) {
            attempt.markSucceeded();
            stateMachine.transition(subscription, SubscriptionStatus.ACTIVE);
            subscriptionRepository.save(subscription);
            dunningAttemptRepository.cancelRemaining(invoice.getId());
            notificationSender.send(subscription.getCustomerId(), NotificationTemplate.PAYMENT_RECOVERED, Map.of());
            log.info("Dunning attempt {} succeeded for subscription {}", attempt.getAttemptNumber(), subscription.getId());
        } else {
            attempt.markFailed();
            boolean isLast = attempt.getAttemptNumber() == dunningPolicy.getMaxAttempts();
            if (isLast) {
                stateMachine.transition(subscription, SubscriptionStatus.UNPAID);
                subscriptionRepository.save(subscription);
                notificationSender.send(subscription.getCustomerId(), NotificationTemplate.FINAL_NOTICE, Map.of());
                log.warn("Dunning exhausted for subscription {}", subscription.getId());
            } else {
                notificationSender.send(subscription.getCustomerId(), NotificationTemplate.RETRY_FAILED,
                        Map.of("attemptNumber", attempt.getAttemptNumber()));
            }
        }
        dunningAttemptRepository.save(attempt);
    }
}
