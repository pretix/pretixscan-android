package eu.pretix.pretixscan.droid.hardware

import android.os.Build
import eu.pretix.pretixscan.droid.BuildConfig

object KioskHardware {
    fun isTR51(): Boolean {
        return Build.BRAND == "pretix" && Build.MODEL == "TR51"
    }

    fun isZebra(): Boolean {
        return Build.BRAND == "Zebra" && Build.MODEL.startsWith("CC6")
    }

    fun isNewland(): Boolean {
        return Build.BRAND in listOf(
            "NewLand",
            "Newland"
        ) && Build.MODEL.startsWith("CC6") || Build.MODEL.startsWith("NQ")
    }

    fun isSeuic(): Boolean {
        return Build.BRAND == "SEUIC" && Build.MODEL.startsWith("AUTOID Pad Air")
    }

    fun isM3(): Boolean {
        return Build.BRAND == "M3" && Build.MODEL.startsWith("M3PC")
    }

    fun isWA1053T(): Boolean {
        return BuildConfig.DEBUG && Build.MODEL == "rk3399-Android10"
    }
}