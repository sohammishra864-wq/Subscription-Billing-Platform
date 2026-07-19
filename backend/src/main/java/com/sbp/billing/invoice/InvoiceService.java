package com.sbp.billing.invoice;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;

    public InvoiceService(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional
    public Invoice syncFromStripe(UUID subscriptionId, String stripeInvoiceId,
                                  String status, long amountDue, long amountPaid,
                                  Long periodStart, Long periodEnd) {
        var invoice = invoiceRepository.findByStripeInvoiceId(stripeInvoiceId)
                .orElseGet(() -> new Invoice(subscriptionId, stripeInvoiceId));

        invoice.setStatus(mapStatus(status));
        invoice.setAmountDueCents(amountDue);
        invoice.setAmountPaidCents(amountPaid);
        if (periodStart != null) invoice.setPeriodStart(Instant.ofEpochSecond(periodStart));
        if (periodEnd != null) invoice.setPeriodEnd(Instant.ofEpochSecond(periodEnd));
        if ("paid".equals(status)) invoice.setPaidAt(Instant.now());

        return invoiceRepository.save(invoice);
    }

    private InvoiceStatus mapStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "draft" -> InvoiceStatus.DRAFT;
            case "open" -> InvoiceStatus.OPEN;
            case "paid" -> InvoiceStatus.PAID;
            case "uncollectible" -> InvoiceStatus.UNCOLLECTIBLE;
            case "void" -> InvoiceStatus.VOID;
            default -> InvoiceStatus.OPEN;
        };
    }
}
