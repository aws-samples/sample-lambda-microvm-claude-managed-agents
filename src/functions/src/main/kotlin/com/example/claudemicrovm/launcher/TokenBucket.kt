package com.example.claudemicrovm.launcher

import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/** A thread-safe token-bucket rate limiter (used for the RunMicrovm TPS limit). */
class TokenBucket(
    ratePerSecond: Double,
    capacity: Double = 1.0,
    /** Monotonic clock in seconds. */
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
    /** Sleep for the given number of seconds. */
    private val sleep: (Double) -> Unit = { seconds -> Thread.sleep(Duration.ofNanos((seconds * 1e9).toLong())) },
) {
    init {
        require(ratePerSecond > 0) { "rate_per_second must be positive" }
        require(capacity > 0) { "capacity must be positive" }
    }

    private val rate = ratePerSecond
    private val capacity = capacity
    private val epsilon = 1e-6
    private val lock = ReentrantLock()
    private var tokens = capacity
    private var last = clock()

    private fun refillLocked() {
        val now = clock()
        val elapsed = now - last
        if (elapsed > 0) {
            tokens = min(capacity, tokens + elapsed * rate)
            last = now
        }
    }

    /** Consume one token without blocking; returns whether one was available. */
    fun tryAcquire(): Boolean = lock.withLock {
        refillLocked()
        if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    /** Block until a token is available, then consume it. */
    fun acquire() {
        while (true) {
            val wait = lock.withLock {
                refillLocked()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return
                }
                max(epsilon, (1.0 - tokens) / rate)
            }
            sleep(wait)
        }
    }
}
