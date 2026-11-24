package com.intersprit.camlink

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Single-viewer, bounded low-latency H.264 byte-stream bridge.
 *
 * MediaCodec access units are normalized to Annex-B start-code framing. The queue
 * intentionally drops the oldest encoded access unit when the consumer falls behind.
 */
object H264StreamHub {
    private const val MAX_QUEUED_ACCESS_UNITS = 6
    private val accessUnits = LinkedBlockingDeque<ByteArray>(MAX_QUEUED_ACCESS_UNITS)

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var codecConfig: ByteArray? = null

    fun start() {
        accessUnits.clear()
        codecConfig = null
        enabled = true
    }

    fun stop() {
        enabled = false
        accessUnits.clear()
        codecConfig = null
    }

    fun publishCodecConfig(vararg buffers: ByteArray?) {
        val output = ByteArrayOutputStream()
        buffers.filterNotNull().forEach { bytes ->
            if (bytes.isNotEmpty()) output.write(toAnnexB(bytes))
        }
        val config = output.toByteArray()
        if (config.isNotEmpty()) codecConfig = config
    }

    fun publishAccessUnit(bytes: ByteArray) {
        if (!enabled || bytes.isEmpty()) return
        val annexB = toAnnexB(bytes)
        if (annexB.isEmpty()) return
        if (!accessUnits.offerLast(annexB)) {
            accessUnits.pollFirst()
            accessUnits.offerLast(annexB)
        }
    }

    fun openStream(): InputStream = object : InputStream() {
        private var current = ByteArray(0)
        private var offset = 0
        private var sentConfig = false

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > buffer.size - off) {
                throw IndexOutOfBoundsException()
            }
            if (len == 0) return 0

            while (offset >= current.size) {
                if (!enabled) return -1

                if (!sentConfig) {
                    val config = codecConfig
                    if (config != null) {
                        current = config
                        offset = 0
                        sentConfig = true
                        continue
                    }
                }

                val next = try {
                    accessUnits.pollFirst(250, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                if (next != null) {
                    current = next
                    offset = 0
                }
            }

            val count = minOf(len, current.size - offset)
            System.arraycopy(current, offset, buffer, off, count)
            offset += count
            return count
        }
    }

    private fun hasAnnexBStartCode(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            (bytes[2] == 1.toByte() || (bytes.size >= 4 && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))

    /**
     * MediaCodec devices commonly expose Annex-B NAL units, but some expose
     * length-prefixed AVC samples. Convert valid 4-byte length-prefixed samples.
     */
    private fun toAnnexB(bytes: ByteArray): ByteArray {
        if (hasAnnexBStartCode(bytes)) return bytes

        val converted = ByteArrayOutputStream(bytes.size + 32)
        var cursor = 0
        var validLengthPrefixed = true
        while (cursor + 4 <= bytes.size) {
            val length = ((bytes[cursor].toInt() and 0xff) shl 24) or
                ((bytes[cursor + 1].toInt() and 0xff) shl 16) or
                ((bytes[cursor + 2].toInt() and 0xff) shl 8) or
                (bytes[cursor + 3].toInt() and 0xff)
            cursor += 4
            if (length <= 0 || length > bytes.size - cursor) {
                validLengthPrefixed = false
                break
            }
            converted.write(byteArrayOf(0, 0, 0, 1))
            converted.write(bytes, cursor, length)
            cursor += length
        }

        if (validLengthPrefixed && cursor == bytes.size && converted.size() > 0) {
            return converted.toByteArray()
        }

        return byteArrayOf(0, 0, 0, 1) + bytes
    }
}
