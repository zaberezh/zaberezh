package by.zaberezh.forma.core.study

import by.zaberezh.forma.core.store.JSON
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Разделы личного кабинета ИИС в виде понятных моделей (формат ответов сверен с веб-версией ИИС
 * и рабочим клиентом MyIIS). Всё разбирается мягко: нет поля — пусто, а не ошибка.
 */
sealed interface CabinetData

/** Персональные данные: /profiles/personal-profile + /personal-information. */
data class Person(
    val fio: String, val fioBy: String, val birth: String, val faculty: String, val speciality: String,
    val course: Int?, val group: String, val email: String, val phone: String, val rating: Int?, val photo: String,
) : CabinetData

/** Зачётная книжка: /markbook. */
data class Markbook(val number: String, val average: Double?, val semesters: List<MarkSemester>) : CabinetData
data class MarkSemester(val number: Int, val average: Double?, val marks: List<MarkRow>)
data class MarkRow(
    val subject: String, val full: String, val form: String, val hours: String, val credits: Double?,
    val mark: String, val date: String, val teacher: String, val retakes: Int,
)

/** Успеваемость текущего семестра: /personal-rating. */
data class Rating(val subjects: List<RatingSubject>) : CabinetData {
    val marks get() = subjects.flatMap { it.marks }
    val average get() = avg(marks)
    val missed get() = subjects.sumOf { it.missed }
    val labsDone get() = subjects.sumOf { s -> s.types.sumOf { it.labs?.done ?: 0 } }
    val labsTotal get() = subjects.sumOf { s -> s.types.sumOf { it.labs?.total ?: 0 } }
    val labsOverdue get() = subjects.sumOf { s -> s.types.sumOf { it.labs?.overdue ?: 0 } }
}
data class RatingSubject(val abbrev: String, val name: String, val types: List<RatingType>, val percents: List<Pair<String, Double>>) {
    val marks get() = types.flatMap { t -> t.lessons.flatMap { l -> l.marks.map { it.mark } } }
    val average get() = avg(marks)
    val missed get() = types.sumOf { t -> t.lessons.sumOf { it.missed } }
}
data class RatingType(val abbrev: String, val lessons: List<RatingLesson>, val labs: LabProgress?)
data class RatingLesson(val date: String, val marks: List<RatingMark>, val missed: Int, val subgroup: Int, val controlPoint: String)
data class RatingMark(val mark: Int, val task: Int?)
/** Лабы по типу занятия: сдано заданий из [total], ближайший срок по несданному и сколько сроков уже прошло. */
data class LabProgress(val done: Int, val total: Int, val next: String?, val nextTask: Int?, val overdue: Int, val tasks: List<IisTask> = emptyList())
/** Одна лаба/задание в ИИС: номер, срок (ISO), сдана ли (есть отметка с этим номером), отметка и когда. */
data class IisTask(val number: Int, val due: String?, val done: Boolean, val mark: Int?, val doneOn: String?)

/** Пропуски: по месяцам, без уважительной причины, справки об уважительных. */
data class Omissions(val monthly: List<Pair<String, Int>>, val unexcused: List<Omission>, val certificates: List<OmissionCert>) : CabinetData {
    val total get() = monthly.sumOf { it.second }
}
data class Omission(val date: String, val subject: String, val type: String, val hours: Int, val term: Int)
data class OmissionCert(val name: String, val from: String, val to: String, val note: String)

/** Группа: /student-groups/user-group-info. */
data class GroupInfo(val number: String, val curator: Curator?, val students: List<GroupMate>) : CabinetData
data class Curator(val fio: String, val position: String, val phone: String, val email: String)
data class GroupMate(val fio: String, val position: String) { val head get() = position.contains("староста", ignoreCase = true) }

