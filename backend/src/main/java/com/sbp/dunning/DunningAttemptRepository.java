package com.sbp.dunning;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DunningAttemptRepository extends JpaRepository<DunningAttempt, UUID> {

    List<DunningAttempt> findByStatusAndScheduledAtBefore(String status, Instant now);

    @Modifying
    @Query("UPDATE DunningAttempt d SET d.status = 'CANCELED' WHERE d.invoiceId = :invoiceId AND d.status = 'PENDING'")
    int cancelRemaining(UUID invoiceId);

    long countBySubscriptionIdAndStatus(UUID subscriptionId, String status);
}
