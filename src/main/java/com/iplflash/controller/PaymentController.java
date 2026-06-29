package com.iplflash.controller;

import com.iplflash.dto.PaymentRequest;
import com.iplflash.entity.Booking;
import com.iplflash.entity.BookingStatus;
import com.iplflash.entity.Seat;
import com.iplflash.entity.SeatStatus;
import com.iplflash.repository.BookingRepository;
import com.iplflash.repository.SeatRepository;
import com.iplflash.service.SeatLockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Simulates the payment step. In a real system this would be a webhook
 * called by your payment gateway (Razorpay, Stripe, etc) - we're faking
 * that callback here so we can test both outcomes on demand.
 *
 * THIS is where the "TTL vs Saga" question from the very start of this
 * project actually gets answered: we use BOTH.
 *   - On success: the seat is permanently sold. Nothing to roll back.
 *   - On failure: we run an explicit COMPENSATING ACTION (Saga-style) -
 *     immediately deleting the Redis key via releaseSeat() - rather than
 *     silently waiting for the 5-minute TTL to expire on its own. That's
 *     the difference between "the seat is free again in 5 minutes" and
 *     "the seat is free again in milliseconds."
 *   - The TTL itself never goes away though - it's still the safety net
 *     for the case where NEITHER confirm nor fail ever gets called at all
 *     (e.g. the user just closes their browser tab mid-payment, or the
 *     payment gateway's webhook never arrives). Without the TTL, that
 *     seat would be stuck locked forever.
 */
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final SeatLockService seatLockService;

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@Valid @RequestBody PaymentRequest request) {
        Seat seat = seatRepository
                .findByMatchIdAndSeatCode(request.matchId(), request.seatCode())
                .orElse(null);
        if (seat == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Seat not found"));
        }

        Booking booking = bookingRepository.findFirstBySeatIdOrderByCreatedAtDesc(seat.getId()).orElse(null);
        if (booking == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No booking found for this seat yet - has the Kafka worker run?"));
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);

        seat.setStatus(SeatStatus.BOOKED);
        seatRepository.save(seat);

        // No lock release here on purpose - the seat is genuinely sold now,
        // so we WANT it to stay unavailable. We just let the Redis key
        // expire naturally via its TTL; it's harmless since the seat's
        // real status (BOOKED, in Postgres) is now the source of truth.
        log.info("Payment CONFIRMED for seat {} - booking {} finalized", seat.getSeatCode(), booking.getId());

        return ResponseEntity.ok(Map.of(
                "status", "CONFIRMED",
                "bookingId", booking.getId(),
                "seatCode", seat.getSeatCode()
        ));
    }

    @PostMapping("/fail")
    public ResponseEntity<?> fail(@Valid @RequestBody PaymentRequest request) {
        Seat seat = seatRepository
                .findByMatchIdAndSeatCode(request.matchId(), request.seatCode())
                .orElse(null);
        if (seat == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Seat not found"));
        }

        Booking booking = bookingRepository.findFirstBySeatIdOrderByCreatedAtDesc(seat.getId()).orElse(null);
        if (booking == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No booking found for this seat yet - has the Kafka worker run?"));
        }

        booking.setStatus(BookingStatus.FAILED);
        bookingRepository.save(booking);

        seat.setStatus(SeatStatus.AVAILABLE);
        seatRepository.save(seat);

        // THE COMPENSATING ACTION: explicitly free the seat in Redis right
        // now, instead of leaving the next user waiting for the TTL.
        seatLockService.releaseSeat(seat.lockKey());

        log.info("Payment FAILED for seat {} - booking {} marked FAILED, seat reopened", seat.getSeatCode(), booking.getId());

        return ResponseEntity.ok(Map.of(
                "status", "FAILED",
                "bookingId", booking.getId(),
                "seatCode", seat.getSeatCode(),
                "message", "Seat released and is now available again."
        ));
    }
}
