package com.orderplatform.order.outbox;

import com.orderplatform.order.kafka.KafkaProducerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Background job that polls the outbox table for PENDING events
 * and publishes them to Kafka. Ensures transactional consistency
 * between the database and the message broker (Outbox Pattern).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaProducerService kafkaProducerService;
    private final int batchSize;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaProducerService kafkaProducerService,
                           @Value("${outbox.batch-size:50}") int batchSize) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaProducerService = kafkaProducerService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.polling.interval:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository
                .findByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING, PageRequest.of(0, batchSize));

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events to publish", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaProducerService.send(event.getAggregateId(), event.getPayload())
                        .get(); // Block to ensure ordering

                event.setStatus(OutboxEventStatus.SENT);
                outboxEventRepository.save(event);
                log.info("Published outbox event id={} aggregateId={}", event.getId(), event.getAggregateId());

            } catch (Exception e) {
                log.error("Error publishing outbox event id={}: {}", event.getId(), e.getMessage());
                event.setStatus(OutboxEventStatus.FAILED);
                outboxEventRepository.save(event);
                // Stop processing this batch — don't skip events to preserve ordering
                break;
            }
        }
    }
}
