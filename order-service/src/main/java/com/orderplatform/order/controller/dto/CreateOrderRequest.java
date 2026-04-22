package com.orderplatform.order.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(

        @NotBlank(message = "customerId is required")
        String customerId,

        @NotBlank(message = "productId is required")
        String productId,

        @Min(value = 1, message = "quantity must be at least 1")
        int quantity,

        String idempotencyKey
) {
}
