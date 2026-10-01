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
        val phys = Bsuir.on(tt, thu).single { it.subject == "Физ" }            // в четверг ещё консультации группы
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

    @Test fun labDoneThenSubmitted() {
        val s = by.zaberezh.forma.core.store.MemoryStore()
        val id = Study.addLab(s, "ОКГ", 1, 2)
        fun lab() = LAB.decode(s.get(id)!!)
        assertEquals(0, lab().stage)
        Study.toggleTask(s, id, 0); Study.toggleTask(s, id, 1)
        assertTrue(lab().done); assertTrue(lab().toSubmit); assertEquals(1, lab().stage)   // сделана — но её ещё сдавать
        Study.advance(s, id, java.time.LocalDate.of(2026, 10, 2))
        assertTrue(lab().submitted); assertEquals("2026-10-02", lab().submittedOn); assertEquals(2, lab().stage)
        Study.toggleTask(s, id, 1)                                                          // задание снова не готово —
        assertFalse(lab().submitted); assertEquals(0, lab().stage)                          // значит и не сдана
        val other = Study.addLab(s, "МА", 2, 0)
        Study.advance(s, other); Study.advance(s, other)
        assertEquals(listOf(0, 2), Study.labs(s).map { it.second.stage })                   // в работе выше сданных
    }

    @Test fun groupConsultationsAddedToSchedule() {
        // неделя 1 — с 1 сентября 2026 (вторник), четверг 3.09 — неделя 1, 10.09 — неделя 2, 17.09 — неделя 3
        val tt = by.zaberezh.forma.core.study.Timetable("653502", start = "2026-09-01", end = "2026-12-31")
        fun thu(d: Int) = by.zaberezh.forma.core.study.Bsuir.on(tt, java.time.LocalDate.of(2026, 9, d)).map { it.start to it.title }
        assertEquals(listOf("13:35" to "Консультация по математическому анализу", "15:00" to "Консультация по физике"), thu(3))
        assertEquals(listOf("13:35" to "Консультация по математическому анализу"), thu(10))
        assertEquals(2, thu(17).size)
        assertTrue(by.zaberezh.forma.core.study.Bsuir.on(tt.copy(group = "421701"), java.time.LocalDate.of(2026, 9, 3)).isEmpty())
    }

    @Test fun waterGlassesAndGoal() {
        val s = by.zaberezh.forma.core.store.MemoryStore()
        val day = LocalDate.of(2026, 10, 2)
        val noon = day.atTime(12, 0).atZone(by.zaberezh.forma.core.store.ZONE).toInstant().toEpochMilli()
        by.zaberezh.forma.core.body.saveWeight(s, day, 70.5, noon)
        assertEquals(2000, by.zaberezh.forma.core.daily.WaterLog.goalMl(s))        // 70,5 кг × 30 мл ≈ 8 стаканов
        repeat(3) { by.zaberezh.forma.core.daily.WaterLog.add(s, noon + it) }
        by.zaberezh.forma.core.daily.WaterLog.add(s, noon - 86_400_000)            // вчерашний не считается
        assertEquals(750, by.zaberezh.forma.core.daily.WaterLog.ml(s, day))
        assertEquals(37, by.zaberezh.forma.core.daily.WaterLog.percent(s, day))
        by.zaberezh.forma.core.daily.WaterLog.undo(s, day)
        assertEquals(500, by.zaberezh.forma.core.daily.WaterLog.ml(s, day))
    }

    @Test fun alarmFromFirstLesson() {
        fun tt(vararg starts: Pair<Int, String>) = by.zaberezh.forma.core.study.Timetable("1", starts.map { (wd, t) ->
            by.zaberezh.forma.core.study.Lesson("X", type = "ЛК", start = t, weekday = wd) })
        val mon = LocalDate.of(2026, 10, 5)
        assertEquals(mon.atTime(7, 10), Study.wakeAt(tt(1 to "08:30", 1 to "10:05"), mon))     // к первой — 7:10
        assertEquals(mon.atTime(8, 30), Study.wakeAt(tt(1 to "10:05"), mon))                   // ко второй — 8:30
        assertEquals(null, Study.wakeAt(tt(2 to "08:30"), mon))                                // в понедельник пар нет
        // консультации не будят
        val consult = by.zaberezh.forma.core.study.Timetable("1", listOf(by.zaberezh.forma.core.study.Lesson("МА", type = "Конс", start = "13:35", weekday = 1)))
        assertEquals(null, Study.wakeAt(consult, mon))
        // вечером понедельника — будильник на вторник
        assertEquals(mon.plusDays(1).atTime(7, 10), Study.nextWake(tt(1 to "08:30", 2 to "08:30"), mon.atTime(21, 0)))
    }
}
