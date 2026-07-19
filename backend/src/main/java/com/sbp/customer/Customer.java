package com.sbp.customer;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "stripe_customer_id", nullable = false, unique = true)
    private String stripeCustomerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Customer() {}

    public Customer(UUID userId, String stripeCustomerId) {
        this.userId = userId;
        this.stripeCustomerId = stripeCustomerId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getStripeCustomerId() { return stripeCustomerId; }
}
