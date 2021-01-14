package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.app.Dialog
import androidx.appcompat.app.AlertDialog
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.Answer
import eu.pretix.pretixscan.droid.R

fun showUnpaidDialog(ctx: Activity, res: TicketCheckProvider.CheckResult,
                     secret: String, answers: MutableList<Answer>?,
                     retryHandler: ((String, MutableList<Answer>?, Boolean) -> Unit)): Dialog {

    val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.dialog_unpaid_title)
            .setMessage(R.string.dialog_unpaid_text)
            .setNegativeButton(R.string.cancel) { dialog, which -> dialog.cancel() }
            .setPositiveButton(R.string.dialog_unpaid_retry) { dialog, which ->
                dialog.dismiss()
                retryHandler(secret, answers, true)
            }.create()
    dialog.show()
    return dialog
}
