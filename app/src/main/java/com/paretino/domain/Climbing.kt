package com.paretino.domain

/** Grades are stored as an index into [Grades.FONT]; the V scale is a display conversion. */
enum class GradeScale { FONT, V }

object Grades {
    val FONT = listOf(
        "3", "4", "4+", "5", "5+", "6A", "6A+", "6B", "6B+", "6C", "6C+",
        "7A", "7A+", "7B", "7B+", "7C", "7C+", "8A", "8A+", "8B", "8B+", "8C", "8C+", "9A",
    )
    private val V = listOf(
        "VB", "V0", "V0", "V1", "V2", "V3", "V3", "V4", "V4", "V5", "V5",
        "V6", "V7", "V8", "V8", "V9", "V10", "V11", "V12", "V13", "V14", "V15", "V16", "V17",
    )

    fun label(index: Int?, scale: GradeScale): String = when {
        index == null || index !in FONT.indices -> "?"
        scale == GradeScale.FONT -> FONT[index]
        else -> V[index]
    }

    /** The grade-index range shown in pickers for [scale] (V merges some Font grades). */
    fun options(scale: GradeScale): List<Pair<Int, String>> = when (scale) {
        GradeScale.FONT -> FONT.mapIndexed { i, g -> i to g }
        GradeScale.V -> V.mapIndexed { i, g -> i to g }.distinctBy { it.second }
    }
}

enum class HoldRole(val label: String, val defaultColor: Long) {
    START("Start", 0xFF2FB36B),
    HAND("Hand", 0xFF2E7DF6),
    FOOT("Foot", 0xFFF5B400),
    FINISH("Finish", 0xFFE5484D);

    val tag: String? get() = when (this) {
        START -> "START"
        FINISH -> "FINISH"
        else -> null
    }
}

enum class AscentStyle(val label: String) {
    FLASH("Flash"),
    SEND("Send"),
    REPEAT("Repeat"),
    ATTEMPT("Attempt");

    val isSend get() = this != ATTEMPT
}

object PathRules {
    val PRESETS = listOf("Feet follow hands", "Open feet", "No matching", "Campus only", "Start sitting")
}
