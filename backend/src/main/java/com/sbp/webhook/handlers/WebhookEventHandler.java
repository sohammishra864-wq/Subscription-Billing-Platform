package com.sbp.webhook.handlers;

import com.stripe.model.Event;

public interface WebhookEventHandler {
    void handle(Event event);
    String getEventType();
}
