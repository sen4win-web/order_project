package com.orderplatform.order.kafka;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class KafkaProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaProducerService(KafkaTemplate<String, String> kafkaTemplate,
                                @Value("${app.kafka.topic:" + KafkaConfig.DEFAULT_TOPIC + "}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @CircuitBreaker(name = "kafkaProducer", fallbackMethod = "sendFallback")
    public CompletableFuture<SendResult<String, String>> send(String key, String payload) {
        log.info("Publishing event to Kafka topic={} key={}", topic, key);
        return kafkaTemplate.send(topic, key, payload);
    }

    @SuppressWarnings("unused")
    private CompletableFuture<SendResult<String, String>> sendFallback(String key, String payload, Throwable t) {
        log.error("Circuit breaker open for Kafka producer. Event will remain in outbox. key={} error={}",
                key, t.getMessage());
        return CompletableFuture.failedFuture(t);
    }
}
