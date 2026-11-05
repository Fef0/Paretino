package com.paretino.export

import com.paretino.data.PhotoStore
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
import com.paretino.data.db.RestoreDao
import com.paretino.data.db.WallDao
import com.paretino.data.db.WallEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupDto(
    val version: Int = 1,
    val walls: List<WallEntity>,
    val positions: List<PositionEntity>,
    val masters: List<MasterEntity>,
    val masterHolds: List<MasterHoldEntity>,
    val paths: List<PathEntity>,
    val pathHolds: List<PathHoldEntity>,
    val climbers: List<ClimberEntity>,
    val ascents: List<AscentEntity>,
    val favorites: List<FavoriteEntity>,
    val ratings: List<RatingEntity>,
)

/** Whole-app backup: a zip with backup.json and every wall photo. */
@Singleton
class BackupManager @Inject constructor(
    private val walls: WallDao,
    private val masters: MasterDao,
    private val paths: PathDao,
    private val climbers: ClimberDao,
    private val restore: RestoreDao,
    private val photos: PhotoStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(out: OutputStream) = withContext(Dispatchers.IO) {
        val dto = BackupDto(
            walls = walls.allWalls(), positions = walls.allPositions(),
            masters = masters.allMasters(), masterHolds = masters.allHolds(),
            paths = paths.allPaths(), pathHolds = paths.allHolds(),
            climbers = climbers.allClimbers(), ascents = climbers.allAscents(),
            favorites = climbers.allFavorites(), ratings = climbers.allRatings(),
        )
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_NAME))
            zip.write(json.encodeToString(BackupDto.serializer(), dto).toByteArray())
            zip.closeEntry()
            val referenced = dto.masters.flatMap { listOf(it.photoPath, it.rectifiedPath) }.toSet()
            for (name in referenced) {
                val f = photos.file(name).takeIf { it.exists() } ?: continue
                zip.putNextEntry(ZipEntry("photos/$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Replaces everything in the app with the backup. Returns the number of paths restored. */
    suspend fun import(input: InputStream): Int = withContext(Dispatchers.IO) {
        var dto: BackupDto? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == JSON_NAME -> dto = json.decodeFromString(BackupDto.serializer(), zip.readBytes().decodeToString())
                    e.name.startsWith("photos/") && !e.isDirectory -> {
                        val name = File(e.name).name // never trust paths inside the zip
                        photos.file(name).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val b = dto ?: error("Not a Paretino backup")
        restore.replaceAll(
            b.walls, b.positions, b.masters, b.masterHolds, b.paths, b.pathHolds,
            b.climbers, b.ascents, b.favorites, b.ratings,
        )
        b.paths.size
    }

    private companion object {
        const val JSON_NAME = "backup.json"
    }
}
