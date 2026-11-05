package com.paretino.export

import com.paretino.domain.HoldRole
import kotlinx.serialization.Serializable

/**
 * A path as it leaves the app (share sheet today, ESP32 tomorrow). Versioned so the firmware and
 * other apps can rely on it.
 */
@Serializable
data class PathExportDto(
    val schemaVersion: Int = SCHEMA_VERSION,
    val app: String = "Paretino",
    val wall: WallDto,
    val master: String,
    val title: String,
    val description: String,
    val grade: String?,
    val setter: String,
    val rules: String,
    val holds: List<HoldDto>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class WallDto(val name: String, val widthCm: Int, val heightCm: Int)

@Serializable
data class HoldDto(
    val role: HoldRole,
    /** "#RRGGBB". */
    val color: String,
    val label: String = "",
    /** LED wired to the hold's anchor, if positions are set up. */
    val led: Int? = null,
    /** Anchor label, e.g. "C7". */
    val position: String? = null,
    /** Outline in wall space, 0..1, as [x0, y0, x1, y1, ...]. */
    val outline: List<Float>,
)

fun colorHex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
