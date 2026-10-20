package com.example.hiltible

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.preference.PreferenceManager
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration as OsmConfiguration
import javax.inject.Inject

@HiltAndroidApp
class HiltiBleApplication : Application() {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        // Configure osmdroid Tile Usage Policy compliance before any MapView creation
        OsmConfiguration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )
        // Identify the app per https://operations.osmfoundation.org/policies/tiles/
        OsmConfiguration.getInstance().userAgentValue =
            "HiltiBLE/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"

        // Configure WorkManager with Hilt
        androidx.work.WorkManager.initialize(
            this,
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
        )
    }
}
