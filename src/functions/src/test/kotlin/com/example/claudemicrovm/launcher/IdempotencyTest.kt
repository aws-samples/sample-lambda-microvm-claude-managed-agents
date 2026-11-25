package com.example.claudemicrovm.launcher

import software.amazon.lambda.powertools.idempotency.exceptions.IdempotencyItemAlreadyExistsException
import software.amazon.lambda.powertools.idempotency.exceptions.IdempotencyItemNotFoundException
import software.amazon.lambda.powertools.idempotency.persistence.BasePersistenceStore
import software.amazon.lambda.powertools.idempotency.persistence.DataRecord
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An in-memory stand-in for the DynamoDB store, with the same conditional-put rule. */
class InMemoryIdempotencyStore : BasePersistenceStore() {
    val records = ConcurrentHashMap<String, DataRecord>()

    override fun getRecord(idempotencyKey: String): DataRecord =
        records[idempotencyKey] ?: throw IdempotencyItemNotFoundException(idempotencyKey)

    @Synchronized
    override fun putRecord(record: DataRecord, now: Instant) {
        val existing = records[record.idempotencyKey]
        if (existing != null) {
            val expired = existing.expiryTimestamp < now.epochSecond
            val inProgressExpired = existing.inProgressExpiryTimestamp.isPresent &&
                existing.inProgressExpiryTimestamp.asLong < now.toEpochMilli()
            if (!expired && !inProgressExpired) {
                throw IdempotencyItemAlreadyExistsException("record exists", null, existing)
            }
        }
        records[record.idempotencyKey] = record
    }

    override fun updateRecord(record: DataRecord) {
        records[record.idempotencyKey] = record
    }

    override fun deleteRecord(idempotencyKey: String) {
        records.remove(idempotencyKey)
    }
}

class IdempotencyTest {
    private val store = InMemoryIdempotencyStore().also(::configureIdempotency)
    private val start = WebhookEvent("wevt_1", SESSION_RUN_STARTED, "sesn_1")

    private fun launcher(client: MicroVmClient) =
        Launcher(TEST_CONFIG, client, TokenBucket(1_000.0)).also { it.setExecutor(idempotentExecutor(it)) }

    @Test
    fun `a redelivered event returns the stored result without a second launch`() {
        val client = RecordingMicroVmClient()
        val launcher = launcher(client)

        val first = launcher.handle(start)
        val second = launcher.handle(start)
        val other = launcher.handle(start.copy(eventId = "wevt_2", sessionId = "sesn_2"))

        assertEquals(200, first.statusCode)
        assertEquals(first, second)
        assertEquals(200, other.statusCode)
        assertEquals(2, client.launches.size)
        assertEquals(2, store.records.size)
    }

    @Test
    fun `a failed launch is not remembered, so the retry launches again`() {
        val failing = RecordingMicroVmClient(fail = true)
        assertEquals(502, launcher(failing).handle(start).statusCode)
        assertTrue(store.records.isEmpty())

        val client = RecordingMicroVmClient()
        assertEquals(200, launcher(client).handle(start).statusCode)
        assertEquals(1, client.launches.size)
    }

    @Test
    fun `a concurrent delivery of the same event is acknowledged as in progress`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = MicroVmClient {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            LaunchedMicroVm("mvm_1", "https://e")
        }
        val launcher = launcher(client)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<LauncherResponse> { launcher.handle(start) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            assertEquals(LauncherResponse(200, "in progress"), launcher.handle(start))

            release.countDown()
            assertEquals(200, first.get(10, TimeUnit.SECONDS).statusCode)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
