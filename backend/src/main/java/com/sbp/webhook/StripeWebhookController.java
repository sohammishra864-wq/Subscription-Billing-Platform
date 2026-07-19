package com.sbp.webhook;

import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookDispatcher dispatcher;

    @Value("${app.stripe.webhook-secret}")
    private String webhookSecret;

    public StripeWebhookController(WebhookEventRepository webhookEventRepository, WebhookDispatcher dispatcher) {
        this.webhookEventRepository = webhookEventRepository;
        this.dispatcher = dispatcher;
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (Exception e) {
            log.warn("Webhook signature verification failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        // Persist-before-process
        var existing = webhookEventRepository.findByStripeEventId(event.getId());
        if (existing.isPresent()) {
            if (existing.get().getStatus() == WebhookEventStatus.PROCESSED) {
                return ResponseEntity.ok("Already processed");
            }
            // RECEIVED or FAILED — reprocess
        } else {
            var webhookEvent = new WebhookEvent(event.getId(), event.getType(), payload);
            try {
                webhookEventRepository.saveAndFlush(webhookEvent);
            } catch (DataIntegrityViolationException e) {
                // Concurrent insert race — safe to proceed
            }
        }

        try {
            dispatcher.dispatch(event);
            webhookEventRepository.findByStripeEventId(event.getId()).ifPresent(we -> {
                we.markProcessed();
                webhookEventRepository.save(we);
            });
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Webhook handler failed for event {}: {}", event.getId(), e.getMessage());
            webhookEventRepository.findByStripeEventId(event.getId()).ifPresent(we -> {
                we.markFailed(e.getMessage());
                webhookEventRepository.save(we);
            });
            return ResponseEntity.internalServerError().body("Handler error");
        }
    }
}
