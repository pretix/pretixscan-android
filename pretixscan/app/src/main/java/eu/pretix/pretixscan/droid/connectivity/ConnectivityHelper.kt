package eu.pretix.pretixscan.droid.connectivity

import android.util.Log
import eu.pretix.libpretixsync.sync.SyncManager
import eu.pretix.pretixscan.droid.AppConfig

interface ConnectivityChangedListener {
    fun onConnectivityChanged()
}

class ConnectivityHelper(val conf: AppConfig) : SyncManager.CheckConnectivityFeedback {
    private val resultHistory = mutableListOf<Long?>()
    private val HISTORY_SIZE = 5
    private val MAX_ERRORS_IN_HISTORY = 2
    private val listeners = mutableListOf<ConnectivityChangedListener>()
    private var hardOffline = false

    private fun ensureHistorySize() {
        while (resultHistory.size > HISTORY_SIZE) {
            resultHistory.removeAt(0)
        }
    }

    private fun checkConditions() {
        val maxDuration = when (conf.autoOfflineMode) {
            "off" -> return
            "1s" -> 1000
            "2s" -> 2000
            "3s" -> 3000
            "5s" -> 5000
            "10s" -> 10000
            "15s" -> 15000
            "20s" -> 20000
            "errors" -> 3600000
            else -> return
        }

        Log.i("ConnectivityHelper", "Connectivity history: " + resultHistory.map {
            it?.toString() ?: "null"
        }.joinToString(", "))

        if (conf.offlineMode) {
            val switchToOnline = !hardOffline && resultHistory.count { it == null } == 0 && resultHistory.size >= MAX_ERRORS_IN_HISTORY && resultHistory.filterNotNull().toLongArray().average() < maxDuration
            if (switchToOnline) {
                conf.offlineMode = false
                this.listeners.forEach { it.onConnectivityChanged() }
            }
        } else {
            val switchToOffline = hardOffline || resultHistory.count { it == null } >= MAX_ERRORS_IN_HISTORY || (resultHistory.size >= MAX_ERRORS_IN_HISTORY && resultHistory.filterNotNull().toLongArray().average() >= maxDuration)
            if (switchToOffline) {
                conf.offlineMode = true
                this.listeners.forEach { it.onConnectivityChanged() }
            }
        }
    }

    override fun recordSuccess(durationInMillis: Long) {
        resultHistory.add(durationInMillis)
        ensureHistorySize()
        checkConditions()
    }

    override fun recordError() {
        resultHistory.add(null)
        ensureHistorySize()
        checkConditions()
    }

    fun setHardOffline(hardOffline: Boolean) {
        this.hardOffline = hardOffline
        checkConditions()
    }

    fun addListener(listener: ConnectivityChangedListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ConnectivityChangedListener) {
        listeners.remove(listener)
    }
}