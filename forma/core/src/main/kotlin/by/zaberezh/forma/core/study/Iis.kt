package by.zaberezh.forma.core.study

import by.zaberezh.forma.core.store.JSON
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/** Ответ HTTP: код, тело, значения Set-Cookie. */
data class HttpResp(val code: Int, val body: String, val cookies: List<String> = emptyList())

fun interface Http { fun send(method: String, url: String, body: String?, headers: Map<String, String>): HttpResp }

class IisUnauthorized : RuntimeException("Сессия ИИС закончилась — войди снова")
class IisError(msg: String) : RuntimeException(msg)

/** Сессия ЛК: только cookie сессии (пароль нигде не хранится) и данные профиля из ответа на вход. */
data class IisSession(val cookie: String, val profile: String = "")

data class IisSection(val id: String, val title: String, val path: String, val about: String)

/**
 * Личный кабинет ИИС БГУИР (iis.bsuir.by/api/v1). Безопасность:
 * - запросы только на https://iis.bsuir.by (другой адрес — исключение), без редиректов (пароль не утечёт на другой хост);
 * - пароль уходит один раз при входе и нигде не сохраняется; дальше — только cookie сессии;
 * - ответы с паролем/куками не логируются.
 */
object Iis {
    const val HOST = "https://iis.bsuir.by"
    const val BASE = "$HOST/api/v1"

    val SECTIONS = listOf(
        IisSection("cv", "Персональный листок", "/personal-cv", "ФИО, группа, контакты"),
        IisSection("markbook", "Зачётная книжка", "/markbook", "оценки по семестрам, средний балл"),
        IisSection("gradebook", "Успеваемость", "/grade-book", "текущие отметки по предметам"),
        IisSection("omissions", "Пропуски", "/omissions-by-student", "пропущенные часы"),
        IisSection("group", "Моя группа", "/student-groups/user-group-info", "куратор, староста, студенты"),
        IisSection("certificates", "Справки", "/certificate", "заказанные справки"),
    )

    private fun cookieOf(setCookies: List<String>) = setCookies.map { it.substringBefore(';').trim() }.filter { '=' in it && !it.endsWith("=") }

    private fun headers(session: IisSession?): Map<String, String> {
        val h = linkedMapOf("Accept" to "application/json", "Content-Type" to "application/json")
        if (session != null) {
            h["Cookie"] = session.cookie
            // Spring Security: XSRF-токен из куки дублируется в заголовке
            session.cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("XSRF-TOKEN=") }?.let { h["X-XSRF-TOKEN"] = it.substringAfter('=') }
        }
        return h
    }

    /** Вход по номеру студенческого и паролю ИИС. */
    fun login(http: Http, username: String, password: String): IisSession {
        val body = buildJsonObject { put("username", username.trim()); put("password", password); put("rememberMe", true) }.toString()
        val r = http.send("POST", "$BASE/auth/login", body, headers(null))
        when {
            r.code == 401 || r.code == 403 || r.code == 400 -> throw IisError("Неверный логин или пароль")
            r.code !in 200..299 -> throw IisError("ИИС ответил ошибкой ${r.code}")
        }
        val cookie = cookieOf(r.cookies)
        if (cookie.isEmpty()) throw IisError("ИИС не выдал сессию — попробуй позже")
        return IisSession(cookie.joinToString("; "), r.body)
    }

    /** GET раздела. 401/403 — сессия истекла. */
    fun get(http: Http, session: IisSession, path: String): String {
        val r = http.send("GET", BASE + path, null, headers(session))
        return when {
            r.code == 401 || r.code == 403 -> throw IisUnauthorized()
            r.code == 404 -> throw IisError("Раздел недоступен в ИИС")
            r.code !in 200..299 -> throw IisError("ИИС ответил ошибкой ${r.code}")
            else -> r.body
        }
    }

    fun logout(http: Http, session: IisSession) { runCatching { http.send("GET", "$BASE/auth/logout", null, headers(session)) } }

    /** HTTP через HttpURLConnection — только на iis.bsuir.by, без перехода по редиректам. */
    val jdk = Http { method, url, body, headers ->
        require(url.startsWith("$HOST/")) { "Запрос не к iis.bsuir.by запрещён" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 15000; c.instanceFollowRedirects = false
            c.useCaches = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..399) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResp(code, text, c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value })
        } finally { c.disconnect() }
    }

    // ---------- профиль из ответа на вход ----------
    data class Profile(val fio: String, val group: String, val photo: String, val extra: List<Pair<String, String>>)

    fun profile(json: String): Profile? = runCatching {
        val o = JSON.parseToJsonElement(json) as? JsonObject ?: return null
        fun s(vararg k: String) = k.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        Profile(
            fio = s("fio", "fullName", "name") ?: listOfNotNull(s("lastName"), s("firstName"), s("middleName")).joinToString(" "),
            group = s("group", "groupName", "studentGroup").orEmpty(),
            photo = s("photoUrl", "photoLink", "photo").orEmpty().takeIf { it.startsWith("http") }.orEmpty(),
            extra = JsonView.cards(json).firstOrNull()?.rows.orEmpty().take(6),
        )
    }.getOrNull()
}

