package eu.pretix.pretixscan.droid.hardware

import android.os.Build

object KioskHardware {
    fun isPretixBadgebox(): Boolean {
        return Build.MODEL == "PRETIX_KT0345_8MP"
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
}