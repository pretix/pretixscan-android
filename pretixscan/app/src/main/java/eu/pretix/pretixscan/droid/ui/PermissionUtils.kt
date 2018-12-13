package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


fun Activity.checkPermission(perm: String, requestCode: Int=1337) {
    if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                        perm)) {
            // TODO: Show explanation
            ActivityCompat.requestPermissions(this,
                    arrayOf(perm),
                    requestCode)
        } else {
            // No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(this,
                    arrayOf(perm),
                    requestCode)
        }
    }
}
