package com.iplflash.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableKafka
public class KafkaTopicConfig {

    public static final String BOOKING_REQUESTS_TOPIC = "booking-requests";

    /**
     * Spring Kafka will create this topic automatically on startup if it
     * doesn't already exist - handy for local dev so you don't have to run
     * a separate kafka-topics.sh command by hand.
     *
     * partitions(3): lets up to 3 consumer instances process messages in
     * parallel (more on this when we scale the worker side).
     * replicas(1): fine for local dev with a single broker; a real
     * production cluster would use 3.
     */
    @Bean
    public NewTopic bookingRequestsTopic() {
        return TopicBuilder.name(BOOKING_REQUESTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
