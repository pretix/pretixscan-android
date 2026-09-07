package eu.pretix.pretixscan.droid.ui

import android.content.Context
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.check.TicketCheckProvider.CheckInType
import eu.pretix.libpretixsync.check.TicketCheckProvider.CheckResult.Type
import eu.pretix.pretixscan.droid.R

fun TicketCheckProvider.CheckResult.defaultMessage(ctx: Context): String? = when (type!!) {
    Type.INVALID -> ctx.getString(R.string.scan_result_invalid)
    Type.VALID -> when (scanType) {
        CheckInType.EXIT -> ctx.getString(R.string.scan_result_exit)
        CheckInType.ENTRY -> ctx.getString(R.string.scan_result_valid)
    }
    Type.USED -> ctx.getString(R.string.scan_result_used)
    Type.RULES -> ctx.getString(R.string.scan_result_rules)
    Type.AMBIGUOUS -> ctx.getString(R.string.scan_result_ambiguous)
    Type.REVOKED -> ctx.getString(R.string.scan_result_revoked)
    Type.UNAPPROVED -> ctx.getString(R.string.scan_result_unapproved)
    Type.INVALID_TIME -> ctx.getString(R.string.scan_result_invalid_time)
    Type.BLOCKED -> ctx.getString(R.string.scan_result_blocked)
    Type.UNPAID -> ctx.getString(R.string.scan_result_unpaid)
    Type.CANCELED -> ctx.getString(R.string.scan_result_canceled)
    Type.PRODUCT -> ctx.getString(R.string.scan_result_product)
    Type.ALREADY_EXCHANGED -> ctx.getString(R.string.scan_result_already_exchanged)
    Type.MEDIUM_INVALID -> ctx.getString(R.string.scan_result_medium_invalid)
    Type.MEDIUM_EXISTS -> ctx.getString(R.string.scan_result_medium_exists)
    Type.ANSWERS_REQUIRED -> ctx.getString(R.string.scan_result_answers_required)
    Type.EXCHANGE_REQUIRED -> ctx.getString(R.string.scan_result_medium_exchange_required)
    else -> null
}

fun TicketCheckProvider.CheckResult.applyDefaultMessage(ctx: Context) {
    if (message == null) {
        message = defaultMessage(ctx)
    }
}

fun TicketCheckProvider.CheckResult.resultState(): ResultState = when (type!!) {
    Type.VALID -> when (scanType) {
        CheckInType.EXIT -> ResultState.SUCCESS_EXIT
        CheckInType.ENTRY -> ResultState.SUCCESS
    }
    Type.USED -> ResultState.WARNING
    Type.INVALID,
    Type.ERROR,
    Type.RULES,
    Type.AMBIGUOUS,
    Type.REVOKED,
    Type.UNAPPROVED,
    Type.INVALID_TIME,
    Type.BLOCKED,
    Type.UNPAID,
    Type.CANCELED,
    Type.PRODUCT,
    Type.ALREADY_EXCHANGED,
    Type.MEDIUM_INVALID,
    Type.MEDIUM_EXISTS,
    Type.ANSWERS_REQUIRED,
    Type.EXCHANGE_REQUIRED,
    Type.EXCHANGE_REQUIRED_OFFLINE -> ResultState.ERROR
}

fun TicketCheckProvider.CheckResult.reasonExplanationText(ctx: Context): String? {
    if (reasonExplanation.isNullOrBlank()) {
        return null
    }
    return if (type == Type.EXCHANGE_REQUIRED_OFFLINE) {
        ctx.getString(R.string.scan_result_medium_exchange_required_offline)
    } else {
        reasonExplanation
    }
}

fun TicketCheckProvider.CheckResult.soundRes(): Int? = when (type!!) {
    Type.VALID -> when (scanType) {
        CheckInType.ENTRY -> if (isRequireAttention) R.raw.attention else R.raw.enter
        CheckInType.EXIT -> R.raw.exit
    }
    Type.ANSWERS_REQUIRED,
    Type.EXCHANGE_REQUIRED,
    Type.EXCHANGE_REQUIRED_OFFLINE -> R.raw.attention
    else -> R.raw.error
}
