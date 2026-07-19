package com.sbp.billing.invoice;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    Optional<Invoice> findByStripeInvoiceId(String stripeInvoiceId);
    Page<Invoice> findBySubscriptionId(UUID subscriptionId, Pageable pageable);
}
