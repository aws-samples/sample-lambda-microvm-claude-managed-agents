package com.example.claudemicrovm.launcher

import software.amazon.lambda.powertools.idempotency.Idempotency
import software.amazon.lambda.powertools.idempotency.IdempotencyConfig
import software.amazon.lambda.powertools.idempotency.persistence.BasePersistenceStore
import software.amazon.lambda.powertools.idempotency.persistence.dynamodb.DynamoDBPersistenceStore
import java.time.Duration

/** Keep dedupe records for as long as a MicroVM can live. */
private const val IDEMPOTENCY_TTL_SECONDS = DEFAULT_MAX_LIFETIME_SECONDS.toLong()

/** Part of the idempotency record key, next to the Lambda function name. */
private const val IDEMPOTENT_FUNCTION_NAME = "launchAndDispatch"

/**
 * Configure Powertools Idempotency (keyed on the webhook `event_id`) over
 * [store]. Records live in a DynamoDB table with partition key `id` and TTL
 * attribute `expiration`.
 */
fun configureIdempotency(store: BasePersistenceStore) {
    Idempotency.config()
        .withPersistenceStore(store)
        .withConfig(
            IdempotencyConfig.builder()
                .withEventKeyJMESPath("event_id")
                .withExpiration(Duration.ofSeconds(IDEMPOTENCY_TTL_SECONDS))
                .build(),
        )
        .configure()
}

/** The DynamoDB-backed persistence store for [tableName]. */
fun dynamoDbIdempotencyStore(tableName: String): BasePersistenceStore =
    DynamoDBPersistenceStore.builder()
        .withTableName(tableName)
        .withExpiryAttr("expiration")
        .build()

/**
 * Wrap the launch side effect of [launcher] in Powertools Idempotency so that
 * retried or concurrent deliveries of the same webhook event launch exactly one
 * MicroVM: a repeat returns the stored [LaunchResult], a concurrent one throws
 * `IdempotencyAlreadyInProgressException`, and a failed launch deletes the
 * record so Anthropic's retry can try again.
 */
fun idempotentExecutor(launcher: Launcher): (WebhookEvent) -> LaunchResult = { event ->
    Idempotency.makeIdempotent(
        IDEMPOTENT_FUNCTION_NAME,
        mapOf("event_id" to event.eventId),
        { launcher.launchAndDispatch(event) },
        LaunchResult::class.java,
    )
}
