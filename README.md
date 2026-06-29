# IPL Ticket Flash Sale Engine

A backend system that survives thousands of users trying to book the **same seat at the same millisecond** without double-booking, crashing the database, or leaving seats stuck in limbo.

Built to demonstrate real distributed-systems problem solving — not just CRUD — using Spring Boot, Redis, Kafka, and PostgreSQL.

## The Problem

When a flash sale opens, large numbers of users hit "book" on the same seat within milliseconds of each other. A naive implementation — check if the seat is free, then mark it taken, as two separate database steps — has a race condition: many requests can all see "free" before any of them finishes writing "taken." Result: double bookings.

At scale, there's a second problem too: even with correct locking, writing every single booking attempt straight to Postgres synchronously means the database becomes the bottleneck the moment traffic spikes.

## Architecture

```
Client
  │
  ▼
POST /api/book
  │
  ├─► Redis: atomic SETNX-style claim (RBucket.trySet) ──► reject instantly if already held
  │
  ├─► Kafka: publish BookingEvent  ──────────────────────► returns "PROCESSING" to client (fast)
  │                                                          │
  │                                                          ▼
  │                                              BookingConsumer (background worker)
  │                                                          │
  │                                                          ▼
  │                                                 Writes Booking + Seat status to Postgres
  ▼
POST /api/payment/confirm  → seat permanently BOOKED
POST /api/payment/fail     → seat lock explicitly released (compensating action) + seat AVAILABLE again
                              (TTL on the Redis key is the safety net if neither endpoint is ever called)
```

## Key Design Decisions

**Redis `RBucket` (SETNX+TTL), not `RLock`.**
The obvious choice for "distributed lock" is Redisson's `RLock`. It's wrong for this use case. `RLock` is a *reentrant* mutex — it identifies the lock holder by **thread identity**, and deliberately lets the same thread re-acquire a lock it already holds. That's useful for protecting a single block of code, but a seat hold needs to survive across multiple, unrelated HTTP requests (book → confirm/fail), and Spring's web server reuses a small pool of worker threads across requests. A completely different user's request can land on a thread that previously held the lock, get treated as "reentrant," and slip past the lock incorrectly. Switching to `RBucket.trySet()` — a plain atomic "set if absent, with TTL" — removes the thread-identity concept entirely, which is what this scenario actually needs.

**TTL *and* Saga, not one or the other.**
- A failed payment triggers an explicit compensating action (`releaseSeat()`) that frees the seat in milliseconds — far better UX than making the next user wait out a TTL.
- The TTL never goes away, though — it's the safety net for the case where neither `confirm` nor `fail` ever gets called at all (user closes the tab, payment webhook never arrives). Without it, that seat would be stuck locked forever.

**Kafka decouples the request from the database write.**
`/api/book` only does two things: try the lock, and publish an event. It never touches Postgres. The actual database write happens in a separate consumer thread, picking messages off the queue at whatever pace the database can sustain — so a traffic spike hits Kafka (cheap to absorb) instead of directly hitting Postgres (expensive to absorb).

## Tech Stack
- Java 17, Spring Boot 3
- Redis + Redisson (distributed coordination)
- Apache Kafka (async processing)
- PostgreSQL (source of truth)
- Docker Compose (local infra)

## Bugs Found & Fixed During Development

These were real issues hit while building this, not hypotheticals — worth mentioning in an interview as evidence of actually understanding the system, not just assembling buzzwords:

1. **JVM timezone mismatch crashing every DB connection.** On Windows, Java reports the system timezone using a deprecated name (`Asia/Calcutta`) that Postgres's JDBC driver rejects outright, since Postgres only recognizes the modern IANA name (`Asia/Kolkata`). Fixed by forcing `-Duser.timezone=UTC` on the JVM via the Maven plugin config.
2. **The `RLock` reentrancy bug described above** — caught via a 20-concurrent-request load test that should have produced exactly one winner, but produced five, in a suspiciously regular pattern (every 5th request). Traced to Tomcat thread-pool reuse colliding with `RLock`'s thread-based ownership model.

## API Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/matches` | Create a match with N seats (test setup) |
| GET | `/api/matches/{id}/seats` | List seats for a match |
| POST | `/api/book` | Attempt to lock + book a seat |
| GET | `/api/book/status?matchId=&seatCode=` | Check if the async booking has completed |
| POST | `/api/payment/confirm` | Simulate successful payment → seat permanently sold |
| POST | `/api/payment/fail` | Simulate failed payment → seat released immediately |

## Running Locally

```bash
docker-compose up -d        # Postgres, Redis, Kafka, Zookeeper
mvn spring-boot:run
```

## Possible Future Improvements
- Idempotency keys on the Kafka event, so a duplicate/retried message can't double-book a seat
- Dead-letter queue for consumer messages that fail repeatedly
- A scheduled job to auto-expire bookings stuck in `PENDING_PAYMENT` past a timeout, independent of the Redis TTL
- Horizontal scaling test: multiple consumer instances across Kafka partitions
