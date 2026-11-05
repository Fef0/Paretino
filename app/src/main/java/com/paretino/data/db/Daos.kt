package com.paretino.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.paretino.domain.AscentStyle
import com.paretino.domain.HoldRole
import kotlinx.coroutines.flow.Flow

@Dao
interface WallDao {
    @Query("SELECT * FROM walls ORDER BY id LIMIT 1")
    fun wall(): Flow<WallEntity?>

    @Query("SELECT * FROM walls ORDER BY id LIMIT 1")
    suspend fun currentWall(): WallEntity?

    @Insert
    suspend fun insert(wall: WallEntity): Long

    @Update
    suspend fun update(wall: WallEntity)

    @Query("SELECT * FROM positions WHERE wallId = :wallId ORDER BY y, x")
    fun positions(wallId: Long): Flow<List<PositionEntity>>

    @Query("SELECT * FROM positions WHERE wallId = :wallId ORDER BY y, x")
    suspend fun positionList(wallId: Long): List<PositionEntity>

    @Insert
    suspend fun insertPositions(positions: List<PositionEntity>): List<Long>

    @Update
    suspend fun updatePositions(positions: List<PositionEntity>)

    @Query("DELETE FROM positions WHERE id IN (:ids)")
    suspend fun deletePositions(ids: List<Long>)

    @Query("SELECT * FROM walls")
    suspend fun allWalls(): List<WallEntity>

    @Query("SELECT * FROM positions")
    suspend fun allPositions(): List<PositionEntity>
}

data class MasterSummary(
    @Embedded val master: MasterEntity,
    val holdCount: Int,
    val pathCount: Int,
)

@Dao
interface MasterDao {
    @Query(
        """
        SELECT m.*,
            (SELECT COUNT(*) FROM master_holds h WHERE h.masterId = m.id) AS holdCount,
            (SELECT COUNT(*) FROM paths p WHERE p.masterId = m.id) AS pathCount
        FROM masters m ORDER BY m.createdAt DESC
        """
    )
    fun summaries(): Flow<List<MasterSummary>>

    @Query("SELECT * FROM masters WHERE id = :id")
    fun master(id: Long): Flow<MasterEntity?>

    @Query("SELECT * FROM masters WHERE id = :id")
    suspend fun get(id: Long): MasterEntity?

    @Query("SELECT * FROM masters WHERE isActive = 1 LIMIT 1")
    fun active(): Flow<MasterEntity?>

    @Insert
    suspend fun insert(master: MasterEntity): Long

    @Update
    suspend fun update(master: MasterEntity)

    @Query("DELETE FROM masters WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE masters SET isActive = (id = :id)")
    suspend fun setActive(id: Long)

    @Query("SELECT * FROM master_holds WHERE masterId = :masterId")
    fun holds(masterId: Long): Flow<List<MasterHoldEntity>>

    @Query("SELECT * FROM master_holds WHERE masterId = :masterId")
    suspend fun holdList(masterId: Long): List<MasterHoldEntity>

    @Insert
    suspend fun insertHolds(holds: List<MasterHoldEntity>): List<Long>

    @Update
    suspend fun updateHolds(holds: List<MasterHoldEntity>)

    @Query("DELETE FROM master_holds WHERE id IN (:ids)")
    suspend fun deleteHolds(ids: List<Long>)

    @Transaction
    suspend fun replaceHolds(masterId: Long, keep: List<MasterHoldEntity>, add: List<MasterHoldEntity>) {
        val keepIds = keep.map { it.id }.toSet()
        deleteHolds(holdList(masterId).map { it.id }.filter { it !in keepIds })
        updateHolds(keep)
        insertHolds(add)
    }

    @Query("SELECT * FROM masters")
    suspend fun allMasters(): List<MasterEntity>

    @Query("SELECT * FROM master_holds")
    suspend fun allHolds(): List<MasterHoldEntity>
}

data class PathSummary(
    @Embedded val path: PathEntity,
    val masterName: String,
    val rectifiedPath: String,
    val masterActive: Boolean,
    val avgStars: Float?,
    val ratingCount: Int,
    val sendCount: Int,
    val isFavorite: Boolean,
    val tickedByMe: Boolean,
)

/** A hold of a path together with its outline on the master. */
data class PathHoldShape(
    val pathId: Long,
    val masterHoldId: Long,
    val role: HoldRole,
    val colorArgb: Int,
    val label: String,
    val contour: String,
    val ledIndex: Int?,
    val positionLabel: String?,
)

@Dao
interface PathDao {
    @Query(
        """
        SELECT p.*, m.name AS masterName, m.rectifiedPath AS rectifiedPath, m.isActive AS masterActive,
            (SELECT AVG(stars) FROM ratings r WHERE r.pathId = p.id) AS avgStars,
            (SELECT COUNT(*) FROM ratings r WHERE r.pathId = p.id) AS ratingCount,
            (SELECT COUNT(*) FROM ascents a WHERE a.pathId = p.id AND a.style != 'ATTEMPT') AS sendCount,
            EXISTS(SELECT 1 FROM favorites f WHERE f.pathId = p.id AND f.climberId = :climberId) AS isFavorite,
            EXISTS(SELECT 1 FROM ascents a WHERE a.pathId = p.id AND a.climberId = :climberId
                AND a.style != 'ATTEMPT') AS tickedByMe
        FROM paths p JOIN masters m ON m.id = p.masterId
        ORDER BY p.createdAt DESC
        """
    )
    fun summaries(climberId: Long): Flow<List<PathSummary>>

