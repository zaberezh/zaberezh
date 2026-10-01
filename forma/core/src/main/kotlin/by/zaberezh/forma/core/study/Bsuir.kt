package by.zaberezh.forma.core.study

import by.zaberezh.forma.core.store.JSON
import by.zaberezh.forma.core.store.Pref
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Пара из расписания. weeks пусто — каждую неделю; date — разовое занятие. Даты — ISO-строки. */
@Serializable
data class Lesson(
    val subject: String,               // «МА»
    val full: String = "",             // «Математический анализ»
    val type: String = "",             // ЛК / ПЗ / ЛР / Консультация / Экзамен
    val start: String = "",            // «08:30»
    val end: String = "",
    val weekday: Int = 1,              // 1 = понедельник
    val weeks: List<Int> = emptyList(),
    val subgroup: Int = 0,             // 0 — вся группа
    val rooms: List<String> = emptyList(),
    val teachers: List<String> = emptyList(),
    val teachersFull: List<String> = emptyList(),   // «Иванов Иван Иванович»
    val teacherInfo: List<String> = emptyList(),    // «доцент, к.т.н.»
    val photos: List<String> = emptyList(),          // ссылки на фото преподавателей (ИИС)
    val from: String? = null,
    val to: String? = null,
    val date: String? = null,
    val note: String = "",
) {
    val title get() = full.ifBlank { subject }
    /** Полное название типа занятия. */
    val typeFull get() = when (type.uppercase()) {
        "ЛК" -> "Лекция"; "ПЗ" -> "Практическое занятие"; "ЛР" -> "Лабораторная работа"
        "КОНС", "КОНСУЛЬТАЦИЯ" -> "Консультация"; else -> type
    }
}

/** Скачанное расписание группы. weekAnchor — дата и номер учебной недели (1–4) с сервера. */
@Serializable
data class Timetable(
    val group: String = "",
    val lessons: List<Lesson> = emptyList(),
    val start: String? = null,
    val end: String? = null,
    val fetchedAt: Long = 0,
    /** Версия разбора: расписание, скачанное старой версией приложения (без фото и т. п.), перекачивается. */
    val format: Int = 0,
    val anchorDate: String? = null,
    val anchorWeek: Int? = null,
)

/** Группа и подгруппа (0 — обе). */
@Serializable data class StudyPrefs(val group: String = "653502", val subgroup: Int = 0)

val TIMETABLE = Pref("study.timetable", Timetable.serializer()) { Timetable() }
val STUDY_PREFS = Pref("study.prefs", StudyPrefs.serializer()) { StudyPrefs() }

/**
 * Расписание БГУИР из открытого API ИИС (iis.bsuir.by/api/v1): /schedule?studentGroup=… и /schedule/current-week.
 * Учебная неделя БГУИР — цикл из 4 недель; пара идёт в недели из своего weekNumber.
 */
object Bsuir {
    /** Текущая версия разбора расписания (2 — фото преподавателей по id). */
    const val FORMAT = 2

    private const val API = "https://iis.bsuir.by/api/v1"
    private val DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val DAYS = mapOf("понедельник" to 1, "вторник" to 2, "среда" to 3, "четверг" to 4, "пятница" to 5, "суббота" to 6, "воскресенье" to 7)

    fun url(group: String) = "$API/schedule?studentGroup=${group.trim()}"
    const val WEEK_URL = "$API/schedule/current-week"

