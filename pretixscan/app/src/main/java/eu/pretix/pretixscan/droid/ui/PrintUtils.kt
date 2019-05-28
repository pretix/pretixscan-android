package eu.pretix.pretixscan.droid.ui

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.os.ResultReceiver
import androidx.core.content.FileProvider
import eu.pretix.libpretixsync.db.BadgeLayout
import eu.pretix.libpretixsync.db.BadgeLayoutItem
import eu.pretix.libpretixsync.db.Item
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.PretixScan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset


fun getDefaultBadgeLayout(): BadgeLayout {
    val tl = BadgeLayout()
    tl.setJson_data("{\"layout\": [{\"type\":\"textarea\",\"left\":\"13.09\",\"bottom\":\"49.73\",\"fontsize\":\"23.6\",\"color\":[0,0,0,1],\"fontfamily\":\"Open Sans\",\"bold\":true,\"italic\":false,\"width\":\"121.83\",\"content\":\"attendee_name\",\"text\":\"Max Mustermann\",\"align\":\"center\"}]}")
    return tl
}

fun getBadgeLayout(application: PretixScan, position: JSONObject): BadgeLayout? {
    val itemid = position.getLong("item")

    val litem = application.data.select(BadgeLayoutItem::class.java)
            .where(BadgeLayoutItem.ITEM_ID.eq(itemid))
            .get().firstOrNull()
    if (litem != null) {
        if (litem.getLayout() == null) {
            return null
        } else {
            return litem.getLayout()
        }
    }

    /* Legacy mechanism: Keep around until pretix 2.5 is end of life */
    val item = application.data.select(Item::class.java)
            .where(Item.SERVER_ID.eq(itemid))
            .get().firstOrNull() ?: return getDefaultBadgeLayout()
    if (item.getBadge_layout_id() != null) {
        return application.data.select(BadgeLayout::class.java)
                .where(BadgeLayout.SERVER_ID.eq(item.getBadge_layout_id()))
                .get().firstOrNull() ?: getDefaultBadgeLayout()
    } else {
        return application.data.select(BadgeLayout::class.java)
                .where(BadgeLayout.IS_DEFAULT.eq(true))
                .get().firstOrNull() ?: getDefaultBadgeLayout()
    }
}

fun isPackageInstalled(packagename: String, packageManager: PackageManager): Boolean {
    try {
        packageManager.getPackageInfo(packagename, 0)
        return true
    } catch (e: PackageManager.NameNotFoundException) {
        return false
    }

}

fun receiverForSending(actualReceiver: ResultReceiver): ResultReceiver {
    val parcel = Parcel.obtain()
    actualReceiver.writeToParcel(parcel, 0)
    parcel.setDataPosition(0)
    val receiverForSending = ResultReceiver.CREATOR.createFromParcel(parcel)
    parcel.recycle()
    return receiverForSending
}


fun printBadge(context: Context, application: PretixScan, position: JSONObject, recv: ResultReceiver?) {
    val positions = JSONArray()
    positions.put(position)
    val data = JSONObject()
    data.put("positions", positions)

    val dir = File(context.filesDir, "print")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val dataFile = File(dir, "order.json")
    val backgroundFiles = ArrayList<File>()

    val badgelayout = getBadgeLayout(application, position) ?: return
    position.put("__layout", badgelayout.json.getJSONArray("layout"))

    if (badgelayout.getBackground_filename() != null) {
        backgroundFiles.add(application.fileStorage.getFile(badgelayout.getBackground_filename()))
        position.put("__file_index", backgroundFiles.size)
    }

    dataFile.outputStream().use {
        it.write(data.toString().toByteArray(Charset.forName("UTF-8")))
    }

    val intent = Intent()
    if (BuildConfig.DEBUG) {
        intent.`package` = "eu.pretix.pretixprint.debug"
    } else {
        intent.`package` = "eu.pretix.pretixprint"
    }
    intent.action = "eu.pretix.pretixpos.print.PRINT_BADGE"
    val dataUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            dataFile)

    context.grantUriPermission(intent.`package`, dataUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(null, dataUri)

    for (bgFile in backgroundFiles) {
        val bgUri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                bgFile)
        context.grantUriPermission(intent.`package`, bgUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData.addItem(ClipData.Item(bgUri))
    }

    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    if (isPackageInstalled("eu.pretix.pretixprint", context.packageManager)) {
        intent.component = ComponentName("eu.pretix.pretixprint", "eu.pretix.pretixprint.print.PrintService")
    } else if (isPackageInstalled("eu.pretix.pretixprint.debug", context.packageManager)) {
        intent.component = ComponentName("eu.pretix.pretixprint.debug", "eu.pretix.pretixprint.print.PrintService")
    } else if (isPackageInstalled("de.silpion.bleterminal", context.packageManager)) {
        intent.component = ComponentName("de.silpion.bleterminal", "de.silpion.bleterminal.print.PrintService")
    } else {
        throw Exception("error_print_no_app");
    }
    if (recv != null) {
        intent.putExtra("resultreceiver", receiverForSending(recv));
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}