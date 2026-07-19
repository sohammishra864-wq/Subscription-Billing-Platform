package com.sbp.dunning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DunningScheduler {

    private static final Logger log = LoggerFactory.getLogger(DunningScheduler.class);

    private final DunningAttemptRepository dunningAttemptRepository;
    private final DunningService dunningService;

    public DunningScheduler(DunningAttemptRepository dunningAttemptRepository, DunningService dunningService) {
        this.dunningAttemptRepository = dunningAttemptRepository;
        this.dunningService = dunningService;
    }

    @Scheduled(fixedDelayString = "${app.jobs.dunning-poll-interval:900000}")
    public void pollAndExecute() {
        var dueAttempts = dunningAttemptRepository.findByStatusAndScheduledAtBefore("PENDING", Instant.now());
        for (var attempt : dueAttempts) {
            try {
                dunningService.executeAttempt(attempt);
            } catch (Exception e) {
                log.error("Error executing dunning attempt {}: {}", attempt.getId(), e.getMessage());
            }
        }
    }
}
