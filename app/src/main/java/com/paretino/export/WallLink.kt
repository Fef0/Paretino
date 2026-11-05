package com.paretino.export

import javax.inject.Inject
import javax.inject.Singleton

/** Sends a path to the physical wall (LEDs behind each anchor). */
interface WallLinkExporter {
    /** False until a controller is paired and reachable. */
    val isAvailable: Boolean

    /** Lights the path on the wall. */
    suspend fun show(path: PathExportDto): Result<Unit>

    /** Turns every LED off. */
    suspend fun clear(): Result<Unit>
}

/**
 * Placeholder for the ESP32 BLE link. The real one will scan for [SERVICE_UUID], connect, and write
 * [Esp32Protocol] frames to [FRAME_CHARACTERISTIC_UUID] in MTU-sized chunks.
 */
@Singleton
class BleEsp32Exporter @Inject constructor() : WallLinkExporter {
    override val isAvailable: Boolean = false

    override suspend fun show(path: PathExportDto): Result<Unit> =
        Result.failure(UnsupportedOperationException("The wall controller is not connected yet (${Esp32Protocol.encode(path).size}-byte frame ready)"))

    override suspend fun clear(): Result<Unit> =
        Result.failure(UnsupportedOperationException("The wall controller is not connected yet"))

    companion object {
        const val SERVICE_UUID = "6b1a0001-5061-7265-7469-6e6f00000000"
        const val FRAME_CHARACTERISTIC_UUID = "6b1a0002-5061-7265-7469-6e6f00000000"
    }
}
