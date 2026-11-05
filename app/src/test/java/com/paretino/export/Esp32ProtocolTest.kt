package com.paretino.export

import com.paretino.domain.HoldRole
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Esp32ProtocolTest {
    private val dto = PathExportDto(
        wall = WallDto("Garage", 400, 300), master = "Set 1", title = "Crimpy", description = "", grade = "6B",
        setter = "Fede", rules = "Feet follow hands",
        holds = listOf(
            HoldDto(HoldRole.START, "#2FB36B", led = 3, outline = listOf(0f, 0f, 1f, 0f, 1f, 1f)),
            HoldDto(HoldRole.HAND, "#2E7DF6", led = 300, outline = listOf(0f, 0f, 1f, 0f, 1f, 1f)),
            HoldDto(HoldRole.FINISH, "#E5484D", led = null, outline = listOf(0f, 0f, 1f, 0f, 1f, 1f)),
        ),
    )

    @Test fun crcMatchesReferenceVector() {
        // CRC-16/CCITT-FALSE check value for "123456789".
        assertEquals(0x29B1, Esp32Protocol.crc16("123456789".toByteArray()))
    }

    @Test fun encodeSkipsUnwiredHoldsAndRoundTrips() {
        val frame = Esp32Protocol.encode(dto)
        assertEquals(8 + 2 * 6, frame.size)
        val decoded = Esp32Protocol.decode(frame)!!
        assertEquals(Esp32Protocol.CMD_SHOW, decoded.command)
        assertEquals(listOf(Triple(3, 0x2FB36B, HoldRole.START), Triple(300, 0x2E7DF6, HoldRole.HAND)), decoded.holds)
    }

    @Test fun headerLayout() {
        val frame = Esp32Protocol.encode(dto)
        assertArrayEquals(byteArrayOf('P'.code.toByte(), 'W'.code.toByte(), 1, 1, 2, 0), frame.copyOfRange(0, 6))
        // LED 300 little endian
        assertArrayEquals(byteArrayOf(0x2C, 0x01), frame.copyOfRange(12, 14))
    }

    @Test fun corruptedFrameIsRejected() {
        val frame = Esp32Protocol.encode(dto)
        frame[7] = (frame[7] + 1).toByte()
        assertNull(Esp32Protocol.decode(frame))
    }

    @Test fun clearFrame() {
        val d = Esp32Protocol.decode(Esp32Protocol.encodeClear())!!
        assertEquals(Esp32Protocol.CMD_CLEAR, d.command)
        assertEquals(0, d.holds.size)
    }

    @Test fun jsonRoundTrip() {
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(PathExportDto.serializer(), dto)
        assertEquals(dto, json.decodeFromString(PathExportDto.serializer(), text))
        assertEquals(true, text.contains("\"schemaVersion\":1"))
    }
}
