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
import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.db.NonceGenerator
import eu.pretix.libpretixsync.models.BadgeLayout
import eu.pretix.libpretixsync.models.db.toModel
import eu.pretix.pretixscan.droid.AndroidFileStorage
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.sqldelight.SyncDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


fun getDefaultBadgeLayout(): BadgeLayout {
    return BadgeLayout.defaultWithLayout(
        "[{\"type\":\"textarea\",\"left\":\"13.09\",\"bottom\":\"49.73\",\"fontsize\":\"23.6\",\"color\":[0,0,0,1],\"fontfamily\":\"Open Sans\",\"bold\":true,\"italic\":false,\"width\":\"121.83\",\"content\":\"attendee_name\",\"text\":\"Max Mustermann\",\"align\":\"center\"}]",
    )
}

fun getBadgeLayout(db: SyncDatabase, position: JSONObject, eventSlug: String): BadgeLayout? {
    val event = db.eventQueries.selectBySlug(eventSlug).executeAsOneOrNull()?.toModel()
    if (event == null || !event.plugins.contains("pretix.plugins.badges")) {
        return null
    }

    val itemid_server = position.getLong("item")
    val itemid_local = db.itemQueries.selectByServerId(itemid_server).executeAsOneOrNull()?.id
    val litem = db.scanBadgeLayoutItemQueries.selectForItem(itemid_local).executeAsOneOrNull()
    if (litem != null) {
        val layoutId = litem.layout
        if (layoutId == null) { // "Do not print badges" is configured for this product
            return null
        } else { // A non-default badge layout is set for this product
            return db.badgeLayoutQueries.selectById(layoutId).executeAsOneOrNull()?.toModel()
        }
    }

    return db.badgeLayoutQueries.selectDefaultForEventSlug(eventSlug)
        .executeAsOneOrNull()?.toModel() ?: getDefaultBadgeLayout()
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


fun printBadge(
    context: Context,
    db: SyncDatabase,
    fileStorage: AndroidFileStorage,
    position: JSONObject,
    eventSlug: String,
    recv: ResultReceiver?
) {
    val positions = JSONArray()
    positions.put(position)
    if (AppConfig(context).printBadgesTwice) {
        positions.put(position)
    }
    val data = JSONObject()
    data.put("positions", positions)

    val dir = File(context.filesDir, "print")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val dataFile = File(dir, "order.json")
    val mediaFiles = ArrayList<File>()

    val badgelayout = getBadgeLayout(db, position, eventSlug) ?: return
    position.put("__layout", badgelayout.layout)

    val backgroundFilename = badgelayout.backgroundFilename
    if (backgroundFilename != null) {
        mediaFiles.add(fileStorage.getFile(backgroundFilename))
        position.put("__file_index", mediaFiles.size)
    }

    val etagMap = JSONObject()
    val files = db.cachedPdfImageQueries.selectForOrderPosition(position.getLong("id"))
        .executeAsList()
        .map { it.toModel() }
    for (f in files) {
        mediaFiles.add(fileStorage.getFile("pdfimage_${f.etag}.bin"))
        etagMap.put(f.key, mediaFiles.size)
    }
    position.put("__image_map", etagMap)

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
        dataFile
    )

    context.grantUriPermission(
        intent.`package`,
        dataUri,
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
    )
    intent.clipData = ClipData.newRawUri(null, dataUri)

    for (mediaFile in mediaFiles) {
        val mediaUrl = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            mediaFile
        )
        context.grantUriPermission(
            intent.`package`,
            mediaUrl,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        intent.clipData!!.addItem(ClipData.Item(mediaUrl))
    }

    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    if (BuildConfig.DEBUG && isPackageInstalled(
            "eu.pretix.pretixprint.debug",
            context.packageManager
        )
    ) {
        intent.component =
            ComponentName("eu.pretix.pretixprint.debug", "eu.pretix.pretixprint.print.PrintService")
    } else if (isPackageInstalled("eu.pretix.pretixprint", context.packageManager)) {
        intent.component =
            ComponentName("eu.pretix.pretixprint", "eu.pretix.pretixprint.print.PrintService")
    } else if (isPackageInstalled("eu.pretix.pretixprint.debug", context.packageManager)) {
        intent.component =
            ComponentName("eu.pretix.pretixprint.debug", "eu.pretix.pretixprint.print.PrintService")
    } else if (isPackageInstalled("de.silpion.bleuartcompanion", context.packageManager)) {
        intent.component = ComponentName(
            "de.silpion.bleuartcompanion",
            "de.silpion.bleuartcompanion.services.print.PrintService"
        )
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

fun isPreviouslyPrinted(db: SyncDatabase, position: JSONObject): Boolean {
    if (position.has("print_logs")) {
        val arr = position.getJSONArray("print_logs")
        val arrlen = arr.length()
        for (i in 0 until arrlen) {
            val printlog = arr.getJSONObject(i)
            if (!printlog.getBoolean("successful")) {
                continue
            }
            if (printlog.optString("type", "?") == "badge") {
                return true
            }
        }
    }

    val count = db.scanQueuedCallQueries.countWhereUrlLike("%orderpositions/" + position.getLong("id") + "/printlog/").executeAsOne()
    if (count > 0) {
        return true
    }
    return false
}

fun logSuccessfulPrint(
    api: PretixApi,
    db: SyncDatabase,
    eventSlug: String,
    positionId: Long,
    type: String
) {
    val logbody = JSONObject()
    logbody.put("source", "pretixSCAN")
    logbody.put("type", type)
    logbody.put("info", JSONObject())
    val tz = TimeZone.getTimeZone("UTC")
    val df: DateFormat = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        Locale.ENGLISH
    ) // Quoted "Z" to indicate UTC, no timezone offset
    df.timeZone = tz
    logbody.put("datetime", df.format(Date()))

    db.queuedCallQueries.insert(
        body = logbody.toString(),
        idempotency_key = NonceGenerator.nextNonce(),
        url = api.eventResourceUrl(eventSlug, "orderpositions") + positionId + "/printlog/",
    )
}