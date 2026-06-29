package com.iplflash.repository;

import com.iplflash.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByMatchId(Long matchId);
    Optional<Seat> findByMatchIdAndSeatCode(Long matchId, String seatCode);
}
