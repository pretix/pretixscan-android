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
import eu.pretix.libpretixsync.db.*
import eu.pretix.pretixscan.droid.AppConfig
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

fun getBadgeLayout(application: PretixScan, position: JSONObject, eventSlug: String): BadgeLayout? {
    val event = application.data.select(Event::class.java)
        .where(Event.SLUG.eq(eventSlug))
        .get().firstOrNull()
    if (!event.hasPlugin("pretix.plugins.badges")) {
        return null
    }

    val itemid_server = position.getLong("item")
    val itemid_local = application.data.select(Item::class.java)
        .where(Item.SERVER_ID.eq(itemid_server))
        .get().firstOrNull().getId()

    val litem = application.data.select(BadgeLayoutItem::class.java)
            .where(BadgeLayoutItem.ITEM_ID.eq(itemid_local))
            .get().firstOrNull()
    if (litem != null) {
        if (litem.getLayout() == null) { // "Do not print badges" is configured for this product
            return null
        } else { // A non-default badge layout is set for this product
            return litem.getLayout()
        }
    }

    return application.data.select(BadgeLayout::class.java)
            .where(BadgeLayout.IS_DEFAULT.eq(true))
            .and(BadgeLayout.EVENT_SLUG.eq(eventSlug))
            .get().firstOrNull() ?: getDefaultBadgeLayout()
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


fun printBadge(context: Context, application: PretixScan, position: JSONObject, eventSlug: String, recv: ResultReceiver?) {
    val config = AppConfig(context)
    val positions = JSONArray()
    positions.put(position)
    if (config.printBadgesTwice) {
        positions.put(position)
    }
    val store = application.data
    val data = JSONObject()
    data.put("positions", positions)

    val dir = File(context.filesDir, "print")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val dataFile = File(dir, "order.json")
    val mediaFiles = ArrayList<File>()

    val badgelayout = if (config.printLayoutOverride != 0L) {
        application.data.select(BadgeLayout::class.java)
            .where(BadgeLayout.SERVER_ID.eq(config.printLayoutOverride))
            .get().firstOrNull()
    } else {
        getBadgeLayout(application, position, eventSlug) ?: return
    }
    position.put("__layout", badgelayout.json.getJSONArray("layout"))

    if (badgelayout.getBackground_filename() != null) {
        mediaFiles.add(application.fileStorage.getFile(badgelayout.getBackground_filename()))
        position.put("__file_index", mediaFiles.size)
    }

    val etagMap = JSONObject()
    val files = store.select(CachedPdfImage::class.java).where(CachedPdfImage.ORDERPOSITION_ID.eq(position.getLong("id"))).get().toList()
    for (f in files) {
        mediaFiles.add(application.fileStorage.getFile("pdfimage_${f.getEtag()}.bin"))
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
            dataFile)

    context.grantUriPermission(intent.`package`, dataUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(null, dataUri)

    for (mediaFile in mediaFiles) {
        val mediaUrl = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                mediaFile)
        context.grantUriPermission(intent.`package`, mediaUrl, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData!!.addItem(ClipData.Item(mediaUrl))
    }

    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    if (BuildConfig.DEBUG && isPackageInstalled("eu.pretix.pretixprint.debug", context.packageManager)) {
        intent.component = ComponentName("eu.pretix.pretixprint.debug", "eu.pretix.pretixprint.print.PrintService")
    } else if (isPackageInstalled("eu.pretix.pretixprint", context.packageManager)) {
        intent.component = ComponentName("eu.pretix.pretixprint", "eu.pretix.pretixprint.print.PrintService")
    } else if (isPackageInstalled("eu.pretix.pretixprint.debug", context.packageManager)) {
        intent.component = ComponentName("eu.pretix.pretixprint.debug", "eu.pretix.pretixprint.print.PrintService")
    } else if (isPackageInstalled("de.silpion.bleuartcompanion", context.packageManager)) {
        intent.component = ComponentName("de.silpion.bleuartcompanion", "de.silpion.bleuartcompanion.services.print.PrintService")
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