package com.example.claudemicrovm.worker.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchToolsTest {
    @TempDir
    lateinit var tmp: Path

    private val workdir: Path by lazy { tmp.toRealPath() }
    private val ctx by lazy { AgentToolContext(workdir) }

    private fun AgentTool.call(vararg input: Pair<String, Any?>): String = runBlocking { run(ToolInput.of(*input)) }

    private fun file(rel: String, content: String = "", mtimeMillis: Long? = null): Path {
        val path = workdir.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        if (mtimeMillis != null) Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMillis))
        return path
    }

    @Test
    fun `glob patterns follow globstar semantics`() {
        val kt = globToRegex("**/*.kt")
        assertTrue(kt.matches("A.kt"))
        assertTrue(kt.matches("src/main/A.kt"))
        assertFalse(kt.matches("src/A.java"))
        assertTrue(globToRegex("src/**/*.kt").matches("src/A.kt"))
        assertTrue(globToRegex("src/**").matches("src/a/b/c"))
        assertFalse(globToRegex("*.kt").matches("src/A.kt"))
        assertTrue(globToRegex("file?.{kt,kts}").matches("file1.kts"))
        assertTrue(globToRegex("[a-c]x[!0-9]").matches("bxz"))
        assertFalse(globToRegex("[a-c]x[!0-9]").matches("bx1"))
        assertTrue(globToRegex("lit.(x)+").matches("lit.(x)+"))
    }

    @Test
    fun `glob returns matching files newest first and prunes excluded directories`() {
        file("old.txt", mtimeMillis = 1_000_000)
        file("nested/new.txt", mtimeMillis = 2_000_000)
        file("nested/skip.md")
        file("node_modules/dep.txt")
        file(".git/config.txt")
        val out = GlobTool(ctx).call("pattern" to "**/*.txt")
        assertEquals(listOf(workdir.resolve("nested/new.txt"), workdir.resolve("old.txt")).joinToString("\n"), out)
        assertEquals(workdir.resolve("nested/new.txt").toString(), GlobTool(ctx).call("pattern" to "*.txt", "path" to "nested"))
        assertEquals("no matches", GlobTool(ctx).call("pattern" to "*.none"))
    }

    @Test
    fun `glob refuses to leave the workdir`() {
        assertEquals("glob: absolute pattern not permitted", assertFailsWith<ToolError> { GlobTool(ctx).call("pattern" to "/etc/*") }.message)
        assertEquals("glob: \"..\" is not permitted in the pattern", assertFailsWith<ToolError> { GlobTool(ctx).call("pattern" to "../*") }.message)
    }

    @Test
    fun `grep walker reports file, line number and text`() {
        val a = file("a.txt", "one\nneedle here\nthree\r\nneedle again\n")
        file("sub/b.txt", "nothing\n")
        file("sub/long.txt", "needle" + "x".repeat(GREP_MAX_LINE_LENGTH))
        val out = GrepTool(ctx).call("pattern" to "needle")
        assertEquals("$a:2:needle here\n$a:4:needle again", out)
        assertEquals("no matches", GrepTool(ctx).call("pattern" to "absent", "path" to "sub"))
    }

    @Test
    fun `grep walker rejects invalid regexes and truncates large output`() {
        assertTrue(assertFailsWith<ToolError> { GrepTool(ctx).call("pattern" to "(") }.message!!.startsWith("grep: invalid regex: "))
        file("big.txt", "match\n".repeat(30_000))
        val out = GrepTool(ctx).call("pattern" to "match")
        assertTrue(out.endsWith("[output truncated at $GREP_OUTPUT_LIMIT bytes]"))
        assertTrue(out.length <= GREP_OUTPUT_LIMIT + 64)
    }
}
