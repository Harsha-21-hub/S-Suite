package com.hesi.snotes

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Minimal allocation-free JSON text writer used for note autosave.
 *
 * Numbers are formatted by hand into a reusable byte buffer (the output is pure
 * ASCII apart from what [raw] passes in), avoiding String.format / Double.toString
 * and the per-call locking of java.io.Writer.
 */
class FastJsonOut(file: File) : Closeable {

    private val out: OutputStream = FileOutputStream(file)
    private val buf = ByteArray(1 shl 16)
    private var n = 0
    private val digits = ByteArray(24)

    private fun ensure(k: Int) {
        if (n + k > buf.size) flush()
    }

    private fun flush() {
        if (n > 0) { out.write(buf, 0, n); n = 0 }
    }

    fun raw(s: String) {
        val bytes = if (s.length <= 64 && s.all { it.code < 128 }) null else s.toByteArray(Charsets.UTF_8)
        if (bytes != null) {
            ensure(bytes.size)
            if (bytes.size > buf.size) { flush(); out.write(bytes); return }
            System.arraycopy(bytes, 0, buf, n, bytes.size); n += bytes.size
            return
        }
        ensure(s.length)
        for (c in s) buf[n++] = c.code.toByte()
    }

    fun int(v: Int) = long(v.toLong())

    fun long(v0: Long) {
        ensure(22)
        var v = v0
        if (v == 0L) { buf[n++] = '0'.code.toByte(); return }
        if (v < 0) {
            buf[n++] = '-'.code.toByte()
            if (v == Long.MIN_VALUE) { raw("9223372036854775808"); return }
            v = -v
        }
        var k = 0
        while (v > 0) { digits[k++] = ('0'.code + (v % 10).toInt()).toByte(); v /= 10 }
        while (k > 0) buf[n++] = digits[--k]
    }

    /** Fixed-point with up to [decimals] digits, trailing zeros trimmed. */
    fun fixed(v: Float, decimals: Int) {
        if (v.isNaN() || v.isInfinite()) { raw("0"); return }
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        var scaled = Math.round(v.toDouble() * scale)
        if (scaled < 0) { ensure(1); buf[n++] = '-'.code.toByte(); scaled = -scaled }
        long(scaled / scale)
        var frac = scaled % scale
        if (frac == 0L) return
        // emit the fraction with leading zeros, then trim trailing zeros
        var d = decimals
        while (frac % 10 == 0L) { frac /= 10; d-- }
        ensure(decimals + 1)
        buf[n++] = '.'.code.toByte()
        var k = 0
        while (k < d) { digits[k++] = ('0'.code + (frac % 10).toInt()).toByte(); frac /= 10 }
        while (k > 0) buf[n++] = digits[--k]
    }

    override fun close() {
        try { flush() } finally { out.close() }
    }
}
