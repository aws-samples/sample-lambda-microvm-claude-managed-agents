package com.example.claudemicrovm.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RunRequestTest {
    @Test
    fun `unwraps the service envelope and the versioned payload`() {
        val raw = """
            {"microvmId":"mvm_1","runHookPayload":"{\"version\":\"1\",\"session\":{\"ANTHROPIC_SESSION_ID\":\"sesn_1\",\"ANTHROPIC_ENVIRONMENT_ID\":\"env_1\",\"ENVIRONMENT_KEY_PARAM_NAME\":\"/p\",\"AWS_REGION\":\"eu-west-1\",\"ANTHROPIC_BASE_URL\":\"https://example.test\"}}"}
        """.trimIndent()

        val parsed = assertIs<RunParseResult.Ok>(parseRunRequest(raw))

        assertEquals(
            RunRequest(Dispatch("sesn_1", "env_1", "/p", "eu-west-1", "https://example.test"), "mvm_1"),
            parsed.request,
        )
    }

    @Test
    fun `accepts a bare dispatch and treats empty values as absent`() {
        val parsed = assertIs<RunParseResult.Ok>(
            parseRunRequest("""{"ANTHROPIC_SESSION_ID":"sesn_1","ANTHROPIC_BASE_URL":""}"""),
        )
        assertEquals(RunRequest(Dispatch("sesn_1", null, null, null, null), null), parsed.request)

        val inlineObject = assertIs<RunParseResult.Ok>(
            parseRunRequest("""{"microvmId":"m","runHookPayload":{"session":{"ANTHROPIC_SESSION_ID":"s"}}}"""),
        )
        assertEquals("s", inlineObject.request.dispatch.sessionId)
    }

    @Test
    fun `reports missing session ids and malformed payloads`() {
        assertEquals(RunParseResult.MissingSessionId, parseRunRequest(""))
        assertEquals(RunParseResult.MissingSessionId, parseRunRequest("""{"runHookPayload":"{\"session\":{\"ANTHROPIC_SESSION_ID\":\"\"}}"}"""))
        assertIs<RunParseResult.Invalid>(parseRunRequest("null"))
        assertIs<RunParseResult.Invalid>(parseRunRequest("[]"))
        assertIs<RunParseResult.Invalid>(parseRunRequest("""{"runHookPayload":"not json"}"""))
        assertIs<RunParseResult.Invalid>(parseRunRequest("""{"runHookPayload":"[1]"}"""))
    }
}
