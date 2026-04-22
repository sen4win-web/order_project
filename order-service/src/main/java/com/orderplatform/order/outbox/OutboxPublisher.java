package com.orderplatform.order.outbox;

import com.orderplatform.order.kafka.KafkaProducerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Background job that polls the outbox table for PENDING events
 * and publishes them to Kafka. This ensures transactional consistency
 * between the database and the message broker (Outbox Pattern).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaProducerService kafkaProducerService;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaProducerService kafkaProducerService) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaProducerService = kafkaProducerService;
    }

    @Scheduled(fixedDelayString = "${outbox.polling.interval:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents =
                outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events to publish", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaProducerService.send(event.getAggregateId(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to publish outbox event id={} error={}",
                                        event.getId(), ex.getMessage());
                            }
                        })
                        .get(); // Block to ensure ordering

                event.setStatus(OutboxEventStatus.SENT);
                outboxEventRepository.save(event);
                log.info("Published outbox event id={} aggregateId={}", event.getId(), event.getAggregateId());

            } catch (Exception e) {
                log.error("Error publishing outbox event id={}: {}", event.getId(), e.getMessage());
                event.setStatus(OutboxEventStatus.FAILED);
                outboxEventRepository.save(event);
            }
        }
    }
}
