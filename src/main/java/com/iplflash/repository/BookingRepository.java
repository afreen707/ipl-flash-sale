package com.iplflash.repository;

import com.iplflash.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findFirstBySeatIdOrderByCreatedAtDesc(Long seatId);
}