/** Справки: /certificate. */
data class Certificates(val items: List<Certificate>) : CabinetData
data class Certificate(val number: Int, val place: String, val ordered: String, val issued: String, val type: String, val status: Int, val rejection: String) {
    val statusText get() = when (status) { 1 -> "готова"; 2 -> "обрабатывается"; 3 -> "отклонена"; else -> "статус $status" }
}

private fun avg(xs: List<Int>): Double? = if (xs.isEmpty()) null else xs.average()

object Cabinet {
    private val DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val MINSK = ZoneId.of("Europe/Minsk")

    // ---------- мягкое чтение JSON ----------
    private fun parse(json: String?): JsonElement? = json?.let { runCatching { JSON.parseToJsonElement(it) }.getOrNull() }
    private fun JsonElement?.o() = this as? JsonObject
    private fun JsonElement?.a(): List<JsonElement> = (this as? JsonArray).orEmpty()
    private fun JsonObject?.s(vararg k: String): String = k.firstNotNullOfOrNull { key ->
        (this?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }.orEmpty()
    private fun JsonObject?.d(k: String): Double? = s(k).replace(',', '.').toDoubleOrNull()
    private fun JsonObject?.i(k: String): Int? = d(k)?.toInt()

    /** Дата из ИИС: «dd.MM.yyyy», «yyyy-MM-dd…» или миллисекунды → «dd.MM.yyyy». */
    fun date(raw: String): String {
        val r = raw.trim()
        return when {
            r.isEmpty() -> ""
            r.all(Char::isDigit) && r.length >= 11 -> Instant.ofEpochMilli(r.toLong()).atZone(MINSK).toLocalDate().format(DMY)
            Regex("""\d{4}-\d{2}-\d{2}.*""").matches(r) -> LocalDate.parse(r.take(10)).format(DMY)
            else -> r
        }
    }

    private fun day(dmy: String): LocalDate? = runCatching { LocalDate.parse(dmy, DMY) }.getOrNull()

    // ---------- разделы ----------
    fun person(profileJson: String?, infoJson: String?, loginJson: String? = null): Person {
        val p = parse(profileJson).o(); val i = parse(infoJson).o(); val l = parse(loginJson).o()
        val all = listOfNotNull(p, i, l)
        fun any(vararg k: String) = all.firstNotNullOfOrNull { o -> o.s(*k).takeIf(String::isNotEmpty) }.orEmpty()
        val edu = i?.get("education").a().firstOrNull().o()
        val fio = listOf(any("lastName"), any("firstName"), any("middleName")).filter(String::isNotEmpty).joinToString(" ")
            .ifEmpty { any("fio", "fullName") }
        val by = listOf(any("belarusianLastName"), any("belarusianFirstName"), any("belarusianMiddleName")).filter(String::isNotEmpty).joinToString(" ")
        return Person(
            fio = fio, fioBy = by.takeIf { it != fio }.orEmpty(), birth = date(any("birthDate", "birthday")),
            faculty = any("faculty", "facultyAbbrev").ifEmpty { edu.s("faculty") },
            speciality = any("speciality", "specialityName").ifEmpty { edu.s("speciality") },
            course = all.firstNotNullOfOrNull { it.i("course") } ?: edu.i("course"),
            group = any("studentGroup", "group", "groupName").ifEmpty { edu.s("group") },
            email = any("email", "universityEmail"), phone = any("phone", "mobilePhone"),
            rating = all.firstNotNullOfOrNull { it.i("rating") }?.takeIf { it > 0 },
            photo = photo(any("photoUrl", "photo", "photoLink")),
        )
    }

    /** Фото профиля: https-ссылка на ИИС или встроенная картинка base64. */
    fun photo(raw: String): String = when {
        raw.startsWith("data:image") -> raw
        raw.length > 200 && !raw.contains('/') -> "data:image/jpeg;base64,$raw"
        raw.length > 200 && raw.matches(Regex("[A-Za-z0-9+/=\\s]+")) -> "data:image/jpeg;base64,$raw"
        else -> Iis.photoUrl(raw)
    }

    fun markbook(json: String): Markbook {
        val o = parse(json).o() ?: throw IisError("ИИС прислал непонятный ответ")
        val pages = o["markPages"].o().orEmpty()
        val sems = pages.mapNotNull { (k, v) ->
            val s = v.o() ?: return@mapNotNull null
            MarkSemester(k.toIntOrNull() ?: 0, s.d("averageMark")?.takeIf { it > 0 }, s["marks"].a().mapNotNull { m ->
                val x = m.o() ?: return@mapNotNull null
                MarkRow(
                    subject = x.s("subject"), full = x.s("fullSubject").ifEmpty { x.s("subject") }, form = x.s("formOfControl"),
                    hours = x.s("hours"), credits = x.d("credits")?.takeIf { it > 0 }, mark = x.s("mark"),
                    date = date(x.s("date")), teacher = x.s("teacher"), retakes = x.i("retakesCount") ?: 0,
                )
            })
        }.sortedByDescending { it.number }
        return Markbook(o.s("number"), o.d("averageMark")?.takeIf { it > 0 }, sems)
    }

    fun rating(json: String, today: LocalDate = LocalDate.now(MINSK)): Rating {
        val o = parse(json).o() ?: throw IisError("ИИС прислал непонятный ответ")
        val deadlines = o["deadlines"].a().mapNotNull { it.o() }.associateBy { it.i("termHoursId") }
        val percents = o["percentageMarks"].a().mapNotNull { it.o() }
        val subjects = o["subjects"].a().mapNotNull { sv ->
            val s = sv.o() ?: return@mapNotNull null
            val abbrev = s.s("abbrev"); val name = s.s("name").ifEmpty { abbrev }
            val types = s["lessonTypes"].a().mapNotNull { tv ->
                val t = tv.o() ?: return@mapNotNull null
                val raw = t["lessons"].a().mapNotNull { it.o() }
                val lessons = raw.map { l ->
                    RatingLesson(
                        date = date(l.s("dateString", "date")),
                        marks = l["marks"].a().mapNotNull { mv -> mv.o()?.let { m -> m.i("mark")?.let { RatingMark(it, m.i("taskNumber")) } } },
                        missed = (l.i("gradebookOmissions") ?: l.i("gradeBookOmissions") ?: 0).coerceAtLeast(0),
                        subgroup = l.i("subGroup") ?: 0, controlPoint = l.s("controlPoint"),
                    )
                }.sortedBy { day(it.date) }
                val labs = deadlines[t.i("termHoursId")]?.let { g ->
                    val total = g.i("taskCount") ?: 0
                    if (total <= 0) return@let null
                    val tasks = lessons.flatMap { it.marks }.mapNotNull { it.task }.toSet()
                    val done = (if (tasks.isNotEmpty()) tasks.size else lessons.count { it.marks.isNotEmpty() }).coerceAtMost(total)
                    val dates = raw.associate { it.i("id") to day(date(it.s("dateString", "date"))) }
                    val due = g["deadlines"].a().mapNotNull { dv -> dv.o()?.let { d -> dates[d.i("lessonId")]?.let { it to d.i("taskNumber") } } }
                        .filter { (_, task) -> task == null || task !in tasks }.sortedBy { it.first }
                    val next = due.firstOrNull { !it.first.isBefore(today) }
                    val allDue = g["deadlines"].a().mapNotNull { dv -> dv.o()?.let { d -> d.i("taskNumber")?.let { k -> dates[d.i("lessonId")]?.let { k to it } } } }
                    val perTask = (1..total).map { k ->
                        val graded = lessons.flatMap { l -> l.marks.filter { it.task == k }.map { l.date to it.mark } }.lastOrNull()
                        IisTask(k, allDue.filter { it.first == k }.minOfOrNull { it.second }?.toString(), k in tasks, graded?.second,
                            graded?.first?.let { day(it)?.toString() })
                    }
                    LabProgress(done, total, next?.first?.format(DMY), next?.second, due.count { it.first.isBefore(today) && it.second != null }, perTask)
                }
                RatingType(t.s("abbrev"), lessons, labs)
            }
            val pct = percents.filter { p -> p.s("discipline").let { it.equals(abbrev, true) || it.equals(name, true) } }
                .mapNotNull { p -> p.d("number")?.let { date(p.s("date")) to it } }
            RatingSubject(abbrev, name, types, pct)
        }
        // сначала предметы, где больше всего отметок (при равенстве — как в ИИС)
        return Rating(subjects.sortedByDescending { it.marks.size })
    }

    fun omissions(monthlyJson: String?, unexcusedJson: String?, certsJson: String?): Omissions {
        val monthly = parse(monthlyJson).a().mapNotNull { it.o() }.map { it.s("month") to (it.i("omissionCount") ?: 0) }
            .filter { it.first.isNotEmpty() }
        val unexcused = parse(unexcusedJson).a().mapNotNull { it.o() }.map { x ->
            val subj = x["subject"].o()
            Omission(date(x.s("date")), subj.s("name").ifEmpty { subj.s("abbrev") }.ifEmpty { x.s("subject") },
                x.s("lessonTypeAbbrev"), x.i("hours") ?: 0, x.i("term") ?: 0)
        }.sortedByDescending { day(it.date) }
        val c = parse(certsJson)
        val list = (c as? JsonArray) ?: c.o()?.let { it["omissionDtoList"] ?: it["omissionList"] ?: it["omissions"] }
        val certs = list.a().mapNotNull { it.o() }.map { x -> OmissionCert(x.s("name"), date(x.s("dateFrom")), date(x.s("dateTo")), x.s("note")) }
        return Omissions(monthly, unexcused, certs)
    }

    fun group(json: String): GroupInfo {
        val o = parse(json).o() ?: throw IisError("ИИС прислал непонятный ответ")
        val c = o["studentGroupCuratorDto"].o()
        return GroupInfo(
            o.s("numberOfGroup"),
            c?.let { Curator(it.s("fio"), it.s("position"), it.s("phone"), it.s("email")) }?.takeIf { it.fio.isNotEmpty() },
            o["groupInfoStudentDto"].a().mapNotNull { it.o() }.map { GroupMate(it.s("fio"), it.s("position")) }.filter { it.fio.isNotEmpty() },
        )
    }

    fun certificates(json: String): Certificates = Certificates(parse(json).a().mapNotNull { it.o() }.map { x ->
        Certificate(x.i("number") ?: 0, x.s("provisionPlace"), date(x.s("dateOrder")), date(x.s("issueDate")),
            x.s("certificateType"), x.i("status") ?: 0, x.s("rejectionReason"))
    }.sortedByDescending { it.number })

    /** Загрузка раздела целиком (несколько запросов там, где ИИС раскладывает данные по разным адресам). */
    fun load(http: Http, session: IisSession, id: String): CabinetData {
        fun get(path: String) = Iis.get(http, session, path)
        // необязательные части: раздел недоступен (404 и т. п.) — просто без них; истёкшая сессия — наверх
        fun opt(path: String) = try { get(path) } catch (e: IisUnauthorized) { throw e } catch (e: Exception) { null }
        return when (id) {
            "cv" -> person(opt("/profiles/personal-profile"), opt("/personal-information"), session.profile)
            "markbook" -> markbook(get("/markbook"))
            "rating" -> rating(get("/personal-rating"))
            "omissions" -> omissions(opt("/omission-count-by-student-for-semester"), opt("/disrespectful-omissions-by-student"), opt("/omissions-by-student"))
            "group" -> group(get("/student-groups/user-group-info"))
            "certificates" -> certificates(get("/certificate"))
            else -> throw IisError("Неизвестный раздел")
        }
    }
}
