package com.example.claudemicrovm.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenBucketTest {
    private var now = 0.0
    private val sleeps = mutableListOf<Double>()

    private fun bucket(rate: Double, capacity: Double = 1.0) = TokenBucket(
        rate,
        capacity,
        clock = { now },
        sleep = { seconds ->
            sleeps += seconds
            now += seconds
        },
    )

    @Test
    fun `tryAcquire spends the burst and then refills at the configured rate`() {
        val bucket = bucket(5.0)
        assertTrue(bucket.tryAcquire())
        assertFalse(bucket.tryAcquire())
        now += 0.1
        assertFalse(bucket.tryAcquire())
        now += 0.1
        assertTrue(bucket.tryAcquire())
    }

    @Test
    fun `acquire blocks until the next token is available`() {
        val bucket = bucket(5.0)
        bucket.acquire()
        bucket.acquire()
        bucket.acquire()
        assertEquals(2, sleeps.size)
        sleeps.forEach { assertEquals(0.2, it, 1e-9) }
        assertEquals(0.4, now, 1e-9)
    }

    @Test
    fun `capacity bounds bursts after idle time`() {
        val bucket = bucket(1.0, capacity = 3.0)
        repeat(3) { assertTrue(bucket.tryAcquire()) }
        assertFalse(bucket.tryAcquire())
        now += 60.0
        repeat(3) { assertTrue(bucket.tryAcquire()) }
        assertFalse(bucket.tryAcquire())
    }

    @Test
    fun `rejects non-positive rates and capacities`() {
        assertFailsWith<IllegalArgumentException> { TokenBucket(0.0) }
        assertFailsWith<IllegalArgumentException> { TokenBucket(1.0, capacity = 0.0) }
    }
}
