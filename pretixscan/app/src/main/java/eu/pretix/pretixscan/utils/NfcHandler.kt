package eu.pretix.pretixscan.utils

import Mf0aesKeySet
import android.app.Activity
import eu.pretix.libpretixnfc.android.hardware.NfcHandler
import eu.pretix.libpretixnfc.android.hardware.NfcHandlerMode
import eu.pretix.libpretixsync.models.db.toModel
import eu.pretix.libpretixsync.utils.codec.binary.Base64
import eu.pretix.libpretixsync.utils.getActiveMediaTypes
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.libpretixnfc.android.hardware.getNfcHandler as getSuperNfcHandler
import java.nio.charset.Charset

fun getNfcHandler(activity: Activity, mode: NfcHandlerMode = NfcHandlerMode.DEFAULT): NfcHandler? {
    val conf = AppConfig(activity)

    if (conf.synchronizedEvents.isEmpty()) {
        return null
    }

    val eventSlug = conf.synchronizedEvents.first()
    val settingsManager = SettingsManager(activity.application)
    val settings = settingsManager.getBySlug(eventSlug)
    val useRandomIdForNewTags = settings?.json?.optBoolean("reusable_media_type_nfc_mf0aes_random_uid", false) ?: false
    val activeMediaTypes = getActiveMediaTypes(
        settingsManager,
        eventSlug
    )

    if (!activeMediaTypes.any { it.isNfcBased() }) {
        return null
    }

    val keySets =  (activity.application as PretixScan).db.mediumKeySetQueries.selectAll()
        .executeAsList()
        .map { it.toModel() }
        .map {
            Mf0aesKeySet(
                it.publicId,
                it.organizer == conf.organizerSlug && it.active,
                conf.keyStore.decryptRsa(
                    "device",
                    Base64.decodeBase64(it.uidKey.toByteArray(Charset.defaultCharset()))
                ),
                conf.keyStore.decryptRsa(
                    "device",
                    Base64.decodeBase64(it.diversificationKey.toByteArray(Charset.defaultCharset()))
                ),
            )
        }

    return getSuperNfcHandler(
        activity,
        keySets,
        useRandomIdForNewTags,
        mode,
        conf.nfcReaderType
    )
}