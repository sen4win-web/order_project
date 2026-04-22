package com.orderplatform.notification.consumer;

/**
 * Simplified notification payload sent to WebSocket clients.
 */
public record OrderNotification(
        String orderId,
        String status
) {
}
