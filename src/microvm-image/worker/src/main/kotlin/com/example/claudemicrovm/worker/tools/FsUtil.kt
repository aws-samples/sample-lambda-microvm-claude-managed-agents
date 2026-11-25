package com.example.claudemicrovm.worker.tools

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileSystemLoopException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Permissions for directories the file tools create (subject to the umask). */
private const val DIR_CREATE_MODE = "rwxr-xr-x"

/** Permissions for files the file tools create (subject to the umask). */
private const val FILE_CREATE_MODE = "rw-r--r--"

private const val MAX_SYMLINK_HOPS = 40

private val POSIX = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

/** Resolve [p] against the context's workdir, confined to it unless `unrestrictedPaths`. */
internal fun resolvePath(ctx: AgentToolContext, p: String): Path =
    confineToRoot(ctx.workdir, p, allowOutside = ctx.unrestrictedPaths)

/**
 * Resolve [p] and confine it to [root].
 *
 * Unless [allowOutside] is set, absolute inputs are rejected and the
 * **canonical** path is returned: every symlink in [p] — including the leaf,
 * even a dangling one — is resolved before the confinement check, and the
 * resolved path is what the caller then operates on, so a symlink inside [root]
 * that points outside it can neither pass the check nor be followed afterwards.
 *
 * Residual TOCTOU: a component could still be swapped for a symlink between this
 * call and the eventual filesystem operation; the MicroVM is the real sandbox.
 */
internal fun confineToRoot(root: Path, p: String, allowOutside: Boolean = false): Path {
    val input = try {
        Path.of(p)
    } catch (e: InvalidPathException) {
        throw ToolError("path ${jsonQuote(p)} is invalid: ${e.reason}")
    }
    if (input.isAbsolute) {
        if (!allowOutside) throw ToolError("absolute path ${jsonQuote(p)} not permitted")
        return input.normalize()
    }
    val realRoot = realPathOrSelf(root.toAbsolutePath().normalize())
    val abs = realRoot.resolve(input).normalize()
    if (allowOutside) return abs
    val real = canonicalize(abs)
    if (real != realRoot && !real.startsWith(realRoot)) {
        throw ToolError("path ${jsonQuote(p)} escapes workdir")
    }
    return real
}

/**
 * Fully resolve [abs]: real-path the longest existing ancestor and re-append the
 * rest, but never re-append a component that is itself a symlink — read the link
 * and continue from its target instead. Handles paths that are about to be
 * created without letting a (dangling) symlink leaf slip through unresolved.
 */
internal fun canonicalize(abs: Path): Path {
    val tail = ArrayDeque<String>()
    var prefix = abs
    var hops = 0
    while (true) {
        val real = try {
            prefix.toRealPath()
        } catch (_: IOException) {
            null
        }
        if (real != null) return tail.fold(real) { acc, name -> acc.resolve(name) }
        if (Files.isSymbolicLink(prefix)) {
            if (++hops > MAX_SYMLINK_HOPS) throw ToolError("$abs: too many levels of symbolic links")
            prefix = (prefix.parent ?: prefix.root).resolve(Files.readSymbolicLink(prefix)).normalize()
            continue
        }
        val parent = prefix.parent ?: return abs
        tail.addFirst(prefix.fileName.toString())
        prefix = parent
    }
}

private fun realPathOrSelf(p: Path): Path = try {
    p.toRealPath()
} catch (_: IOException) {
    p
}

/** Create [dir] and any missing parents with the tools' directory mode. */
internal fun createDirectories(dir: Path) {
    Files.createDirectories(dir, *posixAttributes(DIR_CREATE_MODE))
}

/**
 * Atomically write [content] (UTF-8) to [target]: write a sibling temp file,
 * fsync it, then rename it over the target, so a crash mid-write never leaves
 * the target half-written.
 */
internal fun atomicWriteFile(target: Path, content: String) {
    val temp = target.resolveSibling(".tmp-${ProcessHandle.current().pid()}-${UUID.randomUUID()}")
    try {
        FileChannel.open(
            temp,
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            *posixAttributes(FILE_CREATE_MODE),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Throwable) {
        runCatching { Files.deleteIfExists(temp) }
        throw e
    }
}

private fun posixAttributes(mode: String): Array<FileAttribute<*>> =
    if (POSIX) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))) else emptyArray()

/**
 * Map a filesystem error to a consistent, runtime-independent message (the same
 * wording the Node toolset uses), falling back to the raw message.
 */
internal fun fsErrorMessage(e: Throwable, file: String): String {
    val reason = (e as? FileSystemException)?.reason.orEmpty().lowercase()
    return when {
        e is NoSuchFileException || "no such file" in reason -> "$file: no such file or directory"
        e is AccessDeniedException || "permission denied" in reason || "not permitted" in reason ->
            "$file: permission denied"
        e is NotDirectoryException || "not a directory" in reason -> "$file: not a directory"
        "is a directory" in reason -> "$file: is a directory"
        e is FileSystemLoopException || "too many levels of symbolic links" in reason ->
            "$file: too many levels of symbolic links"
        "file name too long" in reason -> "$file: file name too long"
        "no space left" in reason -> "$file: no space left on device"
        "too many open files" in reason -> "$file: too many open files"
        e is FileAlreadyExistsException -> "$file: file already exists"
        else -> "$file: ${e.message ?: e.toString()}"
    }
}

/** Quote [s] as a JSON string literal (as `JSON.stringify` would), for error messages. */
internal fun jsonQuote(s: String): String = buildString {
    append('"')
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
