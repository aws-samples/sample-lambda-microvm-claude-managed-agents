package com.example.claudemicrovm.worker.util

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Exponential backoff: `baseMs * 2^attempt`, clamped to [capMs]. */
fun backoff(attempt: Int, baseMs: Long, capMs: Long): Long =
    min(baseMs * 2.0.pow(attempt), capMs.toDouble()).toLong()

/** Uniform random delay in the half-open interval `[lowMs, highMs)`. */
fun jitter(lowMs: Long, highMs: Long, random: Random = Random.Default): Long =
    lowMs + (random.nextDouble() * (highMs - lowMs)).toLong()

/**
 * Trim up to 25% off [ms] at random so a fleet of clients backing off after a
 * shared outage does not retry in lockstep.
 */
fun applyJitter(ms: Long, random: Random = Random.Default): Long = (ms * (1 - random.nextDouble() * 0.25)).toLong()
