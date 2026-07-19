package com.sbp.webhook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "stripe_event_id", nullable = false, unique = true)
    private String stripeEventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookEventStatus status = WebhookEventStatus.RECEIVED;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    protected WebhookEvent() {}

    public WebhookEvent(String stripeEventId, String eventType, String payload) {
        this.stripeEventId = stripeEventId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public String getStripeEventId() { return stripeEventId; }
    public String getEventType() { return eventType; }
    public WebhookEventStatus getStatus() { return status; }
    public String getPayload() { return payload; }

    public void markProcessed() {
        this.status = WebhookEventStatus.PROCESSED;
        this.processedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = WebhookEventStatus.FAILED;
        this.errorMessage = error;
    }
}