/** Карточка для показа любого JSON ответа ИИС в дизайне приложения. */
data class JCard(val title: String, val rows: List<Pair<String, String>>, val children: List<JCard> = emptyList())

/**
 * Универсальный показ ответов ИИС: объект — карточка «поле … значение», массив объектов — карточки,
 * вложенные объекты — вложенные карточки. Служебные поля (id, base64, ссылки) скрываются.
 */
object JsonView {
    private val NAMES = mapOf(
        "fio" to "ФИО", "fullname" to "ФИО", "lastname" to "Фамилия", "firstname" to "Имя", "middlename" to "Отчество",
        "birthdate" to "Дата рождения", "birthday" to "Дата рождения", "group" to "Группа", "groupname" to "Группа", "studentgroup" to "Группа",
        "faculty" to "Факультет", "facultyabbrev" to "Факультет", "speciality" to "Специальность", "specialityname" to "Специальность",
        "course" to "Курс", "email" to "Почта", "phone" to "Телефон", "mobilephone" to "Телефон", "address" to "Адрес",
        "subject" to "Предмет", "subjectname" to "Предмет", "lessonname" to "Предмет", "mark" to "Отметка", "marks" to "Отметки",
        "average" to "Средний балл", "averagemark" to "Средний балл", "gpa" to "Средний балл", "semester" to "Семестр", "term" to "Семестр",
        "date" to "Дата", "hours" to "Часы", "totalhours" to "Всего часов", "teacher" to "Преподаватель", "employee" to "Преподаватель",
        "type" to "Тип", "lessontype" to "Тип занятия", "formofcontrol" to "Форма контроля", "commonmark" to "Итог",
        "omissions" to "Пропуски", "respectful" to "По уважительной", "notrespectful" to "Без уважительной", "count" to "Количество",
        "number" to "Номер", "name" to "Название", "title" to "Название", "status" to "Статус", "comment" to "Комментарий",
        "curator" to "Куратор", "headman" to "Староста", "students" to "Студенты", "rating" to "Рейтинг", "position" to "Место",
        "certificates" to "Справки", "reference" to "Справка", "provisionplace" to "Куда", "dateorder" to "Дата заказа",
    )
    private val TITLE_KEYS = listOf("subject", "subjectName", "lessonName", "name", "title", "fio", "semester", "term", "number", "date")
    private val HIDE = setOf("id", "photo", "photourl", "photolink", "password", "token", "cookie", "hash")

    fun label(key: String): String = NAMES[key.lowercase()]
        ?: key.replace(Regex("([a-zа-я])([A-ZА-Я])"), "$1 $2").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

    private fun prim(e: JsonElement): String? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() && it.length < 300 }?.let { if (it == "true") "да" else if (it == "false") "нет" else it }
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.let(::prim) }.takeIf { it.size == e.size && it.isNotEmpty() }?.joinToString(", ")
        else -> null
    }

    private fun hidden(k: String, v: JsonElement) = k.lowercase() in HIDE || k.lowercase().endsWith("id") && v is JsonPrimitive ||
        ((v as? JsonPrimitive)?.contentOrNull?.let { it.startsWith("http") || it.length > 300 } == true)

    private fun card(title: String, o: JsonObject, depth: Int): JCard {
        val rows = mutableListOf<Pair<String, String>>(); val kids = mutableListOf<JCard>()
        for ((k, v) in o) {
            if (hidden(k, v)) continue
            val p = prim(v)
            when {
                p != null -> rows += label(k) to p
                depth >= 4 -> {}
                v is JsonObject -> card(label(k), v, depth + 1).takeIf { it.rows.isNotEmpty() || it.children.isNotEmpty() }?.let { kids += it }
                v is JsonArray -> v.filterIsInstance<JsonObject>().forEachIndexed { i, x -> kids += card(titleOf(x) ?: "${label(k)} ${i + 1}", x, depth + 1) }
            }
        }
        return JCard(title, rows, kids)
    }

    private fun titleOf(o: JsonObject) = TITLE_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }

    /** Ответ раздела → карточки. Непонятный формат — одна карточка с текстом. */
    fun cards(json: String): List<JCard> {
        val e = runCatching { JSON.parseToJsonElement(json) }.getOrNull() ?: return listOf(JCard("Ответ", listOf("" to json.take(500))))
        return when (e) {
            is JsonObject -> {
                val c = card("", e, 0)
                // объект-обёртка с одним списком — сразу показываем элементы списка
                if (c.rows.isEmpty() && c.children.isNotEmpty()) c.children else listOf(c)
            }
            is JsonArray -> e.mapIndexedNotNull { i, x -> (x as? JsonObject)?.let { card(titleOf(it) ?: "${i + 1}", it, 1) } }
                .ifEmpty { listOfNotNull(prim(e)?.let { JCard("", listOf("" to it)) }) }
            else -> listOfNotNull(prim(e)?.let { JCard("", listOf("" to it)) })
        }
    }
}
