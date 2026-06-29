package com.iplflash.dto;

import java.io.Serializable;

/**
 * This is the payload that travels through Kafka.
 *
 * Note it carries seatId (the actual DB primary key), not just matchId +
 * seatCode - we already looked the seat up once in the controller (to get
 * its lock key), so we pass the resolved ID along to save the consumer a
 * repeat lookup, and to avoid any ambiguity if the seat's code were ever
 * reused across data changes.
 */
public record BookingEvent(
        Long seatId,
        String userId,
        String lockKey
) implements Serializable {
}
