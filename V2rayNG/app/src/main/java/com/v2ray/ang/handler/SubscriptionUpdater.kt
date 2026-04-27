package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.extension.toLongEx
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit
import kotlin.math.max

object SubscriptionUpdater {

    class UpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        private val notificationManager = NotificationManagerCompat.from(applicationContext)
        private val notification =
            NotificationCompat.Builder(applicationContext, AppConfig.SUBSCRIPTION_UPDATE_CHANNEL)
                .setWhen(0)
                .setTicker("Update")
                .setContentTitle(context.getString(R.string.title_pref_auto_update_subscription))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        /**
         * Performs the subscription update work.
         * @return The result of the work.
         */
        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            LogUtil.i(AppConfig.TAG, "subscription automatic update starting")
            MmkvManager.encodeLastSubscriptionUpdateAttempt(System.currentTimeMillis())

            val subs = MmkvManager.decodeSubscriptions().filter { it.subscription.autoUpdate }

            for (sub in subs) {
                val subItem = sub.subscription

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notification.setChannelId(AppConfig.SUBSCRIPTION_UPDATE_CHANNEL)
                    val channel =
                        NotificationChannel(
                            AppConfig.SUBSCRIPTION_UPDATE_CHANNEL,
                            AppConfig.SUBSCRIPTION_UPDATE_CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_MIN
                        )
                    notificationManager.createNotificationChannel(channel)
                }
                notificationManager.notify(3, notification.build())
                LogUtil.i(AppConfig.TAG, "subscription automatic update: ---${subItem.remarks}")
                AngConfigManager.updateConfigViaSub(sub)
                notification.setContentText("Updating ${subItem.remarks}")
            }
            notificationManager.cancel(3)
            return Result.success()
        }
    }

    fun configureUpdateTask(interval: Long) {
        val rw = RemoteWorkManager.getInstance(AngApplication.application)
        val lastAttempt = MmkvManager.decodeLastSubscriptionUpdateAttempt()
        val intervalMillis = interval * 60 * 1000
        val now = System.currentTimeMillis()
        val delayMillis = if (lastAttempt <= 0L) 0L else max(0L, lastAttempt + intervalMillis - now)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        rw.enqueueUniquePeriodicWork(
            AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequest.Builder(
                UpdateTask::class.java,
                interval,
                TimeUnit.MINUTES
            )
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()
        )
    }

    fun cancelUpdateTask() {
        val rw = RemoteWorkManager.getInstance(AngApplication.application)
        rw.cancelUniqueWork(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
    }

    fun scheduleIfNeeded() {
        if (MmkvManager.decodeSettingsBool(AppConfig.SUBSCRIPTION_AUTO_UPDATE)) {
            val interval = MmkvManager.decodeSettingsString(
                AppConfig.SUBSCRIPTION_AUTO_UPDATE_INTERVAL,
                AppConfig.SUBSCRIPTION_DEFAULT_UPDATE_INTERVAL
            )?.toLongEx() ?: AppConfig.SUBSCRIPTION_DEFAULT_UPDATE_INTERVAL.toLong()
            configureUpdateTask(interval)
        }
    }
}
