package com.sbp.scheduling;

import com.sbp.idempotency.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanupJob.class);
    private final IdempotencyService idempotencyService;

    public IdempotencyKeyCleanupJob(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Scheduled(cron = "${app.jobs.idempotency-cleanup-cron:0 0 3 * * *}")
    public void cleanup() {
        int deleted = idempotencyService.deleteExpired();
        if (deleted > 0) {
            log.info("Cleaned up {} expired idempotency keys", deleted);
        }
    }
}
