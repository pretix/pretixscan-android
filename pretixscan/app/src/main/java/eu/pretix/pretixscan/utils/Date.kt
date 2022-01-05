package eu.pretix.pretixscan.utils

import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.*


fun daysOfWeekFromLocale(): Array<DayOfWeek> {
    // from https://github.com/kizitonwose/CalendarView/blob/e7bc4c605eb95228b9d93613e19527e8bc732681/sample/src/main/java/com/kizitonwose/calendarviewsample/Extensions.kt
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    var daysOfWeek = DayOfWeek.values()
    // Order `daysOfWeek` array so that firstDayOfWeek is at index 0.
    // Only necessary if firstDayOfWeek != DayOfWeek.MONDAY which has ordinal 0.
    if (firstDayOfWeek != DayOfWeek.MONDAY) {
        val rhs = daysOfWeek.sliceArray(firstDayOfWeek.ordinal..daysOfWeek.indices.last)
        val lhs = daysOfWeek.sliceArray(0 until firstDayOfWeek.ordinal)
        daysOfWeek = rhs + lhs
    }
    return daysOfWeek
}
