package eu.pretix.pretixscan.droid.ui

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.PendingIntentCompat
import eu.pretix.libpretixsync.sqldelight.Migrations
import eu.pretix.pretixscan.droid.AppConfig
import kotlin.system.exitProcess

@SuppressLint("UnspecifiedImmutableFlag")
fun wipeApp(ctx: Context) {
    val conf = AppConfig(ctx)
    conf.resetDeviceConfig()

    ctx.deleteDatabase(Migrations.DEFAULT_DATABASE_NAME)

    val mStartActivity = Intent(ctx, WelcomeActivity::class.java)
    val mPendingIntentId = 123456
    val mPendingIntent = PendingIntentCompat.getActivity(ctx, mPendingIntentId, mStartActivity, PendingIntent.FLAG_CANCEL_CURRENT, true)!!
    val mgr = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, mPendingIntent)
    exitProcess(0)
}