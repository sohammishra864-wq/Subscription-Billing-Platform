package com.sbp.billing.invoice;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "stripe_invoice_id", unique = true)
    private String stripeInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "amount_due_cents", nullable = false)
    private long amountDueCents;

    @Column(name = "amount_paid_cents", nullable = false)
    private long amountPaidCents;

    @Column(nullable = false)
    private String currency = "USD";

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected Invoice() {}

    public Invoice(UUID subscriptionId, String stripeInvoiceId) {
        this.subscriptionId = subscriptionId;
        this.stripeInvoiceId = stripeInvoiceId;
    }

    public UUID getId() { return id; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public String getStripeInvoiceId() { return stripeInvoiceId; }
    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }
    public long getAmountDueCents() { return amountDueCents; }
    public void setAmountDueCents(long amountDueCents) { this.amountDueCents = amountDueCents; }
    public long getAmountPaidCents() { return amountPaidCents; }
    public void setAmountPaidCents(long amountPaidCents) { this.amountPaidCents = amountPaidCents; }
    public String getCurrency() { return currency; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant periodStart) { this.periodStart = periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant periodEnd) { this.periodEnd = periodEnd; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
}
