package com.example.claudemicrovm.worker.tools

import com.example.claudemicrovm.worker.util.withCancellationHook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

internal const val GLOB_RESULT_LIMIT = 200
internal const val GREP_OUTPUT_LIMIT = 100 * 1024
internal const val GREP_MAX_LINE_LENGTH = 2000
private const val WALK_MAX_DEPTH = 40
private const val WALK_MAX_ENTRIES = 50_000
private val EXCLUDED_NAMES = setOf(".git", "node_modules")
private const val GLOB_META = "*?[{\\"

/** `glob`: match files under the workdir against a glob pattern; newest first. */
class GlobTool(private val ctx: AgentToolContext) : AgentTool {
    override val name = "glob"

    override suspend fun run(input: ToolInput): String = withContext(Dispatchers.IO) {
        val pattern = input.string("pattern")
        if (pattern.isNullOrEmpty()) throw ToolError("glob: pattern is required")
        val searchPath = input.string("path")
        var root: Path = ctx.workdir.toAbsolutePath().normalize()
        var pat: String = pattern
        if (pattern.startsWith("/")) {
            if (!ctx.unrestrictedPaths) throw ToolError("glob: absolute pattern not permitted")
            root = Path.of("/")
            pat = pattern.trimStart('/')
        } else if (!searchPath.isNullOrEmpty()) {
            root = resolvePath(ctx, searchPath)
        }
        // A `..` in the pattern itself would walk out of the search root.
        if (!ctx.unrestrictedPaths && pat.split('/', '\\').contains("..")) {
            throw ToolError("glob: \"..\" is not permitted in the pattern")
        }
        while (pat.startsWith("./")) pat = pat.removePrefix("./")
        val regex = try {
            globToRegex(pat)
        } catch (e: PatternSyntaxException) {
            throw ToolError("glob: invalid pattern: ${e.description}")
        }
        val matches = try {
            globFiles(root, pat, regex, confined = !ctx.unrestrictedPaths, job = coroutineContext[Job])
        } catch (e: IOException) {
            throw ToolError("glob: ${e.message}")
        }
        coroutineContext.ensureActive()
        if (matches.isEmpty()) return@withContext "no matches"
        matches
            .sortedWith(compareByDescending<Pair<Path, Long>> { it.second }.thenBy { it.first.toString() })
            .take(GLOB_RESULT_LIMIT)
            .joinToString("\n") { it.first.toString() }
    }
}

/**
 * Collect the regular files under [root] whose root-relative path matches
 * [regex]. Only the directory named by the pattern's literal leading segments is
 * walked; symlinks are never followed and `.git` / `node_modules` are pruned.
 */
private fun globFiles(root: Path, pattern: String, regex: Regex, confined: Boolean, job: Job?): List<Pair<Path, Long>> {
    val walkRoot = try {
        root.toRealPath()
    } catch (_: IOException) {
        return emptyList()
    }
    val segments = pattern.split('/').filter { it.isNotEmpty() }
    val literal = segments.takeWhile { segment -> segment.none { it in GLOB_META } }
        .let { if (it.size == segments.size) it.dropLast(1) else it }
    val baseRel = literal.fold(Path.of("")) { acc, segment -> acc.resolve(segment) }
    val base = try {
        walkRoot.resolve(baseRel).toRealPath()
    } catch (_: IOException) {
        return emptyList()
    }
    if (confined && !base.startsWith(walkRoot)) return emptyList()
    val matches = mutableListOf<Pair<Path, Long>>()
    Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (job?.isActive == false) return FileVisitResult.TERMINATE
            if (dir != base && dir.fileName?.toString() in EXCLUDED_NAMES) return FileVisitResult.SKIP_SUBTREE
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!attrs.isRegularFile || file.fileName?.toString() in EXCLUDED_NAMES) return FileVisitResult.CONTINUE
            val rel = baseRel.resolve(base.relativize(file))
            if (regex.matches(rel.joinToString("/"))) {
                matches += root.resolve(rel) to attrs.lastModifiedTime().toMillis()
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
    })
    return matches
}

/**
 * Translate a glob into an anchored regex over `/`-separated relative paths:
 * `*` and `?` stay within one segment, a whole-segment `**` spans any number of
 * segments (including none), `[...]` / `[!...]` are character classes and
 * `{a,b}` alternations (nestable); `\` escapes the next character.
 */
