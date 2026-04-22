package com.orderplatform.order.kafka;

public record OrderEventPayload(
        String orderId,
        String customerId,
        String productId,
        int quantity,
        String status
) {
}
