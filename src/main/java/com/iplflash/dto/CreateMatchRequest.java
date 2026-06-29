package com.iplflash.dto;

public record CreateMatchRequest(
        String teamA,
        String teamB,
        String venue,
        int numberOfSeats,
        double pricePerSeat
) {
}
