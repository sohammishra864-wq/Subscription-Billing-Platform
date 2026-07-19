package com.sbp.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(UUID customerId, NotificationTemplate template, Map<String, Object> context) {
        log.info("[NOTIFICATION] customer={} template={} context={}", customerId, template, context);
    }
}
