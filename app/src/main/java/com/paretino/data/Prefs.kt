package com.paretino.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.paretino.domain.GradeScale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

@Singleton
class Prefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val store get() = context.dataStore

    val activeClimberId: Flow<Long> = store.data.map { it[ACTIVE_CLIMBER] ?: 0L }
    val gradeScale: Flow<GradeScale> = store.data.map { p ->
        p[GRADE_SCALE]?.let { runCatching { GradeScale.valueOf(it) }.getOrNull() } ?: GradeScale.FONT
    }
    /** Hold detection sensitivity, 0.5 (fewer, surer holds) to 1.5 (more, fainter holds). */
    val sensitivity: Flow<Float> = store.data.map { it[SENSITIVITY] ?: 1f }

    suspend fun activeClimber() = activeClimberId.first()

    suspend fun setActiveClimber(id: Long) = store.edit { it[ACTIVE_CLIMBER] = id }
    suspend fun setGradeScale(scale: GradeScale) = store.edit { it[GRADE_SCALE] = scale.name }
    suspend fun setSensitivity(v: Float) = store.edit { it[SENSITIVITY] = v }

    private companion object {
        val ACTIVE_CLIMBER = longPreferencesKey("active_climber")
        val GRADE_SCALE = stringPreferencesKey("grade_scale")
        val SENSITIVITY = floatPreferencesKey("sensitivity")
    }
}
