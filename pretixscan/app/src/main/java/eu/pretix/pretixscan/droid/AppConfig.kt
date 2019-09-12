package eu.pretix.pretixscan.droid

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.preference.PreferenceManager

import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.config.ConfigStore
import eu.pretix.pretixscan.utils.KeystoreHelper


class AppConfig(ctx: Context) : ConfigStore {

    private val prefs: SharedPreferences
    private val default_prefs: SharedPreferences

    init {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        default_prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    }

    override fun isDebug(): Boolean {
        return BuildConfig.DEBUG
    }

    override fun isConfigured(): Boolean {
        return prefs.contains(PREFS_KEY_API_URL)
    }

    fun setDeviceConfig(url: String, key: String, orga_slug: String, device_id: Long, serial: String, sent_version: Int) {
        val ckey = if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            KeystoreHelper.secureValue(key, true)
            else key
        prefs.edit()
                .putString(PREFS_KEY_API_URL, url)
                .putString(PREFS_KEY_API_KEY, ckey)
                .putString(PREFS_KEY_ORGANIZER_SLUG, orga_slug)
                .putLong(PREFS_KEY_DEVICE_ID, device_id)
                .putString(PREFS_KEY_DEVICE_SERIAL, serial)
                .putInt(PREFS_KEY_DEVICE_KNOWN_VERSION, sent_version)
                .remove(PREFS_KEY_LAST_DOWNLOAD)
                .remove(PREFS_KEY_LAST_SYNC)
                .remove(PREFS_KEY_LAST_FAILED_SYNC)
                .remove(PREFS_KEY_LAST_STATUS_DATA)
                .apply()
    }

    @SuppressLint("ApplySharedPref")
    fun resetDeviceConfig() {
        prefs.edit().clear().commit()
        default_prefs.edit().clear().commit()
    }

    override fun getApiVersion(): Int {
        return prefs.getInt(PREFS_KEY_API_VERSION, PretixApi.SUPPORTED_API_VERSION)
    }

    override fun getEventSlug(): String? = prefs.getString(PREFS_KEY_EVENT_SLUG, null)
    fun setEventSlug(value: String?) = prefs.edit().putString(PREFS_KEY_EVENT_SLUG, value).apply()

    var subeventId: Long?
        get() = if (prefs.contains(PREFS_KEY_SUBEVENT_ID)) {
            prefs.getLong(PREFS_KEY_SUBEVENT_ID, -1)
        } else null
        set(value) = if (value != null) {
            prefs.edit().putLong(PREFS_KEY_SUBEVENT_ID, value).apply()
        } else {
            prefs.edit().remove(PREFS_KEY_SUBEVENT_ID).apply()
        }

    override fun getSubEventId(): Long? {
        return subeventId
    }

    var eventName: String?
        get() = prefs.getString(PREFS_KEY_EVENT_NAME, null)
        set(value) = prefs.edit().putString(PREFS_KEY_EVENT_NAME, value).apply()

    var checkinListId: Long
        get() = prefs.getLong(PREFS_KEY_CHECKINLIST_ID, 0L)
        set(value) = prefs.edit().putLong(PREFS_KEY_CHECKINLIST_ID, value).apply()

    override fun getOrganizerSlug(): String {
        return prefs.getString(PREFS_KEY_ORGANIZER_SLUG, "")
    }

    override fun getApiUrl(): String {
        return prefs.getString(PREFS_KEY_API_URL, "")
    }

