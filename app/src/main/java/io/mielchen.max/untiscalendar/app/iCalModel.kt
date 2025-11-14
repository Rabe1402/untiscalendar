package io.mielchen.max.untiscalendar.app

import net.fortuna.ical4j.model.*
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.*
import net.fortuna.ical4j.util.TimeZoneRegistryFactory
import org.bytedream.untis4j.UntisUtils
import org.bytedream.untis4j.responseObjects.Timetable
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Einfache Logger-Klasse für Docker (nutzt stdout/stderr)
object DockerLogger {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    private fun now() = java.time.LocalDateTime.now().format(dateFmt)

    fun info(message: String) {
        println("[${now()}] INFO  | $message")
    }

    fun warn(message: String) {
        println("[${now()}] WARN  | $message")
    }

    fun error(message: String, throwable: Throwable? = null) {
        System.err.println("[${now()}] ERROR | $message")
        throwable?.let { System.err.println(it.stackTraceToString()) }
    }

    fun debug(message: String) {
        // Optional: nur bei DEBUG aktivieren
        if (System.getenv("DEBUG") == "true") {
            println("[${now()}] DEBUG | $message")
        }
    }
}

fun Timetable.getCalender(): Calendar {
    DockerLogger.info("Starte Kalendergenerierung für ${this.size} Stunden...")

    val calendar = Calendar().apply {
        properties.add(ProdId("-//Max Mielchen//UntisCalender 1.0//EN"))
        properties.add(Version.VERSION_2_0)
        properties.add(CalScale.GREGORIAN)

        try {
            val tzRegistry = TimeZoneRegistryFactory.getInstance().createRegistry()
            val vTimeZone = tzRegistry.getTimeZone(Config.timezone)?.vTimeZone
            if (vTimeZone != null) {
                components.add(vTimeZone)
                DockerLogger.info("Zeitzone hinzugefügt: ${Config.timezone}")
            } else {
                DockerLogger.warn("Zeitzone nicht gefunden: ${Config.timezone} → kein VTIMEZONE")
            }
        } catch (e: Exception) {
            DockerLogger.error("Fehler beim Laden der Zeitzone: ${e.message}", e)
        }
    }

    val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    for ((index, lesson) in this.withIndex()) {
        DockerLogger.info("--- Stunde #$index ---")
        DockerLogger.info("Datum: ${lesson.date.format(dateFmt)} | Zeit: ${lesson.startTime.format(timeFmt)}–${lesson.endTime.format(timeFmt)}")
        DockerLogger.debug("Lesson-Code: ${lesson.code}")

        if (lesson.code == UntisUtils.LessonCode.CANCELLED) {
            DockerLogger.info("Stunde ist ABGESAGT → überspringen")
            continue
        }

        // --- Fachname ---
        var name = Config.defaultSummary
        if (lesson.subjects.longNames.isNotEmpty()) {
            name = lesson.subjects.longNames[0]
            DockerLogger.info("Fach: $name")
        } else {
            DockerLogger.warn("Keine Fächer gefunden → nutze Default: '$name'")
        }

        // --- Event erstellen ---
        val start = lesson.startTime.atDate(lesson.date).atZone(ZoneId.of(Config.timezone))
        val end = lesson.endTime.atDate(lesson.date).atZone(ZoneId.of(Config.timezone))

        val event = VEvent(
            DateTime(java.util.Date.from(start.toInstant())),
            DateTime(java.util.Date.from(end.toInstant())),
            name.replaceUmlaute()
        )
        DockerLogger.info("Event: '${name.replaceUmlaute()}' von ${start.toLocalTime()} bis ${end.toLocalTime()}")

        // --- Beschreibung ---
        val desc = buildString {
            if (lesson.rooms.isNotEmpty()) {
                val room = lesson.rooms[0].longName.replaceUmlaute()
                append("${Config.defaultRoomAlias.replaceUmlaute()}: $room")
                DockerLogger.info("Raum: $room")
            } else {
                append("${Config.defaultRoomAlias.replaceUmlaute()}: -")
                DockerLogger.warn("Kein Raum angegeben")
            }

            append("\n")

            if (lesson.teachers.isNotEmpty()) {
                val teacher = lesson.teachers[0].fullName.replaceUmlaute()
                append("${Config.defaultTeacherAlias.replaceUmlaute()}: $teacher")
                DockerLogger.info("Lehrer: $teacher")
            } else {
                append("${Config.defaultTeacherAlias.replaceUmlaute()}: -")
                DockerLogger.warn("Kein Lehrer angegeben")
            }
        }

        event.properties.add(Description(desc))
        DockerLogger.debug("Beschreibung:\n$desc")

        calendar.components.add(event)
        DockerLogger.info("Event zum Kalender hinzugefügt")
    }

    DockerLogger.info("Kalender fertig – ${calendar.components.size} Komponenten")
    return calendar
}

// --- replaceUmlaute mit Log ---
fun String.replaceUmlaute(): String {
    val original = this
    val map = mapOf(
        "ä" to "ae", "ö" to "oe", "ü" to "ue",
        "Ä" to "Ae", "Ö" to "Oe", "Ü" to "Ue",
        "ß" to "ss"
    )
    var result = this
    map.forEach { (u, r) ->
        if (result.contains(u)) {
            result = result.replace(u, r)
            DockerLogger.debug("Ersetze '$u' → '$r' in: '$original'")
        }
    }
    if (result != original) {
        DockerLogger.info("Umlaute ersetzt: '$original' → '$result'")
    }
    return result
}
