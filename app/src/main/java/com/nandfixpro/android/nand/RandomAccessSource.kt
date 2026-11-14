package com.nandfixpro.android.nand

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Read-only random access abstraction shared by local files, split NAND sets and
 * USB mass storage sessions. Every NAND parser works against this interface so the
 * same code can analyse a `RAWNAND.bin` on disk and a live Hekate target.
 */
interface RandomAccessSource : Closeable {
    val size: Long
    val label: String

    /**
     * Reads exactly [length] bytes at [offset]. Implementations throw when the
     * request would cross the end of the source instead of returning partial data,
     * which keeps callers from analysing truncated structures silently.
     */
    fun read(offset: Long, length: Int): ByteArray
}

class FileSource(file: File) : RandomAccessSource {
    private val raf = RandomAccessFile(file, "r")

    override val size: Long get() = raf.length()
    override val label: String = file.name

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "Negative offset" }
        require(length >= 0) { "Negative length" }
        if (offset + length > size) {
            throw IndexOutOfBoundsException(
                "Read of $length bytes at 0x${offset.toString(16)} exceeds ${label} (${size} bytes)",
            )
        }
        val buffer = ByteArray(length)
        synchronized(raf) {
            raf.seek(offset)
            raf.readFully(buffer)
        }
        return buffer
    }

    override fun close() = raf.close()
}

/**
 * Random access over a local file whose announced size may differ from its real
 * length (for example a file that is being copied). Bounds are checked against the
 * real length to protect against truncated NAND dumps.
 */
class ChannelSource(
    private val channel: FileChannel,
    override val size: Long,
    override val label: String,
    private val closeChannel: Boolean = true,
) : RandomAccessSource {

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "Invalid read window" }
        if (offset + length > size) {
            throw IndexOutOfBoundsException(
                "Read of $length bytes at 0x${offset.toString(16)} exceeds $label ($size bytes)",
            )
        }
        val buffer = ByteArray(length)
        val byteBuffer = ByteBuffer.wrap(buffer)
        var position = offset
        while (byteBuffer.hasRemaining()) {
            val read = channel.read(byteBuffer, position)
            if (read <= 0) {
                throw java.io.EOFException("Unexpected end of $label at 0x${position.toString(16)}")
            }
            position += read
        }
        return buffer
    }

    override fun close() {
        if (closeChannel) channel.close()
    }
}

/**
 * Presents several split files as one contiguous source, mirroring how Hekate
 * (rawnand.bin.NN) and emuMMC (00, 01, ...) split a raw GPP image.
 */
class SplitFileSource(
    private val parts: List<Part>,
    override val label: String,
) : RandomAccessSource {

    class Part(val file: File, val startOffset: Long) {
        val length: Long = file.length()
    }

    private val handles: List<RandomAccessFile> = parts.map { RandomAccessFile(it.file, "r") }

    override val size: Long = parts.sumOf { it.length }

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0) throw IllegalArgumentException("Invalid read window")
        if (offset + length > size) {
            throw IndexOutOfBoundsException(
                "Read of $length bytes at 0x${offset.toString(16)} exceeds split set ($size bytes)",
            )
        }
        val result = ByteArray(length)
        var written = 0
        var cursor = offset
        while (written < length) {
            val index = partIndexFor(cursor)
            val part = parts[index]
            val inside = cursor - part.startOffset
            val available = (part.length - inside).coerceAtMost((length - written).toLong()).toInt()
            val handle = handles[index]
            synchronized(handle) {
                handle.seek(inside)
                handle.readFully(result, written, available)
            }
            written += available
            cursor += available
        }
        return result
    }

    private fun partIndexFor(offset: Long): Int {
        for (i in parts.indices.reversed()) {
            if (offset >= parts[i].startOffset) return i
        }
        return 0
    }

    override fun close() {
        handles.forEach { runCatching { it.close() } }
    }

    companion object {
        fun fromFiles(files: List<File>, label: String): SplitFileSource {
            var cursor = 0L
            val parts = files.map { file ->
                Part(file, cursor).also { cursor += it.length }
            }
            return SplitFileSource(parts, label)
        }
    }
}

/** Restricts another source to a byte range without copying it. */
class SliceSource(
    private val parent: RandomAccessSource,
    private val start: Long,
    override val size: Long,
    override val label: String,
) : RandomAccessSource {

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw IndexOutOfBoundsException("Slice read outside $label")
        }
        return parent.read(start + offset, length)
    }

    override fun close() {
        // Slice shares the parent lifetime.
    }
}

/**
 * Concatenates independent sources (for example several SAF documents) into one
 * seamless read-only image.
 */
class MultiSource(
    private val parts: List<RandomAccessSource>,
    override val label: String,
) : RandomAccessSource {

    private val offsets: LongArray
    override val size: Long

    init {
        val starts = LongArray(parts.size)
        var cursor = 0L
        parts.forEachIndexed { index, part ->
            starts[index] = cursor
            cursor += part.size
        }
        offsets = starts
        size = cursor
    }

    private fun partIndexFor(offset: Long): Int {
        for (index in parts.indices.reversed()) {
            if (offset >= offsets[index]) return index
        }
        return 0
    }

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw IndexOutOfBoundsException("Lecture hors du jeu de sources ($label)")
        }
        val result = ByteArray(length)
        var written = 0
        var cursor = offset
        while (written < length) {
            val index = partIndexFor(cursor)
            val part = parts[index]
            val inside = cursor - offsets[index]
            val available = minOf((part.size - inside).toInt(), length - written)
            if (available <= 0) break
            val chunk = part.read(inside, available)
            chunk.copyInto(result, written)
            written += chunk.size
            cursor += chunk.size
        }
        return result
    }

    override fun close() {
        parts.forEach { runCatching { it.close() } }
    }
}

/** In-memory source used by unit tests and the in-app diagnostics self-test. */
class ByteArraySource(
    private val bytes: ByteArray,
    override val label: String = "memory",
) : RandomAccessSource {
    override val size: Long = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw IndexOutOfBoundsException("Read outside $label")
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    override fun close() = Unit
}
