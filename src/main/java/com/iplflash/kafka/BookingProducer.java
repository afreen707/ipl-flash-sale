package com.iplflash.kafka;

import com.iplflash.dto.BookingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import static com.iplflash.config.KafkaTopicConfig.BOOKING_REQUESTS_TOPIC;

/**
 * Publishes a BookingEvent to Kafka. This is the "instead of slamming the
 * database, drop an event on the queue" half of the async flow.
 *
 * KafkaTemplate.send() is itself asynchronous and returns almost instantly -
 * it just hands the message to Kafka's client buffer to be sent in the
 * background. That's exactly why the controller can reply to the user right
 * after calling this, without waiting for any database work to happen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingProducer {

    private final KafkaTemplate<String, BookingEvent> kafkaTemplate;

    public void publishBookingEvent(BookingEvent event) {
        // Using lockKey as the partition key: Kafka guarantees all messages
        // with the same key land on the same partition and are processed
        // IN ORDER by the same consumer. That matters here - if the same
        // seat somehow got two events (e.g. a retry), we want them handled
        // one after another, never concurrently, by whichever single
        // consumer owns that partition.
        kafkaTemplate.send(BOOKING_REQUESTS_TOPIC, event.lockKey(), event);
        log.info("Published booking event to Kafka: {}", event);
    }
}
