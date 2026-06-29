package com.iplflash.kafka;

import com.iplflash.dto.BookingEvent;
import com.iplflash.entity.Booking;
import com.iplflash.entity.Seat;
import com.iplflash.entity.SeatStatus;
import com.iplflash.repository.BookingRepository;
import com.iplflash.repository.SeatRepository;
import com.iplflash.service.SeatLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.iplflash.config.KafkaTopicConfig.BOOKING_REQUESTS_TOPIC;

/**
 * This is the background worker. It runs on its own thread, completely
 * decoupled from the web request that originally triggered it - the user
 * already got their "PROCESSING" response long before this method even
 * starts running.
 *
 * THIS is where the actual, slower Postgres write happens. Because it's
 * driven by Kafka pulling messages off a queue rather than the web server
 * pushing requests directly at the database, Postgres only ever sees work
 * arrive at the pace this consumer can handle - not the pace users click.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingConsumer {

    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final SeatLockService seatLockService;

    @KafkaListener(topics = BOOKING_REQUESTS_TOPIC, groupId = "booking-workers")
    @Transactional
    public void handleBookingEvent(BookingEvent event) {
        log.info("Worker picked up booking event: {}", event);

        // Safety check: by the time this worker gets around to processing
        // the message, has the lock somehow already gone (e.g. it expired,
        // or was released by a payment-failure flow before we even got
        // here)? If so, don't book it - just log and bail out.
        if (!seatLockService.isLocked(event.lockKey())) {
            log.warn("Lock for {} is no longer held - skipping booking for user {}",
                    event.lockKey(), event.userId());
            return;
        }

        Seat seat = seatRepository.findById(event.seatId()).orElse(null);
        if (seat == null) {
            log.error("Seat {} not found - cannot complete booking", event.seatId());
            return;
        }

        seat.setStatus(SeatStatus.LOCKED);
        seatRepository.save(seat);

        Booking booking = new Booking(seat, event.userId());
        bookingRepository.save(booking);

        log.info("Booking {} created for seat {} by user {} - awaiting payment",
                booking.getId(), seat.getSeatCode(), event.userId());
    }
}
