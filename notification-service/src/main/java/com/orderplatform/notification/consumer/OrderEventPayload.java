package com.orderplatform.notification.consumer;

public record OrderEventPayload(
        String orderId,
        String customerId,
        String productId,
        int quantity,
        String status
) {
}
