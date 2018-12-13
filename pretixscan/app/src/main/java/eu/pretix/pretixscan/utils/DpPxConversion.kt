package eu.pretix.pretixscan.utils

import android.content.res.Resources

val Int.px: Int  // converts px to dp
    get() = (this / Resources.getSystem().displayMetrics.density).toInt()
val Int.dp: Int  // converts dp to px
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()