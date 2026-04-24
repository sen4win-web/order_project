package com.orderplatform.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderplatform.order.controller.dto.CreateOrderRequest;
import com.orderplatform.order.controller.dto.OrderResponse;
import com.orderplatform.order.entity.Order;
import com.orderplatform.order.entity.OrderStatus;
import com.orderplatform.order.kafka.OrderEventPayload;
import com.orderplatform.order.outbox.OutboxEvent;
import com.orderplatform.order.outbox.OutboxEventRepository;
import com.orderplatform.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository orderRepository,
                        OutboxEventRepository outboxEventRepository,
                        ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates an order and inserts an outbox event in the same transaction.
     * This is the Outbox Pattern — guaranteeing that the event will eventually
     * be published to Kafka even if the broker is temporarily unavailable.
     */
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);

        // Idempotency check
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                Order existingOrder = existing.get();
                log.info("Duplicate order detected for idempotencyKey={}", request.idempotencyKey());
                return new OrderResponse(
                        existingOrder.getId().toString(),
                        existingOrder.getStatus().name(),
                        existingOrder.getCreatedAt()
                );
            }
        }

        // Create order
        Order order = new Order();
        order.setCustomerId(request.customerId());
        order.setProductId(request.productId());
        order.setQuantity(request.quantity());
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey(request.idempotencyKey());

        Order savedOrder = orderRepository.save(order);
        log.info("Order created orderId={} customerId={}", savedOrder.getId(), savedOrder.getCustomerId());

        // Create outbox event in the same transaction
        try {
            OrderEventPayload eventPayload = new OrderEventPayload(
                    savedOrder.getId().toString(),
                    savedOrder.getCustomerId(),
                    savedOrder.getProductId(),
                    savedOrder.getQuantity(),
                    savedOrder.getStatus().name()
            );

            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.setEventType("ORDER_CREATED");
            outboxEvent.setAggregateId(savedOrder.getId().toString());
            outboxEvent.setPayload(objectMapper.writeValueAsString(eventPayload));

            outboxEventRepository.save(outboxEvent);
            log.info("Outbox event created for orderId={}", savedOrder.getId());

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order event payload for orderId={}", savedOrder.getId(), e);
            throw new RuntimeException("Failed to create outbox event", e);
        }

        MDC.remove("correlationId");

        return new OrderResponse(
                savedOrder.getId().toString(),
                savedOrder.getStatus().name(),
                savedOrder.getCreatedAt()
        );
    }

    public OrderResponse getOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

        return new OrderResponse(
                order.getId().toString(),
                order.getStatus().name(),
                order.getCreatedAt()
        );
    }
}
