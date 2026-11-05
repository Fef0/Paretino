package com.paretino.di

import android.content.Context
import androidx.room.Room
import com.paretino.data.db.AppDatabase
import com.paretino.export.BleEsp32Exporter
import com.paretino.export.WallLinkExporter
import com.paretino.vision.HoldDetector
import com.paretino.vision.YoloHoldDetector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "paretino.db").build()

    @Provides fun wallDao(db: AppDatabase) = db.wallDao()
    @Provides fun masterDao(db: AppDatabase) = db.masterDao()
    @Provides fun pathDao(db: AppDatabase) = db.pathDao()
    @Provides fun climberDao(db: AppDatabase) = db.climberDao()
    @Provides fun restoreDao(db: AppDatabase) = db.restoreDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun holdDetector(impl: YoloHoldDetector): HoldDetector

    /** Swap for the real BLE implementation once the ESP32 firmware exists. */
    @Binds
    abstract fun wallLink(impl: BleEsp32Exporter): WallLinkExporter
}
