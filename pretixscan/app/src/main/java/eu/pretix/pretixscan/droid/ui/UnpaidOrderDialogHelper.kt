package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import eu.pretix.libpretixui.android.questions.QuestionsDialogInterface
import eu.pretix.pretixscan.droid.R


class UnpaidDialog(ctx: Activity, val retryHandler: (() -> Unit)) : AlertDialog(ctx), QuestionsDialogInterface {
    init {
        setTitle(R.string.dialog_unpaid_title)
        setMessage(ctx.getString(R.string.dialog_unpaid_text))
        setButton(BUTTON_POSITIVE, ctx.getString(R.string.dialog_unpaid_retry)) { _, _ ->
            dismiss()
            retryHandler()
        }
        setButton(BUTTON_NEGATIVE, ctx.getString(eu.pretix.libpretixui.android.R.string.cancel)) { _, _ ->
            cancel()
        }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return false
    }
}

fun showUnpaidDialog(ctx: Activity, retryHandler: (() -> Unit)): QuestionsDialogInterface {
    val dialog = UnpaidDialog(ctx, retryHandler)
    dialog.show()
    return dialog
}
