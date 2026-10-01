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

/** Лабораторная: предмет, номер, задания (каждое — сделано/нет), по желанию своё название. */
@Serializable
data class Lab(
    val subject: String,
    val number: Int,
    val title: String = "",
    val tasks: List<LabTask> = emptyList(),
    val doneManual: Boolean = false,     // для лаб без заданий
    val created: Long = 0,
) {
    val total get() = tasks.size
    val doneCount get() = tasks.count { it.done }
    val done get() = if (tasks.isEmpty()) doneManual else tasks.all { it.done }
    /** Прогресс лабы 0..100 — по сделанным заданиям. */
    val percent get() = if (tasks.isEmpty()) (if (doneManual) 100 else 0) else doneCount * 100 / total
    val name get() = "ЛР $number" + if (title.isNotBlank()) " · $title" else ""
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
    fun labs(s: Store) = LAB.all(s).sortedWith(compareBy({ it.second.done }, { it.second.subject }, { it.second.number }))

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
        l.copy(tasks = l.tasks.mapIndexed { j, t -> if (j == i) t.copy(done = !t.done) else t })
    }

    /** Вся лаба — сделана / не сделана (все задания разом). */
    fun setLabDone(s: Store, id: String, done: Boolean) = updateLab(s, id) { l ->
        l.copy(tasks = l.tasks.map { it.copy(done = done) }, doneManual = done)
    }

    /** Следующий номер лабы по предмету. */
    fun nextNumber(s: Store, subject: String) = (LAB.all(s).filter { it.second.subject == subject }.maxOfOrNull { it.second.number } ?: 0) + 1
}