internal fun globToRegex(glob: String): Regex {
    val out = StringBuilder("^")
    var braceDepth = 0
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' -> {
                var j = i
                while (j < glob.length && glob[j] == '*') j++
                val wholeSegment = (i == 0 || glob[i - 1] == '/') && (j == glob.length || glob[j] == '/')
                if (j - i >= 2 && wholeSegment) {
                    if (j == glob.length) {
                        out.append(".*")
                        i = j
                    } else {
                        out.append("(?:[^/]*/)*")
                        i = j + 1
                    }
                } else {
                    out.append("[^/]*")
                    i = j
                }
            }
            c == '?' -> {
                out.append("[^/]")
                i++
            }
            c == '[' -> {
                val end = classEnd(glob, i)
                if (end < 0) {
                    out.append("\\[")
                    i++
                } else {
                    out.append(classToRegex(glob.substring(i + 1, end)))
                    i = end + 1
                }
            }
            c == '{' && braceEnd(glob, i) >= 0 -> {
                out.append("(?:")
                braceDepth++
                i++
            }
            c == '}' && braceDepth > 0 -> {
                out.append(')')
                braceDepth--
                i++
            }
            c == ',' && braceDepth > 0 -> {
                out.append('|')
                i++
            }
            c == '\\' && i + 1 < glob.length -> {
                out.append(quoteRegexChar(glob[i + 1]))
                i += 2
            }
            else -> {
                out.append(quoteRegexChar(c))
                i++
            }
        }
    }
    out.append('$')
    return Regex(out.toString())
}

/** Index of the `]` closing the class opened at [start], or -1 when unterminated. */
private fun classEnd(glob: String, start: Int): Int {
    var j = start + 1
    if (j < glob.length && (glob[j] == '!' || glob[j] == '^')) j++
    if (j < glob.length && glob[j] == ']') j++
    while (j < glob.length && glob[j] != ']') j++
    return if (j < glob.length) j else -1
}

/** Index of the `}` closing the brace opened at [start], or -1 when unbalanced. */
private fun braceEnd(glob: String, start: Int): Int {
    var depth = 0
    var j = start
    while (j < glob.length) {
        when (glob[j]) {
            '\\' -> j++
            '{' -> depth++
            '}' -> if (--depth == 0) return j
        }
        j++
    }
    return -1
}

/** A glob character class body (between the brackets) as a regex class that never matches `/`. */
private fun classToRegex(body: String): String {
    val negated = body.startsWith("!") || body.startsWith("^")
    val members = buildString {
        for (ch in if (negated) body.substring(1) else body) {
            if (ch in "\\[]&^") append('\\')
            append(ch)
        }
    }
    // `/` goes first so a trailing `-` in the body stays a literal.
    return if (negated) "[^/$members]" else "(?!/)[$members]"
}

private fun quoteRegexChar(c: Char): String = if (c.isLetterOrDigit() || c == '_') c.toString() else "\\$c"

/** `grep`: search file contents for a regex, via ripgrep when on `PATH`, else a built-in walker. */
class GrepTool(private val ctx: AgentToolContext) : AgentTool {
    override val name = "grep"

    override suspend fun run(input: ToolInput): String {
        val pattern = input.string("pattern")
        if (pattern.isNullOrEmpty()) throw ToolError("grep: pattern is required")
        val p = input.string("path")
        val searchPath = withContext(Dispatchers.IO) {
            if (p.isNullOrEmpty()) ctx.workdir.toAbsolutePath().normalize() else resolvePath(ctx, p)
        }
        val rg = findRipgrep()
        return if (rg != null) runRipgrep(rg, pattern, searchPath) else runWalkGrep(pattern, searchPath)
    }
}

private fun findRipgrep(): Path? = (System.getenv("PATH") ?: "")
    .split(File.pathSeparatorChar)
    .asSequence()
    .filter { it.isNotEmpty() }
    .mapNotNull { dir -> runCatching { Path.of(dir, "rg") }.getOrNull() }
    .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

