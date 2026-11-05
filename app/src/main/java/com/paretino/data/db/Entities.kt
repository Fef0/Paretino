package com.paretino.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.paretino.domain.AscentStyle
import com.paretino.domain.HoldRole
import kotlinx.serialization.Serializable

/** The physical wall. Its anchor [PositionEntity]s are fixed; holds move between them. */
@Serializable
@Entity(tableName = "walls")
data class WallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val widthCm: Int,
    val heightCm: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A fixed anchor point (t-nut) on the wall, in rectified-wall space, optionally wired to an LED. */
@Serializable
@Entity(
    tableName = "positions",
    foreignKeys = [ForeignKey(WallEntity::class, ["id"], ["wallId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("wallId")],
)
data class PositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wallId: Long,
    val x: Float,
    val y: Float,
    val label: String,
    val ledIndex: Int? = null,
)

/** One physical set-up of the wall: a photo and the holds bolted on it. */
@Serializable
@Entity(
    tableName = "masters",
    foreignKeys = [ForeignKey(WallEntity::class, ["id"], ["wallId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("wallId")],
)
data class MasterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wallId: Long,
    val name: String,
    val setterName: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Original photo, relative to the photo store. */
    val photoPath: String,
    /** Perspective-corrected photo the holds are expressed in. */
    val rectifiedPath: String,
    /** The four wall corners picked on the original photo (normalized, TL TR BR BL), "x,y x,y x,y x,y". */
    val corners: String,
    val isActive: Boolean = false,
)

@Serializable
@Entity(
    tableName = "master_holds",
    foreignKeys = [
        ForeignKey(MasterEntity::class, ["id"], ["masterId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PositionEntity::class, ["id"], ["positionId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("masterId"), Index("positionId")],
)
data class MasterHoldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val masterId: Long,
    /** Anchor this hold is bolted to, when wall positions are set up. */
    val positionId: Long? = null,
    /** Encoded [com.paretino.domain.Polygon]. */
    val contour: String,
    val centerX: Float,
    val centerY: Float,
    /** Dominant colour of the hold in the photo. */
    val colorArgb: Int,
    val manual: Boolean = false,
)

/** A route ("combination") on a master. */
@Serializable
@Entity(
    tableName = "paths",
    foreignKeys = [ForeignKey(MasterEntity::class, ["id"], ["masterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("masterId")],
)
data class PathEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val masterId: Long,
    val title: String,
    val description: String = "",
    /** Index into [com.paretino.domain.Grades.FONT]. */
    val grade: Int? = null,
    val setterName: String = "",
    val rules: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "path_holds",
    primaryKeys = ["pathId", "masterHoldId"],
    foreignKeys = [
        ForeignKey(PathEntity::class, ["id"], ["pathId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MasterHoldEntity::class, ["id"], ["masterHoldId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("masterHoldId")],
)
data class PathHoldEntity(
    val pathId: Long,
    val masterHoldId: Long,
    val role: HoldRole,
    val colorArgb: Int,
    val label: String = "",
)

@Serializable
@Entity(tableName = "climbers")
data class ClimberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Int,
)

@Serializable
@Entity(
    tableName = "ascents",
    foreignKeys = [
        ForeignKey(PathEntity::class, ["id"], ["pathId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ClimberEntity::class, ["id"], ["climberId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("pathId"), Index("climberId")],
)
data class AscentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pathId: Long,
    val climberId: Long,
    val date: Long = System.currentTimeMillis(),
    val style: AscentStyle,
    val attempts: Int = 1,
    val note: String = "",
)

@Serializable
@Entity(
    tableName = "favorites",
    primaryKeys = ["climberId", "pathId"],
    foreignKeys = [
        ForeignKey(PathEntity::class, ["id"], ["pathId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ClimberEntity::class, ["id"], ["climberId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("pathId")],
)
data class FavoriteEntity(val climberId: Long, val pathId: Long)

@Serializable
@Entity(
    tableName = "ratings",
    primaryKeys = ["climberId", "pathId"],
    foreignKeys = [
        ForeignKey(PathEntity::class, ["id"], ["pathId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ClimberEntity::class, ["id"], ["climberId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("pathId")],
)
data class RatingEntity(
    val climberId: Long,
    val pathId: Long,
    val stars: Int,
    @ColumnInfo(name = "suggestedGrade") val suggestedGrade: Int? = null,
)
