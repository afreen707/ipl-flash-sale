package com.iplflash.controller;

import com.iplflash.dto.CreateMatchRequest;
import com.iplflash.entity.Match;
import com.iplflash.entity.Seat;
import com.iplflash.repository.MatchRepository;
import com.iplflash.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchRepository matchRepository;
    private final SeatRepository seatRepository;

    /**
     * Creates a match plus N seats labeled A1, A2, A3...
     * This is just test setup so we have something real to lock/book.
     */
    @PostMapping
    public ResponseEntity<Match> createMatch(@RequestBody CreateMatchRequest request) {
        Match match = new Match(request.teamA(), request.teamB(), request.venue(), LocalDateTime.now().plusDays(1));
        match = matchRepository.save(match);

        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= request.numberOfSeats(); i++) {
            seats.add(new Seat(match, "A" + i, request.pricePerSeat()));
        }
        seatRepository.saveAll(seats);

        return ResponseEntity.ok(match);
    }

    @GetMapping("/{matchId}/seats")
    public ResponseEntity<List<Seat>> getSeats(@PathVariable Long matchId) {
        return ResponseEntity.ok(seatRepository.findByMatchId(matchId));
    }
}
