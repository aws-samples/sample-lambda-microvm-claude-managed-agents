package com.example.claudemicrovm.worker.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileToolsTest {
    @TempDir
    lateinit var workdir: Path

    private val ctx by lazy { AgentToolContext(workdir, maxFileBytes = 64) }

    private fun AgentTool.call(vararg input: Pair<String, Any?>): String = runBlocking { run(ToolInput.of(*input)) }

    private fun failure(tool: AgentTool, vararg input: Pair<String, Any?>): String? =
        assertFailsWith<ToolError> { tool.call(*input) }.message

    @Test
    fun `write creates parent directories and reports UTF-8 bytes`() {
        assertEquals("wrote 4 bytes to a/b/c.txt", WriteTool(ctx).call("file_path" to "a/b/c.txt", "content" to "hé!"))
        assertEquals("hé!", Files.readString(workdir.resolve("a/b/c.txt")))
        assertEquals("wrote 0 bytes to empty.txt", WriteTool(ctx).call("file_path" to "empty.txt"))
    }

    @Test
    fun `read returns the file or a 1-indexed inclusive line range`() {
        Files.writeString(workdir.resolve("f.txt"), "l1\nl2\nl3\nl4")
        val read = ReadTool(ctx)
        assertEquals("l1\nl2\nl3\nl4", read.call("file_path" to "f.txt"))
        assertEquals("l2\nl3", read.call("file_path" to "f.txt", "view_range" to listOf(2, 3)))
        assertEquals("l3\nl4", read.call("file_path" to "f.txt", "view_range" to listOf(3, -1)))
        assertEquals("", read.call("file_path" to "f.txt", "view_range" to listOf(9, 12)))
        assertEquals("read: view_range must be [start_line, end_line]", failure(read, "file_path" to "f.txt", "view_range" to listOf(1)))
    }

    @Test
    fun `read reports missing files, non-regular files and the size cap`() {
        val read = ReadTool(ctx)
        Files.createDirectories(workdir.resolve("dir"))
        Files.writeString(workdir.resolve("big.txt"), "x".repeat(65))
        assertEquals("read: file_path is required", failure(read))
        assertEquals("read: nope.txt: no such file or directory", failure(read, "file_path" to "nope.txt"))
        assertEquals("read: dir is not a regular file", failure(read, "file_path" to "dir"))
        assertEquals(
            "read: big.txt is 65 bytes, exceeds 64-byte limit. Use bash (head/tail/sed) to read a slice.",
            failure(read, "file_path" to "big.txt"),
        )
        assertEquals("absolute path \"/etc/hosts\" not permitted", failure(read, "file_path" to "/etc/hosts"))
    }

    @Test
    fun `edit replaces a unique occurrence literally`() {
        Files.writeString(workdir.resolve("e.txt"), "alpha beta gamma")
        assertEquals("edited e.txt (1 replacement(s))", EditTool(ctx).call("file_path" to "e.txt", "old_string" to "beta", "new_string" to "\$1 & \$&"))
        assertEquals("alpha \$1 & \$& gamma", Files.readString(workdir.resolve("e.txt")))
    }

    @Test
    fun `edit requires uniqueness unless replace_all`() {
        Files.writeString(workdir.resolve("e.txt"), "aaa")
        val edit = EditTool(ctx)
        assertEquals("edit: old_string appears 3 times in e.txt (must be unique)", failure(edit, "file_path" to "e.txt", "old_string" to "a", "new_string" to "b"))
        assertEquals("edit: old_string not found in e.txt", failure(edit, "file_path" to "e.txt", "old_string" to "z", "new_string" to "b"))
        assertEquals("edit: old_string is required", failure(edit, "file_path" to "e.txt", "old_string" to "", "new_string" to "b"))
        assertEquals("edited e.txt (3 replacement(s))", edit.call("file_path" to "e.txt", "old_string" to "a", "new_string" to "bb", "replace_all" to true))
        assertEquals("bbbbbb", Files.readString(workdir.resolve("e.txt")))
    }

    @Test
    fun `counts non-overlapping occurrences like String split`() {
        assertEquals(2, countOccurrences("aaaa", "aa"))
        assertEquals(0, countOccurrences("abc", "d"))
    }
}
