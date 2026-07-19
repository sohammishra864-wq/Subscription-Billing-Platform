package com.sbp.webhook;

import com.sbp.webhook.handlers.WebhookEventHandler;
import com.stripe.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private final Map<String, WebhookEventHandler> handlers;

    public WebhookDispatcher(List<WebhookEventHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(WebhookEventHandler::getEventType, Function.identity()));
    }

    public void dispatch(Event event) {
        var handler = handlers.get(event.getType());
        if (handler == null) {
            log.info("No handler for event type {}, skipping", event.getType());
            return;
        }
        handler.handle(event);
    }
}
