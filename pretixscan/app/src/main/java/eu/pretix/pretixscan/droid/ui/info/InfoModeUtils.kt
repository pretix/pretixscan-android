package eu.pretix.pretixscan.droid.ui.info

import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.models.db.toModel
import eu.pretix.libpretixsync.sqldelight.SyncDatabase
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.ui.ResultState
import java.time.OffsetDateTime
import java.time.ZoneId

enum class InfoModeAccent(val colorRes: Int, val iconRes: Int, val labelRes: Int) {
    OK(R.color.pretix_brand_green, R.drawable.ic_check_circle_white_24dp, R.string.info_mode_status_ok),
    ATTENTION(R.color.pretix_brand_orange, R.drawable.ic_warning_white_24dp, R.string.info_mode_status_attention),
    INVALID(R.color.pretix_brand_red, R.drawable.ic_error_white_24dp, R.string.info_mode_status_invalid)
}

fun ResultState.toInfoModeAccent(requiresAttention: Boolean): InfoModeAccent = when (this) {
    ResultState.SUCCESS, ResultState.SUCCESS_EXIT ->
        if (requiresAttention) InfoModeAccent.ATTENTION else InfoModeAccent.OK
    ResultState.WARNING, ResultState.DIALOG_QUESTIONS, ResultState.DIALOG_EXCHANGE -> InfoModeAccent.ATTENTION
    ResultState.ERROR, ResultState.EMPTY, ResultState.LOADING -> InfoModeAccent.INVALID
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

// TODO: hängt vom letzten Sync-Zeitpunkt ab, keine Live-Server-Abfrage
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
    }.sortedByDescending { it.dateTime }
}

fun mergeImmediateCheckin(
    db: SyncDatabase,
    dbHistory: List<TicketCheckinHistoryEntry>,
    result: TicketCheckProvider.CheckResult,
    activeListServerId: Long?,
): List<TicketCheckinHistoryEntry> {
    val firstScanned = result.firstScanned ?: return dbHistory
    if (activeListServerId == null) return dbHistory
    if (dbHistory.any { it.listServerId == activeListServerId }) return dbHistory

    val listName = db.checkInListQueries.selectByServerId(activeListServerId).executeAsOneOrNull()?.name
        ?: return dbHistory

    val entry = TicketCheckinHistoryEntry(
        listServerId = activeListServerId,
        listName = listName,
        type = "entry",
        dateTime = OffsetDateTime.ofInstant(firstScanned.toInstant(), ZoneId.systemDefault()),
    )
    return (dbHistory + entry).sortedByDescending { it.dateTime }
}

// TODO: berücksichtigt noch nicht allow_multiple_entries/allow_entry_after_exit der Check-in-Liste
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