package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


fun Activity.checkPermission(perm: String, requestCode: Int) {
    if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this,
                arrayOf(perm),
                requestCode)
    }
}