private suspend fun runRipgrep(rg: Path, pattern: String, searchPath: Path): String {
    val process = withContext(Dispatchers.IO) {
        ProcessBuilder(rg.toString(), "-n", "--no-heading", "-e", pattern, "--", searchPath.toString()).start()
    }
    try {
        return withCancellationHook({ process.destroyForcibly() }) {
            process.outputStream.close()
            val stderr = async(Dispatchers.IO) { process.errorStream.readBytes().toString(Charsets.UTF_8) }
            val (out, truncated) = withContext(Dispatchers.IO) {
                readCapped(process.inputStream.reader(Charsets.UTF_8), GREP_OUTPUT_LIMIT) { process.destroyForcibly() }
            }
            val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
            val err = stderr.await()
            coroutineContext.ensureActive()
            when {
                truncated -> out + "\n[output truncated at $GREP_OUTPUT_LIMIT bytes]"
                code == 0 -> out
                code == 1 -> "no matches"
                else -> throw ToolError("grep: rg failed: ${err.ifEmpty { "exit $code" }}")
            }
        }
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

/** Read [reader] fully, keeping at most [limit] chars; calls [onTruncate] and stops when exceeded. */
private fun readCapped(reader: Reader, limit: Int, onTruncate: () -> Unit): Pair<String, Boolean> {
    val out = StringBuilder()
    val chunk = CharArray(8192)
    try {
        while (true) {
            val n = reader.read(chunk)
            if (n < 0) break
            out.appendRange(chunk, 0, n)
            if (out.length > limit) {
                out.setLength(limit)
                onTruncate()
                return out.toString() to true
            }
        }
    } catch (_: IOException) {
        // The process was killed (truncation or cancellation).
    }
    return out.toString() to false
}

private suspend fun runWalkGrep(pattern: String, root: Path): String = withContext(Dispatchers.IO) {
    val regex = try {
        Pattern.compile(pattern)
    } catch (e: PatternSyntaxException) {
        throw ToolError("grep: invalid regex: ${e.description} near index ${e.index}")
    }
    val job = coroutineContext[Job]
    val hits = mutableListOf<String>()
    var budget = GREP_OUTPUT_LIMIT
    val push: (String) -> Boolean = { line ->
        budget -= line.length + 1
        if (budget < 0) {
            hits += "[output truncated at $GREP_OUTPUT_LIMIT bytes]"
            false
        } else {
            hits += line
            true
        }
    }
    val attrs = runCatching { Files.readAttributes(root, BasicFileAttributes::class.java) }.getOrNull()
    if (attrs?.isRegularFile == true) {
        grepFile(root, regex, push)
    } else {
        walk(root) { file -> job?.isActive != false && grepFile(file, regex, push) }
    }
    coroutineContext.ensureActive()
    if (hits.isEmpty()) "no matches" else hits.joinToString("\n")
}

/** Grep one file line by line; returns false once the output budget is exhausted. */
private fun grepFile(file: Path, regex: Pattern, push: (String) -> Boolean): Boolean {
    try {
        Files.newInputStream(file).use { stream ->
            var lineNo = 0
            return forEachBoundedLine(stream) { line ->
                lineNo++
                // Overlong lines are skipped (line == null): a pathological
                // model-supplied pattern against a huge line is a ReDoS.
                line == null || !regex.matcher(line).find() || push("$file:$lineNo:$line")
            }
        }
    } catch (_: IOException) {
        // Unreadable file: skip it.
        return true
    }
}

/**
 * Feed each `\n`-terminated line (UTF-8, trailing `\r` dropped) of [stream] to
 * [onLine]; lines longer than [GREP_MAX_LINE_LENGTH] arrive as `null` and are
 * never buffered whole. Stops (returning false) as soon as [onLine] returns false.
 */
private fun forEachBoundedLine(stream: InputStream, onLine: (String?) -> Boolean): Boolean {
    val reader = stream.reader(Charsets.UTF_8)
    val line = StringBuilder()
    var overlong = false
    val chunk = CharArray(8192)
    fun emit(): Boolean {
        if (line.isNotEmpty() && line[line.length - 1] == '\r') line.setLength(line.length - 1)
        val keepGoing = onLine(if (overlong || line.length > GREP_MAX_LINE_LENGTH) null else line.toString())
        line.setLength(0)
        overlong = false
        return keepGoing
    }
    while (true) {
        val n = reader.read(chunk)
        if (n < 0) break
        for (k in 0 until n) {
            val ch = chunk[k]
            if (ch == '\n') {
                if (!emit()) return false
            } else if (!overlong) {
                line.append(ch)
                // +1 leaves room for a trailing '\r' that is dropped on emit.
                if (line.length > GREP_MAX_LINE_LENGTH + 1) {
                    overlong = true
                    line.setLength(0)
                }
            }
        }
    }
    return if (line.isNotEmpty() || overlong) emit() else true
}

/**
 * Bounded recursive walk handing every regular file to [onFile] (which returns
 * false to stop). Only real directories are descended into; symlinks, devices,
 * FIFOs and sockets are skipped so a symlink inside the root is never followed.
 */
private fun walk(root: Path, onFile: (Path) -> Boolean) {
    var remaining = WALK_MAX_ENTRIES
    fun visit(dir: Path, depth: Int): Boolean {
        if (depth > WALK_MAX_DEPTH) return true
        val entries = try {
            Files.newDirectoryStream(dir).use { stream -> stream.sortedBy { it.fileName.toString() } }
        } catch (_: IOException) {
            return true
        }
        for (child in entries) {
            if (child.fileName.toString() in EXCLUDED_NAMES) continue
            if (remaining-- <= 0) return false
            val attrs = try {
                Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                continue
            }
            if (attrs.isDirectory) {
                if (!visit(child, depth + 1)) return false
            } else if (attrs.isRegularFile) {
                if (!onFile(child)) return false
            }
        }
        return true
    }
    visit(root, 0)
}
