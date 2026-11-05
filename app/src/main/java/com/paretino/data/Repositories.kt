package com.paretino.data

import android.graphics.Bitmap
import com.paretino.data.db.AscentEntity
import com.paretino.data.db.ClimberDao
import com.paretino.data.db.ClimberEntity
import com.paretino.data.db.FavoriteEntity
import com.paretino.data.db.MasterDao
import com.paretino.data.db.MasterEntity
import com.paretino.data.db.MasterHoldEntity
import com.paretino.data.db.PathDao
import com.paretino.data.db.PathEntity
import com.paretino.data.db.PathHoldEntity
import com.paretino.data.db.PositionEntity
import com.paretino.data.db.RatingEntity
import com.paretino.data.db.WallDao
import com.paretino.data.db.WallEntity
import com.paretino.domain.Anchor
import com.paretino.domain.AscentStyle
import com.paretino.domain.GradeScale
import com.paretino.domain.Grades
import com.paretino.domain.NPoint
import com.paretino.domain.Polygon
import com.paretino.domain.PositionSnapper
import com.paretino.export.HoldDto
import com.paretino.export.PathExportDto
import com.paretino.export.WallDto
import com.paretino.export.colorHex
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A hold being edited before it is saved: [id] 0 for new ones. */
data class EditableHold(val id: Long, val outline: Polygon, val colorArgb: Int, val manual: Boolean)

@Singleton
class WallRepository @Inject constructor(
    private val walls: WallDao,
    private val climbers: ClimberDao,
    private val prefs: Prefs,
) {
    val wall = walls.wall()

    /** First-run set-up: the wall and the first climber (who becomes the active one). */
    suspend fun setUp(name: String, widthCm: Int, heightCm: Int, climberName: String) {
        walls.insert(WallEntity(name = name, widthCm = widthCm, heightCm = heightCm))
        val id = climbers.insert(ClimberEntity(name = climberName, colorArgb = CLIMBER_COLORS[0]))
        prefs.setActiveClimber(id)
    }

    suspend fun update(wall: WallEntity) = walls.update(wall)

    suspend fun replacePositions(wallId: Long, points: List<Pair<NPoint, String>>, leds: List<Int?>) {
        walls.deletePositions(walls.positionList(wallId).map { it.id })
        walls.insertPositions(points.mapIndexed { i, (p, label) -> PositionEntity(wallId = wallId, x = p.x, y = p.y, label = label, ledIndex = leds.getOrNull(i)) })
    }

    companion object {
        val CLIMBER_COLORS = listOf(0xFF2E7DF6, 0xFFE5484D, 0xFF2FB36B, 0xFFF5B400, 0xFF8E4EC6, 0xFF12A594, 0xFFF76B15)
            .map { it.toInt() }
    }
}

@Singleton
class MasterRepository @Inject constructor(
    private val masters: MasterDao,
    private val walls: WallDao,
    private val photos: PhotoStore,
) {
    suspend fun create(
        name: String,
        setter: String,
        photoName: String,
        corners: List<NPoint>,
        rectified: Bitmap,
        holds: List<EditableHold>,
    ): Long {
        val wall = walls.currentWall() ?: error("No wall set up")
        val rectifiedName = photos.save(rectified)
        val first = masters.allMasters().isEmpty()
        val id = masters.insert(
            MasterEntity(
                wallId = wall.id, name = name, setterName = setter, photoPath = photoName,
                rectifiedPath = rectifiedName, corners = Polygon(corners).encode(), isActive = first,
            )
        )
        masters.insertHolds(toEntities(id, wall.id, holds))
        return id
    }

    /** Saves edited holds: kept ones are updated, removed ones deleted (they leave any path using them). */
    suspend fun updateHolds(masterId: Long, holds: List<EditableHold>) {
        val wallId = masters.get(masterId)?.wallId ?: return
        val entities = toEntities(masterId, wallId, holds)
        masters.replaceHolds(masterId, keep = entities.filter { it.id != 0L }, add = entities.filter { it.id == 0L })
    }

    /** Re-assigns anchors on every master after the wall's positions changed. */
    suspend fun resnapAll() {
        for (m in masters.allMasters()) {
            updateHolds(m.id, masters.holdList(m.id).map { EditableHold(it.id, Polygon.decode(it.contour), it.colorArgb, it.manual) })
        }
    }

    suspend fun rename(masterId: Long, name: String, setter: String) {
        masters.get(masterId)?.let { masters.update(it.copy(name = name, setterName = setter)) }
    }

    suspend fun setActive(id: Long) = masters.setActive(id)

    suspend fun delete(id: Long) {
        val m = masters.get(id) ?: return
        masters.delete(id)
        photos.delete(m.photoPath, m.rectifiedPath)
    }

    private suspend fun toEntities(masterId: Long, wallId: Long, holds: List<EditableHold>): List<MasterHoldEntity> {
        val anchors = walls.positionList(wallId).map { Anchor(it.id, NPoint(it.x, it.y)) }
        val snapped = if (anchors.isEmpty()) List(holds.size) { null } else PositionSnapper.snap(holds.map { it.outline.center }, anchors)
        return holds.mapIndexed { i, h ->
            val c = h.outline.center
            MasterHoldEntity(
                id = h.id, masterId = masterId, positionId = snapped[i], contour = h.outline.encode(),
                centerX = c.x, centerY = c.y, colorArgb = h.colorArgb, manual = h.manual,
            )
        }
    }
}

