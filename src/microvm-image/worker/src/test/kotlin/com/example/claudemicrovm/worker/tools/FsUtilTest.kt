package com.example.claudemicrovm.worker.tools

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FsUtilTest {
    @TempDir
    lateinit var tmp: Path

    private val root: Path by lazy { Files.createDirectories(tmp.resolve("work")).toRealPath() }

    @Test
    fun `resolves relative paths inside the root, including ones that do not exist yet`() {
        Files.createDirectories(root.resolve("a"))
        assertEquals(root.resolve("a/b/c.txt"), confineToRoot(root, "a/b/c.txt"))
        assertEquals(root.resolve("x.txt"), confineToRoot(root, "a/../x.txt"))
        assertEquals(root, confineToRoot(root, "."))
    }

    @Test
    fun `rejects absolute paths unless unrestricted`() {
        val e = assertFailsWith<ToolError> { confineToRoot(root, "/etc/passwd") }
        assertEquals("absolute path \"/etc/passwd\" not permitted", e.message)
        assertEquals(Path.of("/etc/passwd"), confineToRoot(root, "/etc/passwd", allowOutside = true))
    }

    @Test
    fun `rejects dot-dot escapes`() {
        val e = assertFailsWith<ToolError> { confineToRoot(root, "../outside.txt") }
        assertEquals("path \"../outside.txt\" escapes workdir", e.message)
    }

    @Test
    fun `rejects symlinks that point outside the root, even dangling ones`() {
        val outside = Files.createDirectories(tmp.resolve("outside")).toRealPath()
        Files.createSymbolicLink(root.resolve("link"), outside)
        Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("missing.txt"))
        assertFailsWith<ToolError> { confineToRoot(root, "link/secret.txt") }
        assertFailsWith<ToolError> { confineToRoot(root, "dangling") }
    }

    @Test
    fun `follows symlinks that stay inside the root and returns the canonical path`() {
        val real = Files.createDirectories(root.resolve("real"))
        Files.createSymbolicLink(root.resolve("alias"), real)
        assertEquals(real.resolve("f.txt"), confineToRoot(root, "alias/f.txt"))
    }

    @Test
    fun `atomic write replaces the target and leaves no temp files`() {
        val target = root.resolve("file.txt")
        atomicWriteFile(target, "one")
        atomicWriteFile(target, "two ✓")
        assertEquals("two ✓", Files.readString(target))
        assertEquals(listOf("file.txt"), Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `quotes strings like JSON stringify`() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", jsonQuote("a\"b\\c\n\u0001"))
    }
}
