package com.sbp.dunning;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dunning_attempts")
public class DunningAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    protected DunningAttempt() {}

    public DunningAttempt(UUID subscriptionId, UUID invoiceId, int attemptNumber, Instant scheduledAt) {
        this.subscriptionId = subscriptionId;
        this.invoiceId = invoiceId;
        this.attemptNumber = attemptNumber;
        this.scheduledAt = scheduledAt;
    }

    public UUID getId() { return id; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public UUID getInvoiceId() { return invoiceId; }
    public int getAttemptNumber() { return attemptNumber; }
    public String getStatus() { return status; }
    public Instant getScheduledAt() { return scheduledAt; }

    public void markSucceeded() { this.status = "SUCCEEDED"; this.executedAt = Instant.now(); }
    public void markFailed() { this.status = "FAILED"; this.executedAt = Instant.now(); }
    public void markSkipped() { this.status = "SKIPPED"; this.executedAt = Instant.now(); }
    public void cancel() { this.status = "CANCELED"; }
}
