package com.orderplatform.order.controller.dto;

import java.time.Instant;

public record OrderResponse(
        String orderId,
        String status,
        Instant createdAt
) {
}
