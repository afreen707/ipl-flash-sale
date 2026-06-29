package com.iplflash.controller;

import com.iplflash.dto.BookingEvent;
import com.iplflash.dto.BookingRequest;
import com.iplflash.entity.Booking;
import com.iplflash.entity.Seat;
import com.iplflash.kafka.BookingProducer;
import com.iplflash.repository.BookingRepository;
import com.iplflash.repository.SeatRepository;
import com.iplflash.service.SeatLockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * STEP 3 VERSION: the request path is now intentionally thin.
 *
 * All this method does is: look up the seat, try to grab the Redis lock,
 * and if successful, fire-and-forget an event onto Kafka. It does NOT touch
 * Postgres at all anymore - that work has moved to BookingConsumer, which
 * runs on a completely separate thread, picking messages off the queue at
 * its own pace.
 *
 * This means the HTTP response comes back to the user almost immediately -
 * the slow part (the database write) happens after the user already has
 * their answer.
 */
@RestController
@RequestMapping("/api/book")
@RequiredArgsConstructor
public class BookingController {

    private final SeatLockService seatLockService;
    private final SeatRepository seatRepository;
    private final BookingProducer bookingProducer;
    private final BookingRepository bookingRepository;

    @PostMapping
    public ResponseEntity<?> book(@Valid @RequestBody BookingRequest request) {
        Seat seat = seatRepository
                .findByMatchIdAndSeatCode(request.matchId(), request.seatCode())
                .orElse(null);

        if (seat == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Seat not found"));
        }

        String lockKey = seat.lockKey();

        // No wait/queueing here by design - this is a flash sale, we want a
        // fast "sorry, gone" rather than users waiting in an invisible line.
        boolean acquired = seatLockService.tryLockSeat(lockKey, request.userId());

        if (!acquired) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "SEAT_UNAVAILABLE", "seatCode", seat.getSeatCode()));
        }

        // We hold the lock - hand the actual DB write off to Kafka instead
        // of doing it here. This call returns almost instantly.
        bookingProducer.publishBookingEvent(new BookingEvent(seat.getId(), request.userId(), lockKey));

        return ResponseEntity.accepted().body(Map.of(
                "status", "PROCESSING",
                "seatCode", seat.getSeatCode(),
                "message", "Seat held for you. Confirming your booking in the background."
        ));
    }

    /**
     * Lets you check whether the Kafka worker has finished writing the
     * booking yet - handy for testing the async flow, since the original
     * POST response no longer waits for (or returns) a bookingId.
     */
    @GetMapping("/status")
    public ResponseEntity<?> checkStatus(@RequestParam Long matchId, @RequestParam String seatCode) {
        Seat seat = seatRepository.findByMatchIdAndSeatCode(matchId, seatCode).orElse(null);
        if (seat == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Seat not found"));
        }

        boolean stillLocked = seatLockService.isLocked(seat.lockKey());

        Booking latestBooking = bookingRepository
                .findFirstBySeatIdOrderByCreatedAtDesc(seat.getId())
                .orElse(null);

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("seatCode", seat.getSeatCode());
        response.put("seatStatus", seat.getStatus());
        response.put("redisLockHeld", stillLocked);
        response.put("bookingFoundInDb", latestBooking != null);
        response.put("bookingId", latestBooking != null ? latestBooking.getId() : null);
        response.put("bookedBy", latestBooking != null ? latestBooking.getUserId() : null);
        return ResponseEntity.ok(response);
    }
}
