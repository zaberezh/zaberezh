package by.zaberezh.forma.core

import by.zaberezh.forma.core.study.Bsuir
import by.zaberezh.forma.core.study.Http
import by.zaberezh.forma.core.study.HttpResp
import by.zaberezh.forma.core.study.Iis
import by.zaberezh.forma.core.study.IisError
import by.zaberezh.forma.core.study.IisUnauthorized
import by.zaberezh.forma.core.study.JsonView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IisTest {
    private val sent = mutableListOf<Triple<String, String, Map<String, String>>>()
    private val http = Http { method, url, body, headers ->
        sent += Triple(method, url, headers)
        when {
            url.endsWith("/auth/login") && body!!.contains("\"password\":\"right\"") ->
                HttpResp(200, """{"fio":"Иванов Иван Иванович","group":"653502","photoUrl":"https://iis.bsuir.by/x.jpg","username":"65350001"}""",
                    listOf("JSESSIONID=abc; Path=/; HttpOnly; Secure", "XSRF-TOKEN=t0k; Path=/"))
            url.endsWith("/auth/login") -> HttpResp(401, "")
            headers["Cookie"]?.contains("JSESSIONID=abc") != true -> HttpResp(401, "")
            url.endsWith("/markbook") -> HttpResp(200, """{"averageMark":8.4,"semesters":[{"semester":1,"marks":[{"subject":"МА","mark":"9","hours":120,"id":5}]}]}""")
            else -> HttpResp(404, "")
        }
    }

    @Test fun loginKeepsOnlySessionCookie() {
        val s = Iis.login(http, "65350001", "right")
        assertEquals("JSESSIONID=abc; XSRF-TOKEN=t0k", s.cookie)
        assertFalse(s.cookie.contains("right"))
        assertEquals("Иванов Иван Иванович", Iis.profile(s.profile)!!.fio)
        assertFailsWith<IisError> { Iis.login(http, "65350001", "wrong") }
        Iis.get(http, s, "/markbook")
        assertEquals("t0k", sent.last().third["X-XSRF-TOKEN"])
        assertFailsWith<IisUnauthorized> { Iis.get(http, s.copy(cookie = "JSESSIONID=old"), "/markbook") }
        assertFailsWith<IisError> { Iis.get(http, s, "/nope") }
        assertTrue(sent.all { it.second.startsWith("https://iis.bsuir.by/api/v1/") })
        assertFailsWith<IllegalArgumentException> { Iis.jdk.send("GET", "https://evil.example/api", null, emptyMap()) }
    }

    @Test fun jsonViewShowsAnyAnswerReadably() {
        val cards = JsonView.cards("""{"averageMark":8.4,"semesters":[{"semester":1,"marks":[{"subject":"МА","mark":"9","hours":120,"id":5}]}]}""")
        val root = cards.single()
        assertEquals(listOf("Средний балл" to "8.4"), root.rows)
        val sem = root.children.single()
        assertEquals("1", sem.title)
        val ma = sem.children.single()
        assertEquals("МА", ma.title)
        assertEquals(listOf("Предмет" to "МА", "Отметка" to "9", "Часы" to "120"), ma.rows)   // id скрыт
        assertEquals("Total omission hours".lowercase().replaceFirstChar { it.uppercase() }, JsonView.label("totalOmissionHours"))
        assertEquals(2, JsonView.cards("""[{"name":"Справка 1","status":"готова"},{"name":"Справка 2"}]""").size)
    }

    @Test fun teacherDetailsFromSchedule() {
        val tt = Bsuir.parse("""{"schedules":{"Вторник":[{"subject":"ОКГ","subjectFullName":"Основы компьютерной графики","lessonTypeAbbrev":"ЛР",
            "startLessonTime":"12:00","endLessonTime":"13:25","weekNumber":[1,2,3,4],"numSubgroup":2,"auditories":["507-2 к."],
            "employees":[{"lastName":"Русина","firstName":"Анна","middleName":"Викторовна","rank":"доцент","degree":"к.т.н.","photoLink":"https://iis.bsuir.by/api/v1/employees/photo/1"}]}]}}""", "653502")
        val l = tt.lessons.single()
        assertEquals(listOf("Русина А. В."), l.teachers)
        assertEquals(listOf("Русина Анна Викторовна"), l.teachersFull)
        assertEquals(listOf("доцент, к.т.н."), l.teacherInfo)
        assertEquals("Лабораторная работа", l.typeFull)
        assertTrue(l.photos.single().startsWith("https://"))
    }

    @Test fun browserHeadersAndRetryOn503() {
        var calls = 0
        val flaky = Http { _, _, _, h ->
            calls++
            assertTrue(h["User-Agent"]!!.startsWith("Mozilla/")); assertEquals("https://iis.bsuir.by", h["Origin"])
            if (calls == 1) HttpResp(503, "") else HttpResp(200, "{}", listOf("JSESSIONID=z; Path=/"))
        }
        assertEquals("JSESSIONID=z", Iis.login(flaky, "1", "p", pause = {}).cookie)
        assertEquals(2, calls)
        val down = Http { _, _, _, _ -> HttpResp(503, "") }
        val e = assertFailsWith<IisError> { Iis.login(down, "1", "p", pause = {}) }
        assertTrue(e.message!!.contains("503"))
    }

    @Test fun photoLinksNormalized() {
        assertEquals("https://iis.bsuir.by/api/v1/employees/photo/1", Iis.photoUrl("http://iis.bsuir.by/api/v1/employees/photo/1"))
        assertEquals("https://iis.bsuir.by/api/v1/employees/photo/1", Iis.photoUrl("/api/v1/employees/photo/1"))
        assertEquals("https://iis.bsuir.by/p.jpg", Iis.photoUrl("//iis.bsuir.by/p.jpg"))
        assertEquals("", Iis.photoUrl(null)); assertEquals("", Iis.photoUrl("data:image/png;base64,AAA"))
    }

    @Test fun loginBodyHasOnlyCredentials() {
        var sentBody = ""
        val h = Http { _, _, b, _ -> sentBody = b!!; HttpResp(200, "{}", listOf("SESSION=s1; Path=/api/v1; Secure; HttpOnly")) }
        assertEquals("SESSION=s1", Iis.login(h, " 65350034 ", "p").cookie)
        assertEquals("""{"username":"65350034","password":"p"}""", sentBody)
        val e = assertFailsWith<IisError> { Iis.login(Http { _, _, _, _ -> HttpResp(503, """{"message":"Service down"}""") }, "1", "p", pause = {}) }
        assertTrue(e.message!!.contains("Service down") && e.message!!.contains("через сайт"))
    }

    @Test fun sessionFromSiteCookies() {
        val h = Http { _, url, _, hd ->
            if (url.endsWith("/personal-information") && hd["Cookie"] == "SESSION=ok")
                HttpResp(200, """{"firstName":"Иван","lastName":"Иванов","middleName":"Иванович","photo":"https://iis.bsuir.by/p.jpg","education":[{"group":"653502"}]}""")
            else HttpResp(401, "")
        }
        assertEquals(null, Iis.sessionFromCookies(h, ""))
        assertEquals(null, Iis.sessionFromCookies(h, "SESSION=bad"))
        val s = Iis.sessionFromCookies(h, "SESSION=ok")!!
        val p = Iis.profile(s.profile)!!
        assertEquals("Иванов Иван Иванович", p.fio); assertEquals("653502", p.group); assertEquals("https://iis.bsuir.by/p.jpg", p.photo)
    }

    @Test fun teacherPhotoByIdWhenNoLink() {
        val tt = Bsuir.parse("""{"schedules":{"Среда":[{"subject":"МА","lessonTypeAbbrev":"ЛК","startLessonTime":"09:00","endLessonTime":"10:20",
            "weekNumber":[1,2,3,4],"employees":[{"id":500434,"lastName":"Иванов","firstName":"Иван","middleName":"Иванович","photoLink":null},
            {"lastName":"Петров","firstName":"Пётр","photoLink":"null/api/v1/employees/photo/7"}]}]}}""", "653502")
        assertEquals(listOf("https://iis.bsuir.by/api/v1/employees/photo/500434", "https://iis.bsuir.by/api/v1/employees/photo/7"), tt.lessons.single().photos)
        assertEquals(Bsuir.FORMAT, tt.format)
    }
}
