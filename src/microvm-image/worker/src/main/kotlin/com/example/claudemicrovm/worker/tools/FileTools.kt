package com.example.claudemicrovm.worker.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Stat [abs] before opening it: the size cap stops a huge file from exhausting
 * memory, and the regular-file check rejects FIFOs, devices and directories
 * without opening them (opening an unconnected FIFO blocks indefinitely).
 */
private fun readBoundedText(tool: String, ctx: AgentToolContext, abs: Path, filePath: String, hint: String): String {
    try {
        val attrs = Files.readAttributes(abs, BasicFileAttributes::class.java)
        if (!attrs.isRegularFile) throw ToolError("$tool: $filePath is not a regular file")
        val limit = ctx.maxFileBytes
        if (limit != null && attrs.size() > limit) {
            throw ToolError("$tool: $filePath is ${attrs.size()} bytes, exceeds $limit-byte limit. $hint")
        }
        // Malformed UTF-8 is replaced with U+FFFD rather than failing the read.
        return String(Files.readAllBytes(abs), Charsets.UTF_8)
    } catch (e: ToolError) {
        throw e
    } catch (e: Exception) {
        throw ToolError("$tool: ${fsErrorMessage(e, filePath)}")
    }
}

/** `read`: read a UTF-8 text file relative to the workdir, optionally a 1-indexed line range. */
class ReadTool(private val ctx: AgentToolContext) : AgentTool {
    override val name = "read"

    override suspend fun run(input: ToolInput): String = withContext(Dispatchers.IO) {
        val filePath = input.string("file_path")
        if (filePath.isNullOrEmpty()) throw ToolError("read: file_path is required")
        val abs = resolvePath(ctx, filePath)
        val data = readBoundedText(name, ctx, abs, filePath, "Use bash (head/tail/sed) to read a slice.")
        val viewRange = input.values["view_range"] ?: return@withContext data
        val bounds = (viewRange as? List<*>)?.map { (it as? Number)?.toLong() }
        if (bounds == null || bounds.size != 2 || bounds.any { it == null }) {
            throw ToolError("read: view_range must be [start_line, end_line]")
        }
        val (startLine, endLine) = bounds.map { it!! }
        val lines = data.split("\n")
        val start = maxOf(0L, startLine - 1).coerceAtMost(lines.size.toLong()).toInt()
        val end = (if (endLine > 0) endLine else lines.size.toLong()).coerceAtMost(lines.size.toLong()).toInt()
        if (end <= start) "" else lines.subList(start, end).joinToString("\n")
    }
}

/** `write`: write a UTF-8 text file relative to the workdir, creating parent directories as needed. */
class WriteTool(private val ctx: AgentToolContext) : AgentTool {
    override val name = "write"

    override suspend fun run(input: ToolInput): String = withContext(Dispatchers.IO) {
        val filePath = input.string("file_path")
        if (filePath.isNullOrEmpty()) throw ToolError("write: file_path is required")
        val content = input.string("content").orEmpty()
        val abs = resolvePath(ctx, filePath)
        try {
            abs.parent?.let(::createDirectories)
            atomicWriteFile(abs, content)
        } catch (e: Exception) {
            throw ToolError("write: ${fsErrorMessage(e, filePath)}")
        }
        "wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $filePath"
    }
}

/** `edit`: replace `old_string` with `new_string` in a file; `old_string` must be unique unless `replace_all`. */
class EditTool(private val ctx: AgentToolContext) : AgentTool {
    override val name = "edit"

    override suspend fun run(input: ToolInput): String = withContext(Dispatchers.IO) {
        val filePath = input.string("file_path")
        if (filePath.isNullOrEmpty()) throw ToolError("edit: file_path is required")
        val oldString = input.string("old_string")
        if (oldString.isNullOrEmpty()) throw ToolError("edit: old_string is required")
        val newString = input.string("new_string").orEmpty()
        val replaceAll = input.truthy("replace_all")
        val abs = resolvePath(ctx, filePath)
        val data = readBoundedText(name, ctx, abs, filePath, "Use bash (sed/awk) to edit a large file.")
        val count = countOccurrences(data, oldString)
        if (count == 0) throw ToolError("edit: old_string not found in $filePath")
        val updated = if (replaceAll) {
            data.replace(oldString, newString)
        } else {
            if (count > 1) throw ToolError("edit: old_string appears $count times in $filePath (must be unique)")
            data.replaceFirst(oldString, newString)
        }
        try {
            atomicWriteFile(abs, updated)
        } catch (e: Exception) {
            throw ToolError("edit: write: ${fsErrorMessage(e, filePath)}")
        }
        "edited $filePath (${if (replaceAll) count else 1} replacement(s))"
    }
}

/** Number of non-overlapping occurrences of [needle] in [haystack], scanning left to right. */
internal fun countOccurrences(haystack: String, needle: String): Int {
    var count = 0
    var index = haystack.indexOf(needle)
    while (index >= 0) {
        count++
        index = haystack.indexOf(needle, index + needle.length)
    }
    return count
}
