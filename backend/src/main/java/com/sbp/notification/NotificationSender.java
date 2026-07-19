package com.sbp.notification;

import java.util.Map;
import java.util.UUID;

public interface NotificationSender {
    void send(UUID customerId, NotificationTemplate template, Map<String, Object> context);
}
