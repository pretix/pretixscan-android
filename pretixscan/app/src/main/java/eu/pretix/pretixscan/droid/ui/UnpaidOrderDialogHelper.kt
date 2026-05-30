package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.Answer
import eu.pretix.libpretixsync.db.ReusableMediaType
import eu.pretix.libpretixui.android.questions.QuestionsDialogInterface
import eu.pretix.pretixscan.droid.R


class UnpaidDialog(ctx: Activity, val secret: String, val sourceType: ReusableMediaType, val answers: MutableList<Answer>?,
                   val retryHandler: ((String, ReusableMediaType, MutableList<Answer>?, Boolean) -> Unit)) : AlertDialog(ctx), QuestionsDialogInterface {
    init {
        setTitle(R.string.dialog_unpaid_title)
        setMessage(ctx.getString(R.string.dialog_unpaid_text))
        setButton(DialogInterface.BUTTON_POSITIVE, ctx.getString(R.string.dialog_unpaid_retry)) { p0, p1 ->
            dismiss()
            retryHandler(secret, sourceType, answers, true)
        }
        setButton(DialogInterface.BUTTON_NEGATIVE, ctx.getString(eu.pretix.libpretixui.android.R.string.cancel)) { p0, p1 ->
            cancel()
        }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return false
    }
}

fun showUnpaidDialog(ctx: Activity,
                     res: TicketCheckProvider.CheckResult,
                     secret: String,
                     sourceType: ReusableMediaType,
                     answers: MutableList<Answer>?,
                     retryHandler: ((String, ReusableMediaType, MutableList<Answer>?, Boolean) -> Unit)): QuestionsDialogInterface {
    val dialog = UnpaidDialog(ctx, secret, sourceType, answers, retryHandler)
    dialog.show()
    return dialog
}
