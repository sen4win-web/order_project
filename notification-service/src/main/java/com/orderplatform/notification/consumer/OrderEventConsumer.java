package com.orderplatform.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public OrderEventConsumer(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order-events", groupId = "notification-group")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);

        log.info("Received order event from Kafka partition={} offset={} key={}",
                record.partition(), record.offset(), record.key());

        try {
            OrderEventPayload payload = objectMapper.readValue(record.value(), OrderEventPayload.class);

            OrderNotification notification = new OrderNotification(
                    payload.orderId(),
                    payload.status()
            );

            // Push to all WebSocket subscribers on /topic/orders
            messagingTemplate.convertAndSend("/topic/orders", notification);
            log.info("Pushed notification via WebSocket orderId={} status={}", payload.orderId(), payload.status());

            acknowledgment.acknowledge();

        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize order event: {}", e.getMessage());
            // Acknowledge to avoid poison pill — in production, send to DLQ
            acknowledgment.acknowledge();
        } finally {
            MDC.remove("correlationId");
        }
    }
}
