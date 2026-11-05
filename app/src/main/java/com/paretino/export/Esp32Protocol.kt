package com.paretino.export

import com.paretino.domain.HoldRole

/**
 * Binary frame for the ESP32 wall controller, small enough for a few BLE writes.
 *
 * ```
 * offset size
 * 0      2    magic "PW"
 * 2      1    version (1)
 * 3      1    command: 1 = show path, 2 = clear wall
 * 4      2    hold count N (little endian)
 * 6      6*N  per hold: LED index u16 LE, R, G, B, role (HoldRole ordinal)
 * 6+6N   2    CRC-16/CCITT-FALSE of everything before, little endian
 * ```
 * Holds without an LED (anchor not wired) are left out.
 */
object Esp32Protocol {
    const val VERSION = 1
    const val CMD_SHOW = 1
    const val CMD_CLEAR = 2

    fun encode(dto: PathExportDto): ByteArray {
        val holds = dto.holds.filter { it.led != null }
        val out = ArrayList<Byte>(8 + holds.size * 6)
        out += 'P'.code.toByte(); out += 'W'.code.toByte()
        out += VERSION.toByte(); out += CMD_SHOW.toByte()
        out.addU16(holds.size)
        for (h in holds) {
            out.addU16(h.led!!)
            val rgb = h.color.removePrefix("#").toInt(16)
            out += (rgb shr 16).toByte(); out += (rgb shr 8).toByte(); out += rgb.toByte()
            out += h.role.ordinal.toByte()
        }
        out.addU16(crc16(out.toByteArray()))
        return out.toByteArray()
    }

    fun encodeClear(): ByteArray {
        val out = arrayListOf('P'.code.toByte(), 'W'.code.toByte(), VERSION.toByte(), CMD_CLEAR.toByte(), 0, 0)
        out.addU16(crc16(out.toByteArray()))
        return out.toByteArray()
    }

    data class Decoded(val command: Int, val holds: List<Triple<Int, Int, HoldRole>>)

    /** Inverse of [encode], for tests and a future firmware simulator. Null when the frame is invalid. */
    fun decode(frame: ByteArray): Decoded? {
        if (frame.size < 8 || frame[0] != 'P'.code.toByte() || frame[1] != 'W'.code.toByte()) return null
        val body = frame.copyOfRange(0, frame.size - 2)
        if (u16(frame, frame.size - 2) != crc16(body)) return null
        val n = u16(frame, 4)
        if (frame.size != 8 + 6 * n) return null
        val holds = List(n) { i ->
            val o = 6 + 6 * i
            val rgb = ((frame[o + 2].toInt() and 0xFF) shl 16) or ((frame[o + 3].toInt() and 0xFF) shl 8) or (frame[o + 4].toInt() and 0xFF)
            Triple(u16(frame, o), rgb, HoldRole.entries[frame[o + 5].toInt()])
        }
        return Decoded(frame[3].toInt(), holds)
    }

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun MutableList<Byte>.addU16(v: Int) { add((v and 0xFF).toByte()); add((v shr 8 and 0xFF).toByte()) }
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
