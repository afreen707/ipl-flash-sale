package com.iplflash.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Handles distributed locking of seats using Redis as an atomic gatekeeper.
 *
 * IMPORTANT DESIGN NOTE:
 * We deliberately do NOT use Redisson's RLock here, even though "distributed
 * lock" might make you reach for it instinctively. RLock is a *reentrant*
 * mutex: it identifies "who holds the lock" by thread identity, and lets the
 * SAME thread re-acquire a lock it already holds (that's normally a useful
 * safety feature, preventing self-deadlock).
 *
 * But our use case isn't a short-lived mutex around one block of code - it's
 * a "reservation" that needs to survive across multiple, unrelated HTTP
 * requests (the original booking request, then later a payment-confirm or
 * payment-fail request). Spring's web server reuses a small pool of worker
 * threads across many different requests. If thread #7 originally acquired
 * the lock for User A, and is later reused by Tomcat to handle an unrelated
 * request from User B, RLock would see "thread #7 already holds this lock"
 * and wrongly let User B's request through as if it were a safe reentrant
 * call - even though it's a completely different logical caller.
 *
 * The fix is to use a plain atomic "SET this key only if absent, with a TTL"
 * operation instead - the classic Redis SETNX pattern. It has no concept of
 * thread/owner identity at all, so there's nothing to be wrongly reentrant
 * about. Redisson exposes this via RBucket.trySet(...).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatLockService {

    private final RedissonClient redissonClient;

    // Safety-net TTL: if a worker crashes after locking a seat and never
    // releases it, Redis automatically frees the seat after this long.
    private static final long LOCK_LEASE_MINUTES = 5;

    /**
     * Attempts to atomically claim a seat. Equivalent to Redis's
     * "SET key value NX EX 300" - succeeds only if the key didn't already
     * exist, and auto-expires after LOCK_LEASE_MINUTES either way.
     *
     * @param lockKey unique key for this seat, e.g. "seat-lock:5:A1"
     * @param holderId who is claiming it (e.g. userId) - stored as the
     *                  value, purely for visibility/debugging in Redis.
     * @return true if this caller now holds the seat, false if someone
     *         else already does
     */
    public boolean tryLockSeat(String lockKey, String holderId) {
        RBucket<String> bucket = redissonClient.getBucket(lockKey);
        boolean acquired = bucket.trySet(holderId, LOCK_LEASE_MINUTES, TimeUnit.MINUTES);
        if (acquired) {
            log.info("Lock ACQUIRED for {} by {}", lockKey, holderId);
        } else {
            log.info("Lock REJECTED for {} (already held by {})", lockKey, bucket.get());
        }
        return acquired;
    }

    /**
     * Explicitly releases a seat lock - this is the "compensating action" in
     * our Saga-lite flow. Called when payment fails, so the seat reopens
     * immediately instead of waiting up to 5 minutes for the TTL to expire.
     *
     * Unlike RLock.unlock(), this works correctly no matter which thread or
     * request calls it - there's no owner-thread check, just "delete the key."
     */
    public void releaseSeat(String lockKey) {
        RBucket<String> bucket = redissonClient.getBucket(lockKey);
        boolean existed = bucket.delete();
        log.info("Lock RELEASED for {} (existed: {})", lockKey, existed);
    }

    /**
     * Check if a seat is currently locked, without trying to acquire it.
     */
    public boolean isLocked(String lockKey) {
        RBucket<String> bucket = redissonClient.getBucket(lockKey);
        return bucket.isExists();
    }
}
