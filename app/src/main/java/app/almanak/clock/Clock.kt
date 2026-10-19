package app.almanak.clock

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Emits now, then again right after every minute boundary. */
fun minuteTicks(now: () -> ZonedDateTime = ZonedDateTime::now): Flow<ZonedDateTime> = flow {
    while (true) {
        val t = now()
        emit(t)
        delay(millisToNextMinute(t) + 50)
    }
}

internal fun millisToNextMinute(t: ZonedDateTime): Long =
    60_000L - (t.second * 1_000L + t.nano / 1_000_000L)

/** Time split in its visible parts so the layout can style the meridiem separately. */
data class ClockText(val time: String, val meridiem: String?)

object ClockFormat {
    fun time(t: ZonedDateTime, use24h: Boolean, locale: Locale): ClockText =
        if (use24h) {
            ClockText(t.format(DateTimeFormatter.ofPattern("H:mm", locale)), null)
        } else {
            ClockText(
                t.format(DateTimeFormatter.ofPattern("h:mm", locale)),
                t.format(DateTimeFormatter.ofPattern("a", locale)).uppercase(locale),
            )
        }

    fun weekday(t: ZonedDateTime, locale: Locale): String =
        t.format(DateTimeFormatter.ofPattern("EEEE", locale)).replaceFirstChar { it.titlecase(locale) }

    /** "Thursday, October 8" / "Jeudi 8 octobre" / "Jueves, 8 de octubre" — French takes no comma after the weekday. */
    fun longDate(t: ZonedDateTime, locale: Locale): String =
        if (locale.language == "fr") "${weekday(t, locale)} ${monthDay(t, locale)}"
        else "${weekday(t, locale)}, ${monthDay(t, locale)}"

    fun monthDay(t: ZonedDateTime, locale: Locale): String =
        t.format(DateTimeFormatter.ofPattern(monthDayPattern(locale, "MMMM"), locale))

    /** Day-and-month pattern for [locale]; [month] is "MMMM" or "MMM". */
    fun monthDayPattern(locale: Locale, month: String): String = when (locale.language) {
        "fr" -> "d MMMM"
        "es" -> "d 'de' MMMM"
        else -> "$month d"
    }
}
