package by.zaberezh.forma.core.study

import by.zaberezh.forma.core.store.Kind
import by.zaberezh.forma.core.store.Store
import by.zaberezh.forma.core.store.newId
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** ДЗ: записано на паре [from], показывается на следующем занятии по предмету — [due]. */
@Serializable
data class Homework(
    val subject: String,
    val type: String = "",
    val text: String,
    val from: String,          // дата пары, на которой записали
    val due: String,           // дата следующего занятия, к которому сделать
    val done: Boolean = false,
)

val HOMEWORK = Kind("study.hw", Homework.serializer())

@Serializable data class LabTask(val name: String, val done: Boolean = false)

/**
 * Лабораторная: предмет, номер, задания (каждое — сделано/нет), по желанию своё название.
 * Путь лабы: в работе → сделана (все задания готовы), но ещё не сдана → сдана преподавателю.
 */
@Serializable
data class Lab(
    val subject: String,
    val number: Int,
    val title: String = "",
    val tasks: List<LabTask> = emptyList(),
    val doneManual: Boolean = false,     // для лаб без заданий
    val created: Long = 0,
    val submitted: Boolean = false,      // сдана (защищена) преподавателю
    val submittedOn: String? = null,     // дата сдачи
    val due: String? = null,             // срок сдачи (ISO) — из ИИС
    val fromIis: Boolean = false,        // создана по данным ИИС
    val mark: Int? = null,               // отметка за лабу в ИИС
) {
    val total get() = tasks.size
    val doneCount get() = tasks.count { it.done }
    val done get() = if (tasks.isEmpty()) doneManual else tasks.all { it.done }
    /** Прогресс лабы 0..100 — по сделанным заданиям. */
    val percent get() = if (tasks.isEmpty()) (if (doneManual) 100 else 0) else doneCount * 100 / total
    val name get() = "ЛР $number" + if (title.isNotBlank()) " · $title" else ""
    /** Сделана, но ещё не сдана. */
    val toSubmit get() = done && !submitted
    /** 0 — в работе, 1 — нужно сдать, 2 — сдана. */
    val stage get() = when { submitted -> 2; done -> 1; else -> 0 }
}

val LAB = Kind("study.lab", Lab.serializer())

object Study {
    // ---------- ДЗ ----------
    /** Записать ДЗ на следующее занятие по предмету после [day]. Возвращает дату, когда оно появится. */
    fun addHomework(s: Store, tt: Timetable, lesson: Lesson, day: LocalDate, text: String, subgroup: Int = 0): LocalDate {
        val due = Bsuir.next(tt, lesson.subject, lesson.type, day, subgroup) ?: day.plusDays(7)
        HOMEWORK.save(s, Homework(lesson.subject, lesson.type, text.trim(), day.toString(), due.toString()))
        return due
    }

    fun homework(s: Store) = HOMEWORK.all(s)

    /** ДЗ к занятию по предмету на эту дату. */
    fun dueOn(s: Store, subject: String, day: LocalDate) = homework(s).filter { it.second.subject == subject && it.second.due == day.toString() }

    /** Записанное на этой паре (к следующему занятию). */
    fun writtenOn(s: Store, subject: String, day: LocalDate) = homework(s).filter { it.second.subject == subject && it.second.from == day.toString() }

    /** Несделанное ДЗ с сегодняшнего дня, по сроку. */
    fun open(s: Store, today: LocalDate) = homework(s).filter { !it.second.done && it.second.due >= today.toString() }.sortedBy { it.second.due }

    fun setHomeworkDone(s: Store, id: String, done: Boolean) {
        val e = s.get(id) ?: return
        HOMEWORK.save(s, HOMEWORK.decode(e).copy(done = done), ts = e.ts, id = id)
    }

    // ---------- лабы ----------
    fun labs(s: Store) = LAB.all(s).sortedWith(compareBy({ it.second.stage }, { it.second.subject }, { it.second.number }))

    fun addLab(s: Store, subject: String, number: Int, tasks: Int, title: String = "", now: Long = System.currentTimeMillis()): String {
        val id = "lab:" + newId()
        LAB.save(s, Lab(subject.trim(), number, title.trim(), List(tasks.coerceIn(0, 50)) { LabTask("Задание ${it + 1}") }, created = now), ts = now, id = id)
        return id
    }

    fun updateLab(s: Store, id: String, f: (Lab) -> Lab) {
        val e = s.get(id) ?: return
        LAB.save(s, f(LAB.decode(e)), ts = e.ts, id = id)
    }

    fun toggleTask(s: Store, id: String, i: Int) = updateLab(s, id) { l ->
        l.copy(tasks = l.tasks.mapIndexed { j, t -> if (j == i) t.copy(done = !t.done) else t }).keepConsistent()
    }

    /** Вся лаба — сделана / не сделана (все задания разом). Не сделанная — значит и не сдана. */
    fun setLabDone(s: Store, id: String, done: Boolean) = updateLab(s, id) { l ->
        l.copy(tasks = l.tasks.map { it.copy(done = done) }, doneManual = done).keepConsistent()
    }

