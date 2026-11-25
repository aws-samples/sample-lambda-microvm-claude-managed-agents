package com.example.claudemicrovm.worker.anthropic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionEventTest {
    @Test
    fun `maps tool calls with their input`() {
        val event = SessionEvent.fromJson(
            """{"type":"agent.tool_use","id":"tu_1","name":"read","input":{"file_path":"a.txt","view_range":[1,5]}}""",
        )
        assertEquals(SessionEvent.ToolUse("tu_1", "read", mapOf("file_path" to "a.txt", "view_range" to listOf(1, 5)), false), event)
        assertEquals(
            SessionEvent.ToolUse("c", "x", emptyMap(), custom = true),
            SessionEvent.fromJson("""{"type":"agent.custom_tool_use","id":"c","name":"x"}"""),
        )
    }

    @Test
    fun `maps results, idles and the end of the session`() {
        assertEquals(
            SessionEvent.ToolAnswered("tu_1", "user.tool_result"),
            SessionEvent.fromJson("""{"type":"user.tool_result","tool_use_id":"tu_1"}"""),
        )
        assertEquals(
            SessionEvent.ToolAnswered("c_1", "user.custom_tool_result"),
            SessionEvent.fromJson("""{"type":"user.custom_tool_result","custom_tool_use_id":"c_1"}"""),
        )
        val idle = SessionEvent.fromJson("""{"type":"session.status_idle","stop_reason":{"type":"end_turn"}}""")
        assertTrue((idle as SessionEvent.Idle).isEndTurn)
        assertFalse((SessionEvent.fromJson("""{"type":"session.status_idle"}""") as SessionEvent.Idle).isEndTurn)
        assertEquals(SessionEvent.Ended("session.deleted"), SessionEvent.fromJson("""{"type":"session.deleted"}"""))
        assertEquals(SessionEvent.Other("agent.message"), SessionEvent.fromJson("""{"type":"agent.message"}"""))
    }
}
