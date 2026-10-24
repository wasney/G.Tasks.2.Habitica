package com.habitsync.gtasks.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.habitsync.gtasks.data.local.SyncDatabase
import com.habitsync.gtasks.data.local.SyncIntervalOption
import com.habitsync.gtasks.data.local.SyncPreferences
import com.habitsync.gtasks.data.remote.GoogleTasksApiService
import com.habitsync.gtasks.data.remote.HabiticaApiService
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = SyncPreferences(applicationContext)
        val db = SyncDatabase.getInstance(applicationContext)

        val habiticaApi = Retrofit.Builder()
            .baseUrl("https://habitica.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HabiticaApiService::class.java)

        val googleApi = Retrofit.Builder()
            .baseUrl("https://tasks.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GoogleTasksApiService::class.java)

        val engine = TwoWaySyncEngine(
            prefs = prefs,
            dao = db.syncDao(),
            habiticaApi = habiticaApi,
            googleApi = googleApi
        )

        val outcome = engine.runFullSync()
        return if (outcome.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "HabitSyncPeriodicTwoWayWork"

        fun scheduleOrUpdate(context: Context, prefs: SyncPreferences) {
            val settings = prefs.getSettings()
            val workManager = WorkManager.getInstance(context)

            if (settings.syncInterval == SyncIntervalOption.MANUAL_ONLY) {
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(
                settings.syncInterval.minutes,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun triggerImmediateSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<PeriodicSyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
