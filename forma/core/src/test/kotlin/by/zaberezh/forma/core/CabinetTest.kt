package by.zaberezh.forma.core

import by.zaberezh.forma.core.study.Cabinet
import by.zaberezh.forma.core.study.Certificates
import by.zaberezh.forma.core.study.Http
import by.zaberezh.forma.core.study.HttpResp
import by.zaberezh.forma.core.study.IisSession
import by.zaberezh.forma.core.study.IisUnauthorized
import by.zaberezh.forma.core.study.Omissions
import by.zaberezh.forma.core.study.Person
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CabinetTest {
    @Test fun markbookSemestersNewestFirst() {
        val m = Cabinet.markbook("""{"number":"65350034","averageMark":0.0,"markPages":{
            "1":{"averageMark":8.5,"marks":[{"subject":"МА","formOfControl":"Экзамен","fullSubject":"Математический анализ","hours":"120","credits":3.0,
                "mark":"9","date":"15.01.2026","teacher":"Иванов И. И.","commonMark":7.1,"commonRetakes":0.1,"retakesCount":0},
                {"subject":"ФК","formOfControl":"Зачет","fullSubject":"Физическая культура","hours":"60","credits":null,"mark":"зачтено","date":null,"teacher":null,"retakesCount":1}]},
            "2":{"averageMark":0.0,"marks":[{"subject":"ОКГ","formOfControl":"Экзамен","fullSubject":"Основы компьютерной графики","hours":"90","mark":"","retakesCount":0}]}}}""")
        assertEquals("65350034", m.number)
        assertNull(m.average)                                // 0.0 — ещё нет оценок, а не «ноль»
        assertEquals(listOf(2, 1), m.semesters.map { it.number })
        val ma = m.semesters[1].marks[0]
        assertEquals("Математический анализ", ma.full); assertEquals("Экзамен", ma.form); assertEquals(3.0, ma.credits)
        assertEquals("15.01.2026", ma.date)
        assertEquals(1, m.semesters[1].marks[1].retakes)
        assertNull(m.semesters[0].average)
    }

    @Test fun ratingMarksLabsAndDeadlines() {
        val r = Cabinet.rating("""{"subjects":[
            {"id":1,"abbrev":"ОКГ","name":"Основы компьютерной графики","lessonTypes":[
              {"id":4,"abbrev":"ЛР","termHoursId":77,"lessons":[
                {"id":10,"dateString":"10.09.2026","gradebookOmissions":0,"subGroup":2,"marks":[{"mark":9,"taskNumber":1}],"controlPoint":""},
                {"id":11,"dateString":"24.09.2026","gradebookOmissions":2,"subGroup":2,"marks":[],"controlPoint":""},
                {"id":12,"dateString":"08.10.2026","gradebookOmissions":0,"subGroup":2,"marks":[],"controlPoint":""},
                {"id":13,"dateString":"22.10.2026","gradebookOmissions":0,"subGroup":2,"marks":[],"controlPoint":""}]},
              {"id":2,"abbrev":"ЛК","termHoursId":78,"lessons":[
                {"id":20,"dateString":"03.09.2026","gradebookOmissions":0,"subGroup":0,"marks":[{"mark":7,"taskNumber":null}],"controlPoint":"КТ1"}]}]},
            {"id":2,"abbrev":"МА","name":"Математический анализ","lessonTypes":[]}],
          "deadlines":[{"termHoursId":77,"taskCount":4,"deadlines":[{"lessonId":10,"taskNumber":1},{"lessonId":11,"taskNumber":2},{"lessonId":12,"taskNumber":3},{"lessonId":13,"taskNumber":4}]}],
          "percentageMarks":[{"discipline":"ОКГ","date":"12.09.2026","number":85.0}]}""", today = LocalDate.of(2026, 10, 1))
        val okg = r.subjects[0]
        assertEquals(8.0, okg.average); assertEquals(2, okg.missed)
        val labs = okg.types[0].labs!!
        assertEquals(1, labs.done); assertEquals(4, labs.total)
        assertEquals(1, labs.overdue)                         // задание 2 к 24.09 не сдано
        assertEquals("08.10.2026", labs.next); assertEquals(3, labs.nextTask)
        assertNull(okg.types[1].labs)
        assertEquals(listOf("12.09.2026" to 85.0), okg.percents)
        assertNull(r.subjects[1].average)
        assertEquals(8.0, r.average); assertEquals(1, r.labsDone); assertEquals(4, r.labsTotal); assertEquals(1, r.labsOverdue)
    }

    @Test fun omissionsFromThreeEndpoints() {
        val o = Cabinet.omissions(
            """[{"month":"сентябрь 2026","omissionCount":4},{"month":"октябрь 2026","omissionCount":0}]""",
            """[{"date":"12.09.2026","subject":{"id":1,"name":"Математический анализ","abbrev":"МА"},"lessonTypeAbbrev":"ЛК","hours":2,"term":1},
                {"date":"20.09.2026","subject":{"id":2,"name":"Физика","abbrev":"Физ"},"lessonTypeAbbrev":"ПЗ","hours":2,"term":1}]""",
            """{"omissionDtoList":[{"id":1,"dateFrom":1788220800000,"dateTo":1788480000000,"note":"ОРВИ","name":"Медицинская справка","term":1}],"faculty":"ФКСиС"}""",
        )
        assertEquals(4, o.total)
        assertEquals(listOf("20.09.2026", "12.09.2026"), o.unexcused.map { it.date })
        assertEquals("Математический анализ", o.unexcused[1].subject)
        assertEquals("Медицинская справка", o.certificates.single().name)
        assertTrue(o.certificates.single().from.matches(Regex("""\d{2}\.\d{2}\.\d{4}""")))
        assertEquals(0, Cabinet.omissions(null, null, """{"faculty":"ФКСиС"}""").total)
    }

    @Test fun groupCuratorAndStudents() {
        val g = Cabinet.group("""{"numberOfGroup":"653502","studentGroupCuratorDto":{"position":"Куратор","fio":"Шепетюк Виталий Васильевич","phone":"+375172938921","email":null,"urlId":"x"},
            "groupInfoStudentDto":[{"position":"Студент","fio":"Березко Александр Сергеевич","urlId":null},{"position":"Староста","fio":"Вашкевич Сергей Михайлович","urlId":null}]}""")
        assertEquals("653502", g.number)
        assertEquals("Шепетюк Виталий Васильевич", g.curator!!.fio); assertEquals("", g.curator!!.email)
        assertEquals(listOf(false, true), g.students.map { it.head })
    }

    @Test fun certificatesWithStatus() {
        val c = Cabinet.certificates("""[{"id":5,"number":376,"provisionPlace":"по месту работы родителей","dateOrder":"02.09.2026","issueDate":"07.09.2026","certificateType":"обычная","status":1,"rejectionReason":null,"isByStudent":true},
            {"id":6,"number":378,"provisionPlace":"иное (профсоюз)","dateOrder":"02.09.2026","issueDate":null,"certificateType":"гербовая","status":2,"isByStudent":true}]""")
        assertEquals(listOf(378, 376), c.items.map { it.number })
        assertEquals(listOf("обрабатывается", "готова"), c.items.map { it.statusText })
        assertEquals("", c.items[0].issued)
    }

    @Test fun personMergesProfileInfoAndLogin() {
        val p = Cabinet.person(
            """{"firstName":"Илья","lastName":"Забережный","middleName":"Алексеевич","belarusianFirstName":"Ілья","belarusianLastName":"Забярэжны",
                "belarusianMiddleName":"Аляксеевіч","birthDate":"2008-03-14","photoUrl":"http://iis.bsuir.by/api/v1/photo/1","course":1,"faculty":"ФКСиС",
                "speciality":"Информатика и технологии программирования","studentGroup":"653502","rating":0}""",
            """{"degree":1,"email":"me@example.com","phone":"+375330000000","course":1,"enablePractice":false,"graduating":false,"kt":false,"re":false,"belarusian":true}""",
            """{"username":"65350034","fio":"Забережный Илья Алексеевич","group":"653502"}""",
        )
        assertEquals("Забережный Илья Алексеевич", p.fio)
        assertEquals("Забярэжны Ілья Аляксеевіч", p.fioBy)
        assertEquals("14.03.2008", p.birth)
        assertEquals("me@example.com", p.email); assertEquals(1, p.course); assertNull(p.rating)
        assertEquals("https://iis.bsuir.by/api/v1/photo/1", p.photo)
        val onlyLogin = Cabinet.person(null, null, """{"fio":"Забережный Илья Алексеевич","group":"653502","photoUrl":"https://iis.bsuir.by/p.jpg"}""")
        assertEquals("653502", onlyLogin.group); assertEquals("https://iis.bsuir.by/p.jpg", onlyLogin.photo)
    }

    @Test fun loadSectionsWithOptionalParts() {
        val calls = mutableListOf<String>()
        val http = Http { _, url, _, _ ->
            calls += url.substringAfter("/api/v1")
            when {
                url.endsWith("/omission-count-by-student-for-semester") -> HttpResp(200, """[{"month":"сентябрь 2026","omissionCount":2}]""")
                url.endsWith("/certificate") -> HttpResp(200, "[]")
                url.endsWith("/markbook") -> HttpResp(401, "")
                else -> HttpResp(404, "")
            }
        }
        val s = IisSession("SESSION=1", """{"fio":"Забережный Илья Алексеевич"}""")
        assertEquals(2, (Cabinet.load(http, s, "omissions") as Omissions).total)     // 404 у остальных частей — не ошибка
        assertTrue((Cabinet.load(http, s, "certificates") as Certificates).items.isEmpty())
        assertEquals("Забережный Илья Алексеевич", (Cabinet.load(http, s, "cv") as Person).fio)
        assertFailsWith<IisUnauthorized> { Cabinet.load(http, s, "markbook") }
        assertTrue("/personal-rating" !in calls)
        assertTrue(Cabinet.group("""{"numberOfGroup":"1"}""").students.isEmpty())
    }
}
