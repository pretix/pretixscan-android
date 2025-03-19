package eu.pretix.pretixscan.droid.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ResultReceiver


fun openGate(
    context: Context,
    recv: ResultReceiver?
) {
    val intent = Intent()
    intent.action = "eu.pretix.pretixscan.gate.OPEN_GATE"

    if (isPackageInstalled("eu.pretix.kt0345service", context.packageManager)) {
        intent.`package` = "eu.pretix.kt0345service"
        intent.component =
            ComponentName("eu.pretix.kt0345service", "eu.pretix.kt0345service.GateService")
    } else {
        throw Exception("error_print_no_app")
    }
    if (recv != null) {
        intent.putExtra("resultreceiver", receiverForSending(recv))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}