    @Query(
        """
        SELECT ph.pathId, ph.masterHoldId, ph.role, ph.colorArgb, ph.label, mh.contour,
            pos.ledIndex AS ledIndex, pos.label AS positionLabel
        FROM path_holds ph
        JOIN master_holds mh ON mh.id = ph.masterHoldId
        LEFT JOIN positions pos ON pos.id = mh.positionId
        """
    )
    fun allHoldShapes(): Flow<List<PathHoldShape>>

    @Query(
        """
        SELECT ph.pathId, ph.masterHoldId, ph.role, ph.colorArgb, ph.label, mh.contour,
            pos.ledIndex AS ledIndex, pos.label AS positionLabel
        FROM path_holds ph
        JOIN master_holds mh ON mh.id = ph.masterHoldId
        LEFT JOIN positions pos ON pos.id = mh.positionId
        WHERE ph.pathId = :pathId
        """
    )
    suspend fun holdShapes(pathId: Long): List<PathHoldShape>

    @Query("SELECT * FROM paths WHERE id = :id")
    fun path(id: Long): Flow<PathEntity?>

    @Query("SELECT * FROM paths WHERE id = :id")
    suspend fun get(id: Long): PathEntity?

    @Query("SELECT * FROM paths WHERE masterId = :masterId")
    suspend fun pathsOf(masterId: Long): List<PathEntity>

    @Query("SELECT * FROM path_holds WHERE pathId = :pathId")
    fun holds(pathId: Long): Flow<List<PathHoldEntity>>

    @Query("SELECT * FROM path_holds WHERE pathId = :pathId")
    suspend fun holdList(pathId: Long): List<PathHoldEntity>

    @Insert
    suspend fun insert(path: PathEntity): Long

    @Update
    suspend fun update(path: PathEntity)

    @Query("DELETE FROM paths WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM path_holds WHERE pathId = :pathId")
    suspend fun clearHolds(pathId: Long)

    @Insert
    suspend fun insertHolds(holds: List<PathHoldEntity>)

    @Transaction
    suspend fun save(path: PathEntity, holds: List<PathHoldEntity>): Long {
        val id = if (path.id == 0L) insert(path) else path.id.also { update(path) }
        clearHolds(id)
        insertHolds(holds.map { it.copy(pathId = id) })
        return id
    }

    @Query("SELECT * FROM paths")
    suspend fun allPaths(): List<PathEntity>

    @Query("SELECT * FROM path_holds")
    suspend fun allHolds(): List<PathHoldEntity>
}

data class AscentRow(
    @Embedded val ascent: AscentEntity,
    val climberName: String,
    val pathTitle: String,
    val pathGrade: Int?,
)

data class RatingSummary(val avgStars: Float?, val count: Int)

@Dao
interface ClimberDao {
    @Query("SELECT * FROM climbers ORDER BY name COLLATE NOCASE")
    fun climbers(): Flow<List<ClimberEntity>>

    @Query("SELECT * FROM climbers")
    suspend fun allClimbers(): List<ClimberEntity>

    @Insert
    suspend fun insert(climber: ClimberEntity): Long

    @Update
    suspend fun update(climber: ClimberEntity)

    @Delete
    suspend fun delete(climber: ClimberEntity)

    @Query(
        """
        SELECT a.*, c.name AS climberName, p.title AS pathTitle, p.grade AS pathGrade
        FROM ascents a JOIN climbers c ON c.id = a.climberId JOIN paths p ON p.id = a.pathId
        WHERE a.pathId = :pathId ORDER BY a.date DESC
        """
    )
    fun ascentsForPath(pathId: Long): Flow<List<AscentRow>>

    @Query(
        """
        SELECT a.*, c.name AS climberName, p.title AS pathTitle, p.grade AS pathGrade
        FROM ascents a JOIN climbers c ON c.id = a.climberId JOIN paths p ON p.id = a.pathId
        WHERE a.climberId = :climberId ORDER BY a.date DESC
        """
    )
    fun ascentsForClimber(climberId: Long): Flow<List<AscentRow>>

    @Insert
    suspend fun insertAscent(ascent: AscentEntity): Long

    @Query("DELETE FROM ascents WHERE id = :id")
    suspend fun deleteAscent(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE climberId = :climberId AND pathId = :pathId)")
    fun isFavorite(climberId: Long, pathId: Long): Flow<Boolean>

    @Insert
    suspend fun addFavorite(f: FavoriteEntity)

    @Delete
    suspend fun removeFavorite(f: FavoriteEntity)

    @Query("SELECT * FROM ratings WHERE climberId = :climberId AND pathId = :pathId")
    fun rating(climberId: Long, pathId: Long): Flow<RatingEntity?>

    @Query("SELECT AVG(stars) AS avgStars, COUNT(*) AS count FROM ratings WHERE pathId = :pathId")
    fun ratingSummary(pathId: Long): Flow<RatingSummary>

    @Upsert
    suspend fun upsertRating(r: RatingEntity)

    @Query("SELECT * FROM ascents")
    suspend fun allAscents(): List<AscentEntity>

    @Query("SELECT * FROM favorites")
    suspend fun allFavorites(): List<FavoriteEntity>

    @Query("SELECT * FROM ratings")
    suspend fun allRatings(): List<RatingEntity>

    @Query("SELECT style FROM ascents WHERE climberId = :climberId AND pathId = :pathId")
    suspend fun stylesFor(climberId: Long, pathId: Long): List<AscentStyle>
}
