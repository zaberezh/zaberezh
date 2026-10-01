package by.zaberezh.forma.core

import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.study.Cabinet
import by.zaberezh.forma.core.study.IisWatch
import by.zaberezh.forma.core.study.LAB
import by.zaberezh.forma.core.study.Study
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IisSyncTest {
    private fun rating(graded: String) = Cabinet.rating("""{"subjects":[
        {"id":1,"abbrev":"ОКГ","name":"Основы компьютерной графики","lessonTypes":[
          {"id":4,"abbrev":"ЛР","termHoursId":77,"lessons":[
            {"id":10,"dateString":"10.09.2026","gradebookOmissions":0,"subGroup":2,"marks":[$graded],"controlPoint":""},
            {"id":11,"dateString":"24.09.2026","gradebookOmissions":2,"subGroup":2,"marks":[],"controlPoint":""},
            {"id":12,"dateString":"08.10.2026","gradebookOmissions":0,"subGroup":2,"marks":[],"controlPoint":""}]}]}],
      "deadlines":[{"termHoursId":77,"taskCount":3,"deadlines":[{"lessonId":10,"taskNumber":1},{"lessonId":11,"taskNumber":2},{"lessonId":12,"taskNumber":3}]}],
      "percentageMarks":[]}""", today = LocalDate.of(2026, 10, 1))

    @Test fun labsCreatedFromIisAndGradedLater() {
        val s = MemoryStore()
        val r = Study.syncFromIis(s, rating("""{"mark":9,"taskNumber":1}"""))
        assertEquals(listOf("ОКГ ЛР 1", "ОКГ ЛР 2", "ОКГ ЛР 3"), r.created)
        val labs = Study.labs(s).map { it.second }
        assertEquals(listOf(2, 3), labs.filter { it.stage == 0 }.map { it.number })
        val first = labs.single { it.number == 1 }
        assertTrue(first.submitted); assertEquals(9, first.mark); assertEquals("2026-09-10", first.submittedOn)
        assertEquals("2026-09-24", labs.single { it.number == 2 }.due)
        // повторная синхронизация ничего не дублирует; новая отметка — лаба засчитана
        val again = Study.syncFromIis(s, rating("""{"mark":9,"taskNumber":1},{"mark":8,"taskNumber":2}"""))
        assertTrue(again.created.isEmpty()); assertEquals(listOf("ОКГ ЛР 2 — 8"), again.graded)
        assertEquals(3, LAB.all(s).size)
        // удалённая вручную лаба из ИИС не возвращается
        Study.deleteLab(s, "lab:iis:ОКГ:3")
        Study.syncFromIis(s, rating("""{"mark":9,"taskNumber":1}"""))
        assertEquals(2, LAB.all(s).size)
    }

    @Test fun ownLabGetsDueAndGrade() {
        val s = MemoryStore()
        val id = Study.addLab(s, "ОКГ", 1, 4, "Растр")
        Study.syncFromIis(s, rating("""{"mark":7,"taskNumber":1}"""))
        val mine = LAB.decode(s.get(id)!!)
        assertTrue(mine.submitted); assertEquals("Растр", mine.title); assertEquals(4, mine.tasks.count { it.done })
        assertEquals(3, LAB.all(s).size)                                       // своя ЛР 1 + из ИИС ЛР 2 и 3
    }

    @Test fun watchReportsOnlyNewMarksAndOmissions() {
        val s = MemoryStore()
        assertTrue(IisWatch.check(s, rating("")).empty)                        // первый снимок — тишина
        val news = IisWatch.check(s, rating("""{"mark":9,"taskNumber":1}"""))
        assertEquals(listOf("Основы компьютерной графики · ЛР 10.09 — 9 (ЛР 1)"), news.marks)
        assertTrue(news.omissions.isEmpty())                                    // пропуск 24.09 был и раньше
        assertTrue(IisWatch.check(s, rating("""{"mark":9,"taskNumber":1}""")).empty)
    }
}
