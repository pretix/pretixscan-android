package eu.pretix.pretixscan.utils

import android.app.Application
import eu.pretix.libpretixsync.models.Settings
import eu.pretix.libpretixsync.models.db.toModel
import eu.pretix.libpretixsync.utils.SettingsManager
import eu.pretix.pretixscan.droid.PretixScan

class SettingsManager(private val application: Application): SettingsManager {
    override fun getBySlug(eventSlug: String): Settings? {
        return (application as PretixScan).db.settingsQueries.selectBySlug(eventSlug).executeAsOneOrNull()?.toModel()
    }
}