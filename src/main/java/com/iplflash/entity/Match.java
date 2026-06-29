package com.iplflash.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "matches")
@Getter
@Setter
@NoArgsConstructor
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String teamA;
    private String teamB;
    private String venue;
    private LocalDateTime matchTime;

    public Match(String teamA, String teamB, String venue, LocalDateTime matchTime) {
        this.teamA = teamA;
        this.teamB = teamB;
        this.venue = venue;
        this.matchTime = matchTime;
    }
}