    private fun date(s: String?): String? = s?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.trim(), DMY).toString() }.getOrNull() }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

    private fun lesson(o: JsonObject, weekday: Int): Lesson {
        val emps = o["employees"].arr().mapNotNull { it as? JsonObject }
        val teachers = emps.map { p ->
            listOfNotNull(p["lastName"].str(), p["firstName"].str()?.firstOrNull()?.let { "$it." }, p["middleName"].str()?.firstOrNull()?.let { "$it." })
                .joinToString(" ")
        }
        return Lesson(
            subject = o["subject"].str() ?: o["subjectFullName"].str() ?: "Занятие",
            full = o["subjectFullName"].str() ?: "",
            type = o["lessonTypeAbbrev"].str() ?: "",
            start = o["startLessonTime"].str() ?: "",
            end = o["endLessonTime"].str() ?: "",
            weekday = weekday,
            weeks = o["weekNumber"].arr().mapNotNull { (it as? JsonPrimitive)?.intOrNull },
            subgroup = (o["numSubgroup"] as? JsonPrimitive)?.intOrNull ?: 0,
            rooms = o["auditories"].arr().mapNotNull { it.str() },
            teachers = teachers,
            teachersFull = emps.map { p -> listOfNotNull(p["lastName"].str(), p["firstName"].str(), p["middleName"].str()).joinToString(" ") },
            teacherInfo = emps.map { p -> listOfNotNull(p["rank"].str(), p["degree"].str()).filter { it.isNotBlank() }.joinToString(", ") },
            // нет ссылки — у ИИС есть фото по id преподавателя
            photos = emps.map { p -> Iis.photoUrl(p["photoLink"].str() ?: p["photoUrl"].str() ?: (p["id"] as? JsonPrimitive)?.intOrNull?.let { "/api/v1/employees/photo/$it" }) },
            from = date(o["startLessonDate"].str()),
            to = date(o["endLessonDate"].str()),
            date = date(o["dateLesson"].str()),
            note = o["note"].str() ?: "",
        )
    }

    /** Ответ /schedule → пары. Неизвестные поля игнорируются, формат дат — dd.MM.yyyy. */
    fun parse(json: String, group: String, now: Long = System.currentTimeMillis()): Timetable {
        val root = JSON.parseToJsonElement(json).jsonObject
        val lessons = mutableListOf<Lesson>()
        (root["schedules"] as? JsonObject)?.forEach { (day, list) ->
            val wd = DAYS[day.lowercase().trim()] ?: return@forEach
            list.arr().forEach { (it as? JsonObject)?.let { o -> lessons += lesson(o, wd) } }
        }
        // экзамены и консультации — разовые занятия с датой
        root["exams"].arr().forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val d = date(o["dateLesson"].str()) ?: return@forEach
            lessons += lesson(o, LocalDate.parse(d).dayOfWeek.value).copy(date = d, weeks = emptyList())
        }
        return Timetable(group, lessons.sortedWith(compareBy({ it.weekday }, { it.start })),
            date(root["startDate"].str()), date(root["endDate"].str()), now, format = FORMAT)
    }

    /** Номер учебной недели 1–4 на дату: от якоря с сервера или от 1 сентября (неделя 1). */
    fun week(tt: Timetable, day: LocalDate): Int {
        val (anchor, w) = tt.anchorDate?.let { LocalDate.parse(it) to (tt.anchorWeek ?: 1) }
            ?: (LocalDate.of(if (day.monthValue >= 9) day.year else day.year - 1, 9, 1) to 1)
        val mon = { d: LocalDate -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        val diff = ChronoUnit.WEEKS.between(mon(anchor), mon(day)).toInt()
        return Math.floorMod(w - 1 + diff, 4) + 1
    }

    /** Пары на дату для подгруппы (0 — все), по времени. */
    fun on(tt: Timetable, day: LocalDate, subgroup: Int = 0): List<Lesson> {
        val iso = day.toString()
        val w = week(tt, day)
        return tt.lessons.filter { l ->
            (subgroup == 0 || l.subgroup == 0 || l.subgroup == subgroup) &&
                if (l.date != null) l.date == iso
                else l.weekday == day.dayOfWeek.value && (l.weeks.isEmpty() || w in l.weeks) &&
                    (l.from ?: tt.start).let { it == null || iso >= it } && (l.to ?: tt.end).let { it == null || iso <= it }
        }.sortedBy { it.start }
    }

    /** Следующее занятие по предмету после даты: сначала того же типа (ПЗ → ПЗ), иначе любое. */
    fun next(tt: Timetable, subject: String, type: String, after: LocalDate, subgroup: Int = 0, horizonDays: Int = 120): LocalDate? {
        var any: LocalDate? = null
        for (i in 1..horizonDays) {
            val d = after.plusDays(i.toLong())
            val ls = on(tt, d, subgroup).filter { it.subject == subject }
            if (ls.any { it.type == type }) return d
            if (any == null && ls.isNotEmpty()) any = d
        }
        return any
    }

    /** Предметы с лабораторными (для формы «новая лаба»), затем остальные. */
    fun subjects(tt: Timetable): List<String> {
        val all = tt.lessons.filter { it.date == null }.groupBy { it.subject }
        return all.keys.sortedWith(compareBy({ k -> if (all.getValue(k).any { it.type == "ЛР" }) 0 else 1 }, { it }))
    }

    /** Ответ /current-week — просто число. */
    fun parseWeek(body: String): Int? = body.trim().trim('"').toIntOrNull()?.takeIf { it in 1..4 }

    /** Скачать расписание и текущую неделю. fetch — HTTP GET (на телефоне — с телефона). */
    fun download(group: String, fetch: (String) -> String?, today: LocalDate = LocalDate.now()): Timetable {
        val body = fetch(url(group)) ?: error("Не удалось скачать расписание группы $group (нет сети или iis.bsuir.by недоступен)")
        val tt = parse(body, group)
        if (tt.lessons.isEmpty()) error("В ответе ИИС нет пар для группы $group — проверь номер группы")
        val w = fetch(WEEK_URL)?.let(::parseWeek)
        return if (w != null) tt.copy(anchorDate = today.toString(), anchorWeek = w) else tt
    }
}