    /** Сдана / не сдана. Сдать можно только сделанную: отметка «сдана» отмечает и все задания. */
    fun setSubmitted(s: Store, id: String, submitted: Boolean, today: LocalDate = LocalDate.now()) = updateLab(s, id) { l ->
        if (submitted) l.copy(tasks = l.tasks.map { it.copy(done = true) }, doneManual = true, submitted = true, submittedOn = today.toString())
        else l.copy(submitted = false, submittedOn = null)
    }

    /** Следующий шаг по кружку: в работе → сделана → сдана → снова «нужно сдать». */
    fun advance(s: Store, id: String, today: LocalDate = LocalDate.now()) {
        val l = s.get(id)?.let(LAB::decode) ?: return
        when (l.stage) {
            0 -> setLabDone(s, id, true)
            1 -> setSubmitted(s, id, true, today)
            else -> setSubmitted(s, id, false)
        }
    }

    private fun Lab.keepConsistent() = if (!done && submitted) copy(submitted = false, submittedOn = null) else this

    // ---------- подъём по первой паре ----------
    /**
     * Будильник на день: от первой пары (консультации не в счёт). К 1-й паре (до 8:30 включительно) — за [firstMin] минут
     * (8:30 → 7:10), к остальным — за [laterMin] (10:05 → 8:30). Нет пар — null.
     */
    fun wakeAt(tt: Timetable, day: LocalDate, subgroup: Int = 0, firstMin: Long = 80, laterMin: Long = 95): java.time.LocalDateTime? {
        val first = Bsuir.on(tt, day, subgroup).filter { !it.type.startsWith("Конс", true) }
            .mapNotNull { runCatching { java.time.LocalTime.parse(it.start) }.getOrNull() }.minOrNull() ?: return null
        return day.atTime(first.minusMinutes(if (first <= java.time.LocalTime.of(8, 30)) firstMin else laterMin))
    }

    /** Ближайший будильник после [now] (сегодня, если ещё не прошёл, иначе следующие дни с парами — до недели вперёд). */
    fun nextWake(tt: Timetable, now: java.time.LocalDateTime, subgroup: Int = 0): java.time.LocalDateTime? =
        (0L..7L).asSequence().mapNotNull { wakeAt(tt, now.toLocalDate().plusDays(it), subgroup) }.firstOrNull { it.isAfter(now) }

    // ---------- лабы из ИИС ----------
    private const val HIDDEN = "study.lab.hidden"

    private fun hidden(s: Store) = s.kvGet(HIDDEN)?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /** Удалить лабу; лабу из ИИС запоминаем, чтобы синхронизация не создала её снова. */
    fun deleteLab(s: Store, id: String) {
        val l = s.get(id)?.let(LAB::decode)
        if (l != null && l.fromIis) s.kvPut(HIDDEN, (hidden(s) + "${l.subject}|${l.number}").joinToString("\n"))
        s.delete(id)
    }

    /** Что изменила синхронизация: новые лабы и лабы, которые ИИС засчитал (есть отметка). */
    data class SyncResult(val created: List<String>, val graded: List<String>)

    /**
     * Лабы из «Успеваемости» ИИС: по каждому предмету с лабами — карточки «ЛР 1…N» со сроками.
     * Лаба с отметкой в ИИС — сдана (с датой и отметкой). Свои лабы с тем же предметом и номером не дублируются —
     * им только подставляются срок и отметка. Удалённые вручную лабы из ИИС больше не создаются.
     */
    fun syncFromIis(s: Store, rating: Rating, now: Long = System.currentTimeMillis()): SyncResult {
        val created = mutableListOf<String>(); val graded = mutableListOf<String>()
        val skip = hidden(s)
        val existing = LAB.all(s)
        for (subj in rating.subjects) for (t in subj.types) {
            val p = t.labs ?: continue
            for (task in p.tasks) {
                val key = "${subj.abbrev}|${task.number}"
                val mine = existing.firstOrNull { (_, l) -> l.subject.equals(subj.abbrev, true) && l.number == task.number }
                if (mine == null) {
                    if (key in skip) continue
                    val l = Lab(subj.abbrev, task.number, created = now, doneManual = task.done, submitted = task.done,
                        submittedOn = task.doneOn, due = task.due, fromIis = true, mark = task.mark)
                    LAB.save(s, l, ts = now, id = "lab:iis:${subj.abbrev}:${task.number}")
                    created += "${subj.abbrev} ЛР ${task.number}"
                } else {
                    val (e, l) = mine
                    var n = l.copy(due = task.due ?: l.due, mark = task.mark ?: l.mark)
                    if (task.done && !l.submitted) {
                        n = n.copy(tasks = n.tasks.map { it.copy(done = true) }, doneManual = true, submitted = true, submittedOn = task.doneOn)
                        graded += "${subj.abbrev} ЛР ${task.number}" + (task.mark?.let { " — $it" } ?: "")
                    }
                    if (n != l) LAB.save(s, n, ts = e.ts, id = e.id)
                }
            }
        }
        return SyncResult(created, graded)
    }

    /** Следующий номер лабы по предмету. */
    fun nextNumber(s: Store, subject: String) = (LAB.all(s).filter { it.second.subject == subject }.maxOfOrNull { it.second.number } ?: 0) + 1
}
