package com.sbp.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    @Query("SELECT s FROM Subscription s WHERE s.customerId = :customerId AND s.status NOT IN ('CANCELED', 'EXPIRED')")
    Optional<Subscription> findActiveByCustomerId(UUID customerId);

    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);
}
