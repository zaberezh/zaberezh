package by.zaberezh.forma.core

import by.zaberezh.forma.core.daily.nextWaterTime
import by.zaberezh.forma.core.store.MemoryStore
import by.zaberezh.forma.core.study.Bsuir
import by.zaberezh.forma.core.study.LAB
import by.zaberezh.forma.core.study.Study
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudyTest {
    // сокращённый ответ iis.bsuir.by/api/v1/schedule?studentGroup=… (формат ИИС)
    private val json = """
    {"studentGroupDto":{"name":"653502"},"startDate":"01.09.2026","endDate":"27.12.2026",
     "schedules":{
      "Понедельник":[
        {"subject":"МА","subjectFullName":"Математический анализ","lessonTypeAbbrev":"ЛК","startLessonTime":"08:30","endLessonTime":"09:55",
         "weekNumber":[1,2,3,4],"numSubgroup":0,"auditories":["611-2 к."],"employees":[{"lastName":"Иванов","firstName":"Иван","middleName":"Иванович"}],
         "startLessonDate":"01.09.2026","endLessonDate":"20.12.2026","dateLesson":null,"note":null},
        {"subject":"ОАиП","subjectFullName":"Основы алгоритмизации и программирования","lessonTypeAbbrev":"ЛР","startLessonTime":"10:05","endLessonTime":"11:30",
         "weekNumber":[1,3],"numSubgroup":1,"auditories":["501-4 к."],"employees":[],"startLessonDate":"01.09.2026","endLessonDate":"20.12.2026"},
        {"subject":"ОАиП","lessonTypeAbbrev":"ЛР","startLessonTime":"10:05","endLessonTime":"11:30",
         "weekNumber":[2,4],"numSubgroup":2,"auditories":["501-4 к."],"employees":[]}
      ],
      "Четверг":[
        {"subject":"Физ","subjectFullName":"Физика","lessonTypeAbbrev":"ПЗ","startLessonTime":"11:40","endLessonTime":"13:05","weekNumber":[1,2,3,4],"numSubgroup":0,"unknownField":42}
      ]
     },
     "exams":[{"subject":"МА","lessonTypeAbbrev":"Экзамен","startLessonTime":"09:00","endLessonTime":"12:00","dateLesson":"10.01.2027","auditories":["611-2 к."]}]}
    """.trimIndent()

    private val mon = LocalDate.of(2026, 9, 28)   // понедельник; 1 сентября 2026 — вторник, неделя 1

    @Test fun parsesBsuirSchedule() {
        val tt = Bsuir.parse(json, "653502")
        assertEquals(5, tt.lessons.size)
        val ma = tt.lessons.first { it.subject == "МА" && it.type == "ЛК" }
        assertEquals("Математический анализ", ma.title); assertEquals(listOf("Иванов И. И."), ma.teachers)
        assertEquals("2026-09-01", ma.from); assertEquals("2026-01-10".replace("2026", "2027"), tt.lessons.first { it.type == "Экзамен" }.date)
        assertEquals(listOf("МА", "ОАиП", "Физ"), Bsuir.subjects(tt).sorted())
        assertEquals("ОАиП", Bsuir.subjects(tt).first())                        // предмет с лабами — первым
    }

    @Test fun weekCycleAndDayFilter() {
        val tt = Bsuir.parse(json, "653502")
        assertEquals(1, Bsuir.week(tt, LocalDate.of(2026, 9, 1)))
        assertEquals(1, Bsuir.week(tt, LocalDate.of(2026, 8, 31)))            // та же неделя
        assertEquals(1, Bsuir.week(tt, mon))                                    // 29 сен → 5-я неделя от начала → цикл 1
        assertEquals(2, Bsuir.week(tt, mon.plusDays(7)))
        // якорь с сервера важнее расчёта от 1 сентября
        assertEquals(3, Bsuir.week(tt.copy(anchorDate = mon.toString(), anchorWeek = 2), mon.plusDays(7)))
        val all = Bsuir.on(tt, mon)
        assertEquals(listOf("МА ЛК", "ОАиП ЛР"), all.map { "${it.subject} ${it.type}" })      // неделя 1 → лаба 1-й подгруппы
        assertEquals(listOf("МА"), Bsuir.on(tt, mon, subgroup = 2).map { it.subject })
        assertEquals(listOf("ОАиП"), Bsuir.on(tt, mon.plusDays(7), subgroup = 2).drop(1).map { it.subject })
        assertTrue(Bsuir.on(tt, LocalDate.of(2026, 12, 28)).isEmpty())        // после конца семестра
        assertEquals(listOf("Экзамен"), Bsuir.on(tt, LocalDate.of(2027, 1, 10)).map { it.type })
        assertEquals(2, Bsuir.parseWeek("2")); assertEquals(null, Bsuir.parseWeek("<html>"))
    }

    @Test fun homeworkAppearsOnNextLessonOfSubject() {
        val tt = Bsuir.parse(json, "653502")
        val s = MemoryStore()
        val thu = mon.plusDays(3)
        val phys = Bsuir.on(tt, thu).single()
        val due = Study.addHomework(s, tt, phys, thu, "задачи 1–5")
        assertEquals(thu.plusDays(7), due)                                       // следующий четверг
        assertEquals(1, Study.dueOn(s, "Физ", due).size)
        assertEquals(1, Study.writtenOn(s, "Физ", thu).size)
        val id = Study.open(s, thu).single().first.id
        Study.setHomeworkDone(s, id, true)
        assertTrue(Study.open(s, thu).isEmpty())
    }

    @Test fun labsProgressByTasks() {
        val s = MemoryStore()
        val id = Study.addLab(s, "ОАиП", 1, 4, "Сортировки")
        Study.toggleTask(s, id, 0); Study.toggleTask(s, id, 2)
        val lab = LAB.decode(s.get(id)!!)
        assertEquals(50, lab.percent); assertFalse(lab.done); assertEquals("ЛР 1 · Сортировки", lab.name)
        Study.setLabDone(s, id, true)
        assertTrue(LAB.decode(s.get(id)!!).done)
        assertEquals(2, Study.nextNumber(s, "ОАиП"))
        val empty = Study.addLab(s, "Физ", 1, 0)
        assertEquals(0, LAB.decode(s.get(empty)!!).percent)
        Study.setLabDone(s, empty, true)
        assertEquals(100, LAB.decode(s.get(empty)!!).percent)
        Study.updateLab(s, id) { it.copy(tasks = it.tasks.mapIndexed { i, t -> if (i == 0) t.copy(name = "Пузырёк") else t }) }
        assertEquals("Пузырёк", LAB.decode(s.get(id)!!).tasks[0].name)
    }

    @Test fun waterEveryHourInWindow() {
        val st = Settings()
        val wedMorning = LocalDateTime.of(2026, 9, 30, 9, 15)
        assertEquals(LocalDateTime.of(2026, 9, 30, 16, 0), nextWaterTime(st, wedMorning))      // будни с 16
        assertEquals(LocalDateTime.of(2026, 9, 30, 17, 0), nextWaterTime(st, wedMorning.withHour(16).withMinute(0)))
        assertEquals(LocalDateTime.of(2026, 10, 1, 16, 0), nextWaterTime(st, LocalDateTime.of(2026, 9, 30, 23, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 3, 11, 0), nextWaterTime(st, LocalDateTime.of(2026, 10, 2, 23, 30)))  // пт → сб 11:00
        assertEquals(LocalDateTime.of(2026, 10, 4, 23, 0), nextWaterTime(st, LocalDateTime.of(2026, 10, 4, 22, 1)))
    }
}
