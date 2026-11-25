package com.example.claudemicrovm.worker.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BashToolTest {
    @TempDir
    lateinit var workdir: Path

    private val tool by lazy { BashTool(AgentToolContext(workdir, env = mapOf("PATH" to System.getenv("PATH")))) }

    @AfterEach
    fun closeTool() = tool.close()

    private fun bash(vararg input: Pair<String, Any?>): String = runBlocking { tool.run(ToolInput.of(*input)) }

    @Test
    fun `runs commands in the workdir and keeps shell state between calls`() {
        assertEquals(workdir.toRealPath().toString(), bash("command" to "pwd -P"))
        bash("command" to "export GREETING=hello; mkdir sub; cd sub")
        assertEquals("hello", bash("command" to "echo \$GREETING"))
        assertTrue(bash("command" to "pwd").endsWith("/sub"))
    }

    @Test
    fun `merges stderr, strips ANSI escapes and trailing newlines`() {
        assertEquals("out\nerr\nred", bash("command" to "echo out; echo err >&2; printf '\\033[31mred\\033[0m\\n\\n\\n'"))
    }

    @Test
    fun `non-zero exit codes become tool errors carrying the output`() {
        assertEquals("boom", assertFailsWith<ToolError> { bash("command" to "echo boom; (exit 3)") }.message)
        assertEquals("exit 1", assertFailsWith<ToolError> { bash("command" to "false") }.message)
    }

    @Test
    fun `exiting the shell itself discards the session and the next call starts fresh`() {
        bash("command" to "export MARK=1")
        assertEquals("bash: bash session terminated", assertFailsWith<ToolError> { bash("command" to "exit 3") }.message)
        assertEquals("unset", bash("command" to "echo \${MARK:-unset}"))
    }

    @Test
    fun `stdin-reading commands get EOF instead of hanging`() {
        assertEquals("", bash("command" to "cat"))
    }

    @Test
    fun `timeouts discard the shell and the next call starts fresh`() {
        bash("command" to "export MARK=1")
        val e = assertFailsWith<ToolError> { bash("command" to "sleep 5", "timeout_ms" to 200) }
        assertEquals("bash: bash command timed out after 200ms", e.message)
        assertEquals("", bash("command" to "echo \${MARK:-}"))
    }

    @Test
    fun `restart with and without a command`() {
        bash("command" to "export MARK=1")
        assertEquals("bash session restarted", bash("restart" to true))
        assertEquals("unset", bash("command" to "echo \${MARK:-unset}"))
        assertEquals("bash: command is required", assertFailsWith<ToolError> { bash() }.message)
    }

    @Test
    fun `keeps only the tail of very large output`() {
        val out = bash("command" to "head -c 300000 /dev/zero | tr '\\0' 'x'; echo; echo END")
        assertTrue(out.startsWith("[output truncated]\n"))
        assertTrue(out.endsWith("END"))
        assertTrue(out.length <= BASH_OUTPUT_LIMIT + 32)
    }

    @Test
    fun `a command cannot spoof the exit-code sentinel`() {
        assertEquals("__ANT_CMD_fake_DONE__0", bash("command" to "echo __ANT_CMD_fake_DONE__0"))
    }

    @Test
    fun `the default shell environment drops the ANTHROPIC namespace`() {
        val env = scrubbedShellEnv(mapOf("ANTHROPIC_API_KEY" to "k", "ANTHROPIC_AUTH_TOKEN" to "t", "HOME" to "/root"))
        assertEquals(mapOf("HOME" to "/root"), env)
        assertFalse(scrubbedShellEnv().keys.any { it.startsWith("ANTHROPIC_") })
    }
}
