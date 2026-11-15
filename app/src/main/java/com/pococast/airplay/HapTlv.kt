package com.pococast.airplay

import java.io.ByteArrayOutputStream

/**
 * The TLV8 encoding HomeKit pairing speaks: a tag byte, a length byte, and up
 * to 255 bytes of value, repeated. A value longer than 255 bytes is sent as the
 * same tag several times and joined up again when read.
 */
object HapTlv {

    const val METHOD = 0x00
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val FLAGS = 0x13

    fun encode(vararg entries: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in entries) {
            if (value.isEmpty()) {
                out.write(tag)
                out.write(0)
                continue
            }
            var offset = 0
            while (offset < value.size) {
                val size = minOf(255, value.size - offset)
                out.write(tag)
                out.write(size)
                out.write(value, offset, size)
                offset += size
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Map<Int, ByteArray> {
        val collected = LinkedHashMap<Int, ByteArrayOutputStream>()
        var index = 0
        while (index + 1 < bytes.size) {
            val tag = bytes[index].toInt() and 0xFF
            val length = bytes[index + 1].toInt() and 0xFF
            val start = index + 2
            val end = minOf(start + length, bytes.size)
            collected.getOrPut(tag) { ByteArrayOutputStream() }.write(bytes, start, end - start)
            index = end
        }
        val out = LinkedHashMap<Int, ByteArray>()
        for ((tag, stream) in collected) out[tag] = stream.toByteArray()
        return out
    }
}
