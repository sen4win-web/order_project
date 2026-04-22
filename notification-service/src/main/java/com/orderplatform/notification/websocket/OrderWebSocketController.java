package com.orderplatform.notification.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/**
 * Optional controller for handling inbound WebSocket messages.
 * Clients can send messages to /app/subscribe and receive a confirmation.
 */
@Controller
public class OrderWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(OrderWebSocketController.class);

    @MessageMapping("/subscribe")
    @SendTo("/topic/orders")
    public String subscribe(String message) {
        log.info("Client subscribed to order updates: {}", message);
        return "{\"message\": \"Subscribed to order updates\"}";
    }
}
