package com.orderplatform.order.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String DEFAULT_TOPIC = "order-events";

    @Value("${app.kafka.topic:" + DEFAULT_TOPIC + "}")
    private String topicName;

    @Value("${app.kafka.partitions:3}")
    private int partitions;

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(topicName)
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