    override fun getApiKey(): String {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return KeystoreHelper.secureValue(prefs.getString(PREFS_KEY_API_KEY, ""), false)!!
        } else {
            return prefs.getString(PREFS_KEY_API_KEY, "")
        }
    }

    override fun getLastDownload(): Long {
        return prefs.getLong(PREFS_KEY_LAST_DOWNLOAD, 0)
    }

    override fun setLastDownload(`val`: Long) {
        prefs.edit().putLong(PREFS_KEY_LAST_DOWNLOAD, `val`).apply()
    }

    override fun getLastSync(): Long {
        return prefs.getLong(PREFS_KEY_LAST_SYNC, 0)
    }

    override fun setLastSync(`val`: Long) {
        prefs.edit().putLong(PREFS_KEY_LAST_SYNC, `val`).apply()
    }

    override fun getLastFailedSync(): Long {
        return prefs.getLong(PREFS_KEY_LAST_FAILED_SYNC, 0)
    }

    override fun setLastFailedSync(`val`: Long) {
        prefs.edit().putLong(PREFS_KEY_LAST_FAILED_SYNC, `val`).apply()
    }

    override fun getLastFailedSyncMsg(): String {
        return prefs.getString(PREFS_KEY_LAST_FAILED_SYNC_MSG, "")
    }

    override fun setLastFailedSyncMsg(`val`: String) {
        prefs.edit().putString(PREFS_KEY_LAST_FAILED_SYNC_MSG, `val`).apply()
    }

    var devicePosId: Long
        get() = prefs.getLong(PREFS_KEY_DEVICE_ID, 0L)
        set(value) = prefs.edit().putLong(PREFS_KEY_DEVICE_ID, value).apply()

    override fun getPosId(): Long {
        return devicePosId
    }

    var devicePosSerial: String
        get() = prefs.getString(PREFS_KEY_DEVICE_SERIAL, "")
        set(value) = prefs.edit().putString(PREFS_KEY_DEVICE_SERIAL, value).apply()

    var scanFlash: Boolean
        get() = prefs.getBoolean(PREFS_KEY_SCAN_FLASH, false)
        set(value) = prefs.edit().putBoolean(PREFS_KEY_SCAN_FLASH, value).apply()

    var scanFocus: Boolean
        get() = prefs.getBoolean(PREFS_KEY_SCAN_AUTOFOCUS, true)
        set(value) = prefs.edit().putBoolean(PREFS_KEY_SCAN_AUTOFOCUS, value).apply()

    override fun getDeviceKnownVersion(): Int {
        return prefs.getInt(PREFS_KEY_DEVICE_KNOWN_VERSION, 0)
    }

    override fun setDeviceKnownVersion(value: Int) {
        prefs.edit().putInt(PREFS_KEY_DEVICE_KNOWN_VERSION, value).apply()
    }

    var deviceRegistered: Boolean = false
        get() = prefs.contains(PREFS_KEY_DEVICE_SERIAL) && prefs.contains(PREFS_KEY_API_KEY)

    var useCamera: Boolean
        get() = default_prefs.getBoolean(PREFS_KEY_USE_CAMERA, true)
        set(value) = default_prefs.edit().putBoolean(PREFS_KEY_USE_CAMERA, value).apply()

    var proxyMode: Boolean
        get() = default_prefs.getBoolean(PREFS_KEY_SCAN_PROXY, true)
        set(value) = default_prefs.edit().putBoolean(PREFS_KEY_SCAN_PROXY, value).apply()

    var offlineMode: Boolean
        get() = default_prefs.getBoolean(PREFS_KEY_SCAN_OFFLINE, true)
        set(value) = default_prefs.edit().putBoolean(PREFS_KEY_SCAN_OFFLINE, value).apply()

    var printBadges: Boolean
        get() = default_prefs.getBoolean(PREFS_KEY_PRINTBADGES, false)
        set(value) = default_prefs.edit().putBoolean(PREFS_KEY_PRINTBADGES, value).apply()

    var autoPrintBadges: Boolean
        get() = default_prefs.getBoolean(PREFS_KEY_AUTOPRINTBADGES, false)
        set(value) = default_prefs.edit().putBoolean(PREFS_KEY_AUTOPRINTBADGES, value).apply()

    companion object {
        val PREFS_NAME = "pretixdroid"
        val PREFS_KEY_API_URL = "pretix_api_url"
        val PREFS_KEY_API_KEY = "pretix_api_key"
        val PREFS_KEY_EVENT_SLUG = "pretix_api_event_slug"
        val PREFS_KEY_SUBEVENT_ID = "pretix_api_subevent_id"
        val PREFS_KEY_EVENT_NAME = "event_name"
        val PREFS_KEY_CHECKINLIST_ID = "checkin_list_id"
        val PREFS_KEY_ORGANIZER_SLUG = "pretix_api_organizer_slug"
        val PREFS_KEY_SHOW_INFO = "show_info"
        val PREFS_KEY_ALLOW_SEARCH = "allow_search"
        val PREFS_KEY_API_VERSION = "pretix_api_version"
        val PREFS_KEY_LAST_SYNC = "last_sync"
        val PREFS_KEY_LAST_FAILED_SYNC = "last_failed_sync"
        val PREFS_KEY_LAST_FAILED_SYNC_MSG = "last_failed_sync_msg"
        val PREFS_KEY_LAST_DOWNLOAD = "last_download"
        val PREFS_KEY_LAST_STATUS_DATA = "last_status_data"
        val PREFS_KEY_DEVICE_ID = "device_pos_id"
        val PREFS_KEY_DEVICE_SERIAL = "device_pos_serial"
        val PREFS_KEY_DEVICE_KNOWN_VERSION = "device_pos_known_version"
        val PREFS_KEY_SCAN_AUTOFOCUS = "scan_autofocus"
        val PREFS_KEY_SCAN_FLASH = "scan_flash"
        val PREFS_KEY_USE_CAMERA = "pref_use_camera"
        val PREFS_KEY_SCAN_OFFLINE = "pref_scan_offline"
        val PREFS_KEY_SCAN_PROXY = "pref_scan_proxy"
        val PREFS_KEY_PRINTBADGES = "pref_print_badges"
        val PREFS_KEY_AUTOPRINTBADGES = "pref_auto_print_badges"
    }
}
