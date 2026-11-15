package com.pococast.airplay

import java.io.ByteArrayOutputStream

/**
 * Minimal binary property list encoder.
 *
 * Apple's AirPlay video endpoints expect the play request body to be a binary
 * plist (bplist00). Only the value types needed here are supported: strings,
 * doubles and integers, inside one dictionary.
 */
object BinaryPlist {

    private const val MAX_OBJECTS = 255

    fun dict(vararg entries: Pair<String, Any>): ByteArray {
        require(entries.size < 15) { "dictionary too large" }

        val objects = ArrayList<ByteArray>()
        objects.add(ByteArray(0)) // index 0: the dictionary itself

        fun add(bytes: ByteArray): Int {
            check(objects.size < MAX_OBJECTS) { "too many plist objects" }
            objects.add(bytes)
            return objects.size - 1
        }

        val refs = IntArray(entries.size * 2)
        entries.forEachIndexed { index, entry ->
            refs[index * 2] = add(stringObject(entry.first))
            refs[index * 2 + 1] = add(valueObject(entry.second))
        }

        // A dictionary object holds every key reference first, then every value
        // reference, so the two halves must not be interleaved.
        val dict = ByteArrayOutputStream()
        dict.write(0xD0 or entries.size)
        for (i in entries.indices) dict.write(refs[i * 2] and 0xFF)
        for (i in entries.indices) dict.write(refs[i * 2 + 1] and 0xFF)
        objects[0] = dict.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))

        val offsets = IntArray(objects.size)
        for (i in objects.indices) {
            offsets[i] = out.size()
            out.write(objects[i])
        }
        val offsetTableOffset = out.size()

        val offsetSize = when {
            offsetTableOffset < 0x100 -> 1
            offsetTableOffset < 0x10000 -> 2
            else -> 4
        }
        for (offset in offsets) writeSized(out, offset.toLong(), offsetSize)

        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(offsetSize)
        out.write(1) // object reference size
        writeLong(out, objects.size.toLong())
        writeLong(out, 0L) // top object
        writeLong(out, offsetTableOffset.toLong())

        return out.toByteArray()
    }

    private fun stringObject(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        if (bytes.size < 15) {
            out.write(0x50 or bytes.size)
        } else {
            out.write(0x5F)
            writeInt(out, bytes.size)
        }
        out.write(bytes, 0, bytes.size)
        return out.toByteArray()
    }

    private fun valueObject(value: Any): ByteArray = when (value) {
        is Double -> doubleObject(value)
        is Int -> ByteArrayOutputStream().also { writeInt(it, value) }.toByteArray()
        is Long -> ByteArrayOutputStream().also { writeInt(it, value.toInt()) }.toByteArray()
        else -> stringObject(value.toString())
    }

    private fun doubleObject(value: Double): ByteArray {
        val bits = value.toRawBits()
        val out = ByteArrayOutputStream()
        out.write(0x23)
        for (shift in SHIFTS) out.write(((bits ushr shift) and 0xFF).toInt())
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        when {
            value < 0x100 -> {
                out.write(0x10)
                out.write(value and 0xFF)
            }
            value < 0x10000 -> {
                out.write(0x11)
                out.write((value ushr 8) and 0xFF)
                out.write(value and 0xFF)
            }
            else -> {
                out.write(0x12)
                out.write((value ushr 24) and 0xFF)
                out.write((value ushr 16) and 0xFF)
                out.write((value ushr 8) and 0xFF)
                out.write(value and 0xFF)
            }
        }
    }

    private fun writeLong(out: ByteArrayOutputStream, value: Long) {
        for (shift in SHIFTS) out.write(((value ushr shift) and 0xFF).toInt())
    }

    private fun writeSized(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write(((value ushr (i * 8)) and 0xFF).toInt())
    }

    private val SHIFTS = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)
}
