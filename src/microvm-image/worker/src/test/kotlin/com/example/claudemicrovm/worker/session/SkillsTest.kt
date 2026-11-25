package com.example.claudemicrovm.worker.session

import com.example.claudemicrovm.worker.anthropic.SkillRef
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillsTest {
    @TempDir
    lateinit var tmp: Path

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun onPath(cmd: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { Files.isExecutable(Path.of(it, cmd)) }

    @Test
    fun `finds the single wrapper directory of an archive listing`() {
        assertEquals("pdf", archiveTopDir("pdf/\npdf/SKILL.md\npdf/scripts/run.sh\n"))
        assertEquals("pdf", archiveTopDir("./pdf/SKILL.md\n./pdf/x\n"))
        assertEquals("", archiveTopDir("SKILL.md\nscripts/run.sh\n"))
        assertEquals("", archiveTopDir("pdf/SKILL.md\ndocx/SKILL.md\n"))
        assertEquals("", archiveTopDir("SKILL.md\n"))
    }

    @Test
    fun `rejects unsafe member names and special members`() {
        assertFailsWith<SkillArchiveException> { assertSafeMemberNames("ok.txt\n../evil.txt\n") }
        assertFailsWith<SkillArchiveException> { assertSafeMemberNames("/etc/passwd\n") }
        assertFailsWith<SkillArchiveException> { assertSafeMemberNames("a/..\\b\n") }
        assertSafeMemberNames("a/b..c/d\n")
        assertFailsWith<SkillArchiveException> {
            assertNoSpecialMembers("-rw-r--r-- 0 a/f\nlrwxrwxrwx 0 a/link -> /etc\n")
        }
        assertNoSpecialMembers("Archive:  x.zip\n-rw-r--r--  3.0 unx a/f\ndrwxr-xr-x  3.0 unx a/\n2 files\n")
    }

    @Test
    fun `reduces skill names to one safe directory name`() {
        assertEquals("pdf", skillDirName(" pdf ", "skill_1"))
        assertEquals("pdf", skillDirName("../../pdf/", "skill_1"))
        assertEquals("skill_1", skillDirName("..", "skill_1"))
        assertEquals("skill_1", skillDirName("  ", "skill_1"))
    }

    @Test
    fun `resolves version aliases to the newest numeric version`() = runBlocking {
        val api = FakeSkills(emptyList(), versions = mapOf("s" to listOf("1759178010641129", "99", "latest", "1759178010641130")))
        assertEquals("42", resolveSkillVersion(api, "s", "42"))
        assertEquals("1759178010641130", resolveSkillVersion(api, "s", "latest"))
        assertEquals("1759178010641130", resolveSkillVersion(api, "s", null))
        assertFailsWith<SkillArchiveException> { resolveSkillVersion(api, "none", "latest") }
        Unit
    }

    @Test
    fun `downloads skills into named directories, stripping the wrapper dir, and cleans them up`() = runBlocking {
        assumeTrue(onPath("unzip"), "unzip is not on PATH")
        val api = FakeSkills(
            skills = listOf(SkillRef("skill_pdf", "latest"), SkillRef("skill_flat", "7"), SkillRef("skill_bad", "1")),
            versions = mapOf("skill_pdf" to listOf("3", "12")),
            names = mapOf("skill_pdf@12" to "pdf", "skill_flat@7" to "flat", "skill_bad@1" to "bad"),
            archives = mapOf(
                "skill_pdf@12" to zip("pdf/SKILL.md" to "# pdf", "pdf/scripts/run.sh" to "echo hi"),
                "skill_flat@7" to zip("SKILL.md" to "# flat"),
                "skill_bad@1" to zip("../escape.txt" to "nope"),
            ),
        )

        val cleanup = setupSkills(api, "sesn_1", tmp)

        val skills = tmp.resolve("skills")
        assertEquals("# pdf", Files.readString(skills.resolve("pdf/SKILL.md")))
        assertEquals("echo hi", Files.readString(skills.resolve("pdf/scripts/run.sh")))
        assertEquals("# flat", Files.readString(skills.resolve("flat/SKILL.md")))
        assertFalse(Files.exists(tmp.resolve("escape.txt")))
        assertFalse(Files.exists(skills.resolve("escape.txt")))
        // Only the skill directories remain: no archives or staging dirs.
        assertEquals(setOf("pdf", "flat", "bad"), Files.list(skills).use { s -> s.map { it.fileName.toString() }.toList() }.toSet())
        assertTrue(Files.list(skills.resolve("bad")).use { it.count() } == 0L)

        cleanup()

        assertEquals(0L, Files.list(skills).use { it.count() })
    }

    @Test
    fun `extracts tar archives too`() = runBlocking {
        assumeTrue(onPath("tar"), "tar is not on PATH")
        val src = Files.createDirectories(tmp.resolve("src/docx"))
        Files.writeString(src.resolve("SKILL.md"), "# docx")
        val archive = tmp.resolve("skill.tar.gz")
        val tar = ProcessBuilder("tar", "-czf", archive.toString(), "-C", tmp.resolve("src").toString(), "docx").start()
        assertEquals(0, tar.waitFor())
        val dest = Files.createDirectories(tmp.resolve("out/docx"))

        extractSkillArchive(archive, dest)

        assertEquals("# docx", Files.readString(dest.resolve("SKILL.md")))
    }

    @Test
    fun `deleteTree does not follow symlinks`() {
        val outside = Files.createDirectories(tmp.resolve("outside"))
        Files.writeString(outside.resolve("keep.txt"), "keep")
        val tree = Files.createDirectories(tmp.resolve("tree/sub"))
        Files.createSymbolicLink(tree.resolve("link"), outside)

        deleteTree(tmp.resolve("tree"))

        assertFalse(Files.exists(tmp.resolve("tree")))
        assertEquals("keep", Files.readString(outside.resolve("keep.txt")))
    }
}
