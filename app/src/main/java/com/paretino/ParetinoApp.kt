package com.paretino

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class ParetinoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("Paretino", "OpenCV failed to load: hold detection unavailable")
    }
}
