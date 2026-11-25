package com.example.claudemicrovm.launcher

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.UnwrapWebhookParams
import com.anthropic.core.http.Headers
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(WebhookVerifier::class.java)

/** Verifies an inbound webhook delivery. */
fun interface WebhookVerifier {
    /** True if the signature over [body] is valid and the delivery is fresh. */
    fun verify(body: String, headers: Map<String, String>, signingSecret: String): Boolean
}

/**
 * Verifies deliveries with the Anthropic SDK (`client.beta().webhooks().unwrap`):
 * the Standard Webhooks HMAC over `webhook-id`, `webhook-timestamp` and the raw
 * body, plus a freshness check on the timestamp. The client never makes a
 * request here, so its API key is a placeholder.
 */
class AnthropicWebhookVerifier(
    clientFactory: () -> AnthropicClient = {
        AnthropicOkHttpClient.builder().apiKey("unused-for-webhook-verification").build()
    },
) : WebhookVerifier {
    private val client by lazy(clientFactory)

    override fun verify(body: String, headers: Map<String, String>, signingSecret: String): Boolean = try {
        val sdkHeaders = Headers.builder()
        // API Gateway preserves the sender's header casing; the verifier expects lower case.
        headers.forEach { (name, value) -> sdkHeaders.put(name.lowercase(), value) }
        client.beta().webhooks().unwrap(
            UnwrapWebhookParams.builder().body(body).headers(sdkHeaders.build()).secret(signingSecret).build(),
        )
        true
    } catch (e: Exception) {
        log.warn("webhook signature verification failed: {}", e.toString())
        false
    }
}
