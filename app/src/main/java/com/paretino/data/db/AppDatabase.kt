package com.paretino.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Database(
    entities = [
        WallEntity::class, PositionEntity::class, MasterEntity::class, MasterHoldEntity::class,
        PathEntity::class, PathHoldEntity::class, ClimberEntity::class, AscentEntity::class,
        FavoriteEntity::class, RatingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wallDao(): WallDao
    abstract fun masterDao(): MasterDao
    abstract fun pathDao(): PathDao
    abstract fun climberDao(): ClimberDao
    abstract fun restoreDao(): RestoreDao
}

/** Bulk writes for restoring a backup with its original ids. */
@Dao
interface RestoreDao {
    @Query("DELETE FROM walls") suspend fun clearWalls()
    @Query("DELETE FROM climbers") suspend fun clearClimbers()

    @Insert suspend fun walls(items: List<WallEntity>)
    @Insert suspend fun positions(items: List<PositionEntity>)
    @Insert suspend fun masters(items: List<MasterEntity>)
    @Insert suspend fun masterHolds(items: List<MasterHoldEntity>)
    @Insert suspend fun paths(items: List<PathEntity>)
    @Insert suspend fun pathHolds(items: List<PathHoldEntity>)
    @Insert suspend fun climbers(items: List<ClimberEntity>)
    @Insert suspend fun ascents(items: List<AscentEntity>)
    @Insert suspend fun favorites(items: List<FavoriteEntity>)
    @Insert suspend fun ratings(items: List<RatingEntity>)

    /** Everything hangs off walls and climbers, so clearing those cascades to every table. */
    @Transaction
    suspend fun replaceAll(
        walls: List<WallEntity>, positions: List<PositionEntity>, masters: List<MasterEntity>,
        masterHolds: List<MasterHoldEntity>, paths: List<PathEntity>, pathHolds: List<PathHoldEntity>,
        climbers: List<ClimberEntity>, ascents: List<AscentEntity>, favorites: List<FavoriteEntity>,
        ratings: List<RatingEntity>,
    ) {
        clearWalls(); clearClimbers()
        walls(walls); positions(positions); masters(masters); masterHolds(masterHolds)
        paths(paths); pathHolds(pathHolds); climbers(climbers); ascents(ascents)
        favorites(favorites); ratings(ratings)
    }
}
