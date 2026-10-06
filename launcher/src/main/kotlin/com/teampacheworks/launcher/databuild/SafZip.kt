package com.teampacheworks.launcher.databuild

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** One file inside a [SafZip]. [name] is forward-slashed; directories are not listed. */
class SafZipEntry(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    internal val localHeaderOffset: Long
)

/**
 * Random-access reader for a `.zip` the player picked through the document UI.
 *
 * `java.util.zip.ZipFile` wants a path, and a picked document has none the app may open;
 * `ZipInputStream` has no index, so merely listing a gigabyte archive means reading all of it. This
 * reads the central directory through the document's seekable file descriptor instead -- listing is
 * a few kilobytes of I/O whatever the archive size -- and streams one entry at a time.
 *
 * Supports STORED and DEFLATED entries and Zip64 sizes/offsets. Entry names containing a `..`
 * segment or an absolute path are dropped, so a caller joining a name to its install root cannot be
 * steered outside it.
 */
class SafZip(context: Context, uri: Uri) : Closeable {

    private val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("could not open $uri")
    private val channel: FileChannel = FileInputStream(pfd.fileDescriptor).channel

    /** Every file entry, in central-directory order. */
    val entries: List<SafZipEntry> by lazy { readCentralDirectory() }

    /** Uncompressed bytes of [entry]. Close it; the archive stays open for the next entry. */
    fun open(entry: SafZipEntry): InputStream {
        val header = read(entry.localHeaderOffset, 30)
        if (header.getInt(0) != LOCAL_SIG) throw IOException("bad local header for ${entry.name}")
        val nameLen = header.getShort(26).toInt() and 0xFFFF
        val extraLen = header.getShort(28).toInt() and 0xFFFF
        val raw = ChannelSlice(entry.localHeaderOffset + 30 + nameLen + extraLen, entry.compressedSize)
        return when (entry.method) {
            STORED -> raw
            // Inflater(nowrap) may want one byte past the deflate stream to report the end.
            DEFLATED -> InflaterInputStream(
                SequenceInputStream(raw, ByteArrayInputStream(ByteArray(1))), Inflater(true), 1 shl 16
            )
            else -> throw IOException("${entry.name}: unsupported compression method ${entry.method}")
        }
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }

    private fun read(position: Long, length: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var at = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n < 0) throw IOException("unexpected end of archive")
            at += n
        }
        buffer.flip()
        return buffer
    }

    private fun readCentralDirectory(): List<SafZipEntry> {
        val fileSize = channel.size()
        val tailLength = minOf(fileSize, 22L + 0xFFFF).toInt()
        val tail = read(fileSize - tailLength, tailLength)
        var eocd = -1
        for (i in tailLength - 22 downTo 0) {
            if (tail.getInt(i) == EOCD_SIG) { eocd = i; break }
        }
        if (eocd < 0) throw IOException("not a zip archive")
        var count = (tail.getShort(eocd + 10).toInt() and 0xFFFF).toLong()
        var cdSize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val locatorAt = fileSize - tailLength + eocd - 20
            if (locatorAt >= 0) {
                val locator = read(locatorAt, 20)
                if (locator.getInt(0) == ZIP64_LOCATOR_SIG) {
                    val record = read(locator.getLong(8), 56)
                    if (record.getInt(0) == ZIP64_EOCD_SIG) {
                        count = record.getLong(32)
                        cdSize = record.getLong(40)
                        cdOffset = record.getLong(48)
                    }
                }
            }
        }
        if (cdSize > Int.MAX_VALUE) throw IOException("central directory too large")
        val cd = read(cdOffset, cdSize.toInt())
        val out = ArrayList<SafZipEntry>(count.toInt().coerceAtLeast(0))
        var p = 0
        while (p + 46 <= cd.limit() && cd.getInt(p) == CENTRAL_SIG) {
            val method = cd.getShort(p + 10).toInt() and 0xFFFF
            var compressed = cd.getInt(p + 20).toLong() and 0xFFFFFFFFL
            var size = cd.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = cd.getShort(p + 32).toInt() and 0xFFFF
            var offset = cd.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val nameBytes = ByteArray(nameLen).also { cd.position(p + 46); cd.get(it) }
            // Zip64 extra: only the fields whose 32-bit slot is saturated are present, in order.
            var e = p + 46 + nameLen
            val extraEnd = e + extraLen
            while (e + 4 <= extraEnd) {
                val id = cd.getShort(e).toInt() and 0xFFFF
                val len = cd.getShort(e + 2).toInt() and 0xFFFF
                if (id == 0x0001) {
                    var f = e + 4
                    if (size == 0xFFFFFFFFL) { size = cd.getLong(f); f += 8 }
                    if (compressed == 0xFFFFFFFFL) { compressed = cd.getLong(f); f += 8 }
                    if (offset == 0xFFFFFFFFL) { offset = cd.getLong(f) }
                }
                e += 4 + len
            }
            p += 46 + nameLen + extraLen + commentLen

            val name = String(nameBytes, Charsets.UTF_8).replace('\\', '/')
            if (name.endsWith("/")) continue
            val segments = name.split('/')
            if (name.startsWith("/") || segments.any { it == ".." }) continue
            out += SafZipEntry(segments.filter { it.isNotEmpty() && it != "." }.joinToString("/"),
                method, compressed, size, offset)
        }
        return out
    }

    /** [remaining] bytes of the archive from [position], read on demand. */
    private inner class ChannelSlice(private var position: Long, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val want = minOf(len.toLong(), remaining).toInt()
            val n = channel.read(ByteBuffer.wrap(b, off, want), position)
            if (n < 0) throw IOException("unexpected end of archive")
            position += n
            remaining -= n
            return n
        }
    }

    private companion object {
        const val STORED = 0
        const val DEFLATED = 8
        const val LOCAL_SIG = 0x04034b50
        const val CENTRAL_SIG = 0x02014b50
        const val EOCD_SIG = 0x06054b50
        const val ZIP64_LOCATOR_SIG = 0x07064b50
        const val ZIP64_EOCD_SIG = 0x06064b50
    }
}
