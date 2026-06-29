package com.iplflash.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "seats", uniqueConstraints = @UniqueConstraint(columnNames = {"match_id", "seat_code"}))
@Getter
@Setter
@NoArgsConstructor
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private Match match;

    // e.g. "A1", "B12" - the human-readable seat label
    @Column(name = "seat_code", nullable = false)
    private String seatCode;

    private Double price;

    @Enumerated(EnumType.STRING)
    private SeatStatus status = SeatStatus.AVAILABLE;

    public Seat(Match match, String seatCode, Double price) {
        this.match = match;
        this.seatCode = seatCode;
        this.price = price;
        this.status = SeatStatus.AVAILABLE;
    }

    /**
     * This is the string we use as the Redis lock key.
     * Unique per match+seat so locking seat A1 for Match #5
     * never collides with seat A1 for Match #6.
     */
    public String lockKey() {
        return "seat-lock:" + match.getId() + ":" + seatCode;
    }
}
