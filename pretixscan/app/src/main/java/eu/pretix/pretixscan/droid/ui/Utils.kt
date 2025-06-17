package eu.pretix.pretixscan.droid.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.PendingIntentCompat
import eu.pretix.libpretixsync.Models
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.pretixscan.droid.AndroidHttpClientFactory
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.PretixScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.system.exitProcess

fun wipeApp(ctx: Context) {
    val conf = AppConfig(ctx)

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val api = PretixApi.fromConfig(
                conf,
                AndroidHttpClientFactory(ctx.applicationContext as PretixScan)
            )
            api.postResource(
                api.apiURL("device/revoke"),
                JSONObject()
            )
        } catch (_: Exception) {
            // well, we tried.
            // The user has to clean up the devices list themselves.
        }

        withContext(Dispatchers.Main) {
            conf.resetDeviceConfig()

            ctx.deleteDatabase(Models.DEFAULT.name)

            val mStartActivity = Intent(ctx, WelcomeActivity::class.java)
            val mPendingIntentId = 123456
            val mPendingIntent = PendingIntentCompat.getActivity(ctx, mPendingIntentId, mStartActivity, PendingIntent.FLAG_CANCEL_CURRENT, true)!!
            val mgr = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, mPendingIntent)
            exitProcess(0)
        }
    }
}