@Singleton
class PathRepository @Inject constructor(
    private val paths: PathDao,
    private val masters: MasterDao,
    private val walls: WallDao,
    private val climbers: ClimberDao,
    private val prefs: Prefs,
) {
    suspend fun save(path: PathEntity, holds: List<PathHoldEntity>): Long =
        paths.save(path.copy(updatedAt = System.currentTimeMillis()), holds)

    suspend fun delete(id: Long) = paths.delete(id)

    suspend fun duplicate(id: Long): Long {
        val p = paths.get(id) ?: error("No path $id")
        val holds = paths.holdList(id)
        val now = System.currentTimeMillis()
        return paths.save(p.copy(id = 0, title = "${p.title} (copy)", createdAt = now, updatedAt = now), holds)
    }

    suspend fun exportDto(pathId: Long): PathExportDto {
        val p = paths.get(pathId) ?: error("No path $pathId")
        val m = masters.get(p.masterId) ?: error("No master")
        val wall = walls.currentWall() ?: error("No wall")
        val scale = prefs.gradeScale.first()
        return PathExportDto(
            wall = WallDto(wall.name, wall.widthCm, wall.heightCm),
            master = m.name,
            title = p.title,
            description = p.description,
            grade = p.grade?.let { Grades.label(it, scale) },
            setter = p.setterName,
            rules = p.rules,
            holds = paths.holdShapes(pathId).map { s ->
                HoldDto(
                    role = s.role, color = colorHex(s.colorArgb), label = s.label, led = s.ledIndex,
                    position = s.positionLabel, outline = Polygon.decode(s.contour).points.flatMap { listOf(it.x, it.y) },
                )
            },
        )
    }

    suspend fun logAscent(pathId: Long, style: AscentStyle, attempts: Int, note: String) {
        val climber = prefs.activeClimber()
        // A first send in one go is a flash, whatever was picked.
        val styles = climbers.stylesFor(climber, pathId)
        val effective = when {
            style == AscentStyle.SEND && attempts == 1 && styles.isEmpty() -> AscentStyle.FLASH
            style.isSend && styles.any { it.isSend } -> AscentStyle.REPEAT
            else -> style
        }
        climbers.insertAscent(AscentEntity(pathId = pathId, climberId = climber, style = effective, attempts = attempts, note = note))
    }

    suspend fun toggleFavorite(pathId: Long) {
        val f = FavoriteEntity(prefs.activeClimber(), pathId)
        if (climbers.isFavorite(f.climberId, pathId).first()) climbers.removeFavorite(f) else climbers.addFavorite(f)
    }

    suspend fun rate(pathId: Long, stars: Int) =
        climbers.upsertRating(RatingEntity(prefs.activeClimber(), pathId, stars))
}

fun GradeScale.label(index: Int?) = Grades.label(index, this)
