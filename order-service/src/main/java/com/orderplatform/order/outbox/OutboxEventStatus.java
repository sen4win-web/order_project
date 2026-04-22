package com.orderplatform.order.outbox;

public enum OutboxEventStatus {
    PENDING,
    SENT,
    FAILED
}
