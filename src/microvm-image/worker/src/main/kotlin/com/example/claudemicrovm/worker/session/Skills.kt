package com.example.claudemicrovm.worker.session

import com.example.claudemicrovm.worker.anthropic.SkillsApi
import com.example.claudemicrovm.worker.tools.createDirectories
import com.example.claudemicrovm.worker.tools.jsonQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigInteger
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

private val log = LoggerFactory.getLogger("com.example.claudemicrovm.worker.session.Skills")
private val NUMERIC = Regex("^\\d+$")

/** A skill archive that must not be extracted, or that could not be. */
class SkillArchiveException(message: String) : IOException(message)

/**
 * Download the session agent's skills into `<workdir>/skills/<name>/`.
 *
 * For each skill attached to the session's agent, resolves its version, fetches
 * the archive (a zip or tar.* file) and extracts it into a directory named after
 * the skill. A failure on one skill is logged and does not block the others.
 * Returns a cleanup function that removes the directories this call created.
 */
suspend fun setupSkills(api: SkillsApi, sessionId: String, workdir: Path): suspend () -> Unit {
    val skillsRoot = workdir.resolve("skills").toAbsolutePath().normalize()
    val created = mutableListOf<Path>()
    for (skill in api.sessionSkills(sessionId)) {
        try {
            val version = resolveSkillVersion(api, skill.skillId, skill.version)
            val name = api.versionName(skill.skillId, version)
            // The directory is the skill's name reduced to a single safe path
            // component, so a hostile name cannot escape `skillsRoot`.
            val dest = skillsRoot.resolve(skillDirName(name, skill.skillId)).normalize()
            if (dest == skillsRoot || dest.parent != skillsRoot) {
                log.warn("skill name escapes the skills dir; skipping (name={})", name)
                continue
            }
            withContext(Dispatchers.IO) {
                deleteTree(dest)
                createDirectories(dest)
            }
            created.add(dest)
            val archive = dest.resolve(".skill-archive-${ProcessHandle.current().pid()}-${System.currentTimeMillis()}")
            try {
                api.download(skill.skillId, version, archive)
                extractSkillArchive(archive, dest)
            } finally {
                withContext(Dispatchers.IO) { Files.deleteIfExists(archive) }
            }
            log.info("downloaded skill (skill_id={}, version={}, dest={})", skill.skillId, version, dest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("failed to download skill (skill_id={}, error={})", skill.skillId, e.toString())
        }
    }
    return {
        for (dest in created) {
            try {
                withContext(Dispatchers.IO) { deleteTree(dest) }
            } catch (e: IOException) {
                log.warn("failed to clean up skill (dest={}, error={})", dest, e.toString())
            }
        }
    }
}

/** The last path component of [name] (like `path.basename`), or [fallback] when that is empty, `.` or `..`. */
internal fun skillDirName(name: String, fallback: String): String {
    val base = name.trim().trimEnd('/').substringAfterLast('/')
    return if (base.isEmpty() || base == "." || base == ".." || base.contains('\u0000')) fallback else base
}

/**
 * Resolve [version] to the concrete numeric version the versions endpoints
 * require: the session's skill version can be an alias such as `latest`, which
 * resolves to the newest numeric version. Numeric versions pass through.
 */
suspend fun resolveSkillVersion(api: SkillsApi, skillId: String, version: String?): String {
    if (version != null && NUMERIC.matches(version)) return version
    return api.versions(skillId)
        .filter { NUMERIC.matches(it) }
        .maxByOrNull { BigInteger(it) }
        ?: throw SkillArchiveException(
            "skill ${jsonQuote(skillId)} has no concrete version to resolve ${jsonQuote(version ?: "latest")} against",
        )
}

/**
 * Extract a skill archive (zip or tar.*) into [dest] by shelling out to `unzip`
 * / `tar`, which must be on `PATH`. The listing is validated first: members that
 * are absolute, contain `..`, or are symlinks / hardlinks / devices are refused
 * (zip-slip / tar-slip). The archive's single wrapper directory, if any, is
 * stripped so files land at `dest/SKILL.md` rather than `dest/<skill>/SKILL.md`.
 */
suspend fun extractSkillArchive(archive: Path, dest: Path) {
    val stage = dest.resolveSibling(".skill-stage-${ProcessHandle.current().pid()}-${System.currentTimeMillis()}")
    try {
        // Zip archives start with "PK\x03\x04"; anything else is treated as tar.*
        // (`tar -xf` autodetects gzip / bzip2 / xz).
        val head = withContext(Dispatchers.IO) { Files.newInputStream(archive).use { it.readNBytes(4) } }
        val isZip = head.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        val cmd = if (isZip) "unzip" else "tar"
        val file = archive.toString()
        val listing = runArchiveTool(cmd, if (isZip) listOf("-Z1", file) else listOf("-tf", file))
        assertSafeMemberNames(listing)
        assertNoSpecialMembers(runArchiveTool(cmd, if (isZip) listOf("-Z", file) else listOf("-tvf", file)))
        val top = archiveTopDir(listing)
        withContext(Dispatchers.IO) { createDirectories(stage) }
        runArchiveTool(
            cmd,
            if (isZip) listOf("-oq", file, "-d", stage.toString()) else listOf("-xf", file, "-C", stage.toString()),
        )
        withContext(Dispatchers.IO) {
            // Promote the wrapper's contents (or the staged tree itself) into `dest`;
            // `stage` is a sibling of `dest`, so each move stays on one filesystem.
            val srcRoot = if (top.isEmpty()) stage else stage.resolve(top)
            Files.list(srcRoot).use { entries ->
                for (entry in entries.toList()) Files.move(entry, dest.resolve(entry.fileName.toString()))
            }
        }
    } finally {
        withContext(Dispatchers.IO) { deleteTree(stage) }
    }
}

/** Reject archive members that are absolute or contain a `..` component. */
internal fun assertSafeMemberNames(listing: String) {
    for (raw in listing.lines()) {
        val entry = raw.trim()
        if (entry.isEmpty()) continue
        if (entry.startsWith("/") || entry.split('/', '\\').contains("..")) {
            throw SkillArchiveException("refusing to extract unsafe archive member: $entry")
        }
    }
}

/**
 * Reject archives containing anything but regular files and directories. The
 * type char is the first byte of each `ls`-style line of `tar -tvf` / `unzip -Z`:
 * `l` symlink, `h` hardlink, `b`/`c` device, `p` fifo, `s` socket.
 */
internal fun assertNoSpecialMembers(verboseListing: String) {
    for (line in verboseListing.lines()) {
        if (line.trimStart().firstOrNull() in setOf('l', 'h', 'b', 'c', 'p', 's')) {
            throw SkillArchiveException("refusing to extract archive with symlink/hardlink/device member")
        }
    }
}

/**
 * The single top-level directory shared by every entry of an archive listing,
 * or `""` when entries do not all live under one directory (a flat or
 * multi-root archive).
 */
internal fun archiveTopDir(listing: String): String {
    var top: String? = null
    var nested = false
    for (raw in listing.lines()) {
        // Drop `.` / empty segments so `./pdf/...` is treated like `pdf/...`.
        val parts = raw.trim().split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) continue
        if (top == null) top = parts[0] else if (parts[0] != top) return ""
        if (parts.size > 1) nested = true
    }
    return if (top != null && nested) top else ""
}

/** Run an archive CLI and return its stdout; a missing binary becomes a clear error. */
private suspend fun runArchiveTool(cmd: String, args: List<String>): String = coroutineScope {
    val process = try {
        withContext(Dispatchers.IO) { ProcessBuilder(listOf(cmd) + args).start() }
    } catch (e: IOException) {
        throw SkillArchiveException("skill extraction requires the `$cmd` command, but it was not found on PATH")
    }
    try {
        process.outputStream.close()
        val stderr = async(Dispatchers.IO) { process.errorStream.readBytes().toString(Charsets.UTF_8) }
        val stdout = withContext(Dispatchers.IO) { process.inputStream.readBytes().toString(Charsets.UTF_8) }
        val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
        if (code != 0) throw SkillArchiveException("$cmd ${args.joinToString(" ")} failed (exit $code): ${stderr.await().trim()}")
        stdout
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

/** Delete [root] recursively without following symlinks; a missing [root] is a no-op. */
internal fun deleteTree(root: Path) {
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
