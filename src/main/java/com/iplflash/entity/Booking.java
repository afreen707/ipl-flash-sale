package com.iplflash.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.PENDING_PAYMENT;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Booking(Seat seat, String userId) {
        this.seat = seat;
        this.userId = userId;
        this.status = BookingStatus.PENDING_PAYMENT;
        this.createdAt = LocalDateTime.now();
    }
}
