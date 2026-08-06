package eu.pretix.pretixscan.droid.ui.info

import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.models.db.toModel
import eu.pretix.libpretixsync.sqldelight.SyncDatabase
import eu.pretix.pretixscan.droid.R
import java.time.OffsetDateTime

/**
 * Small, stateless helper functions for Info mode - kept together in one file, same convention
 * as GateUtils.kt/PrintUtils.kt (flat *Utils.kt files in ui/, not a separate utils package).
 * Adapters stay in their own file (CheckinHistoryAdapter.kt), consistent with how the rest of
 * the project keeps adapters separate from Utils files.
 */

// ---------------------------------------------------------------------------------------------
// Status accent mapping (was InfoModeAccent.kt)
// ---------------------------------------------------------------------------------------------

/**
 * Maps the (many) CheckResult.Type values onto three muted accent buckets for Info mode.
 * Deliberately just icon tint / text color / thin stroke - never a flat full-area fill -
 * so this reads as visually distinct from the real scan-result screen (which fills the
 * whole result card with @{data.getColor(data.resultState)}, see activity_main.xml).
 *
 * Reuses the existing white status icons (ic_check_circle_white_24dp etc.) - they're plain
 * single-color vectors, so tinting them to a different color via setColorFilter works fine
 * and keeps the iconography consistent with the rest of the app.
 */
enum class InfoModeAccent(val colorRes: Int, val iconRes: Int, val labelRes: Int) {
    OK(R.color.pretix_brand_green, R.drawable.ic_check_circle_white_24dp, R.string.info_mode_status_ok),
    ATTENTION(R.color.pretix_brand_orange, R.drawable.ic_warning_white_24dp, R.string.info_mode_status_attention),
    INVALID(R.color.pretix_brand_red, R.drawable.ic_error_white_24dp, R.string.info_mode_status_invalid)
}

fun TicketCheckProvider.CheckResult.Type?.toInfoModeAccent(): InfoModeAccent = when (this) {
    TicketCheckProvider.CheckResult.Type.VALID -> InfoModeAccent.OK

    // "Would work, but needs something first" - shown as attention/orange, not a hard error
    TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED,
    TicketCheckProvider.CheckResult.Type.UNPAID,
    TicketCheckProvider.CheckResult.Type.AMBIGUOUS,
    TicketCheckProvider.CheckResult.Type.EXCHANGE_REQUIRED,
    TicketCheckProvider.CheckResult.Type.EXCHANGE_REQUIRED_OFFLINE,
    TicketCheckProvider.CheckResult.Type.USED -> InfoModeAccent.ATTENTION

    // Everything else - genuinely wouldn't work
    TicketCheckProvider.CheckResult.Type.INVALID,
    TicketCheckProvider.CheckResult.Type.ERROR,
    TicketCheckProvider.CheckResult.Type.BLOCKED,
    TicketCheckProvider.CheckResult.Type.INVALID_TIME,
    TicketCheckProvider.CheckResult.Type.CANCELED,
    TicketCheckProvider.CheckResult.Type.PRODUCT,
    TicketCheckProvider.CheckResult.Type.RULES,
    TicketCheckProvider.CheckResult.Type.REVOKED,
    TicketCheckProvider.CheckResult.Type.UNAPPROVED,
    TicketCheckProvider.CheckResult.Type.ALREADY_EXCHANGED,
    TicketCheckProvider.CheckResult.Type.MEDIUM_INVALID,
    TicketCheckProvider.CheckResult.Type.MEDIUM_EXISTS,
    null -> InfoModeAccent.INVALID
}

data class TicketCheckinHistoryEntry(
    val listServerId: Long,
    val listName: String,
    val type: String?,
    val dateTime: OffsetDateTime?,
)

enum class PresenceStatus {
    PRESENT,
    NOT_PRESENT,
    NOT_SCANNED_YET
}

fun loadCheckinHistory(db: SyncDatabase, positionServerId: Long?): List<TicketCheckinHistoryEntry> {
    if (positionServerId == null) return emptyList()

    val localPosition = db.orderPositionQueries.selectByServerId(positionServerId).executeAsOneOrNull()
        ?: return emptyList()

    val checkIns = db.checkInQueries.selectByPositionId(localPosition.id).executeAsList().map { it.toModel() }

    return checkIns.mapNotNull { checkIn ->
        val listServerId = checkIn.listServerId ?: return@mapNotNull null
        val listName = db.checkInListQueries.selectByServerId(listServerId).executeAsOneOrNull()?.name
            ?: return@mapNotNull null
        TicketCheckinHistoryEntry(
            listServerId = listServerId,
            listName = listName,
            type = checkIn.type,
            dateTime = checkIn.dateTime,
        )
    }.sortedBy { it.dateTime }
}

fun currentPresenceStatus(history: List<TicketCheckinHistoryEntry>, activeListServerId: Long?): PresenceStatus {
    val lastOnActiveList = history
        .filter { it.listServerId == activeListServerId }
        .maxByOrNull { it.dateTime ?: OffsetDateTime.MIN }
        ?: return PresenceStatus.NOT_SCANNED_YET

    return when (lastOnActiveList.type) {
        "exit" -> PresenceStatus.NOT_PRESENT
        else -> PresenceStatus.PRESENT
    }
}