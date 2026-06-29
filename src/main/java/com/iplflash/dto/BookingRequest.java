package com.iplflash.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BookingRequest(
        @NotNull Long matchId,
        @NotBlank String seatCode,
        @NotBlank String userId
) {
}
