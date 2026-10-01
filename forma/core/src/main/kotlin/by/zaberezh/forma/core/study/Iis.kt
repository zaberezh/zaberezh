package by.zaberezh.forma.core.study

import by.zaberezh.forma.core.store.JSON
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

data class IisSection(val id: String, val title: String, val about: String)

/**
 * Личный кабинет ИИС БГУИР (iis.bsuir.by/api/v1). Безопасность:
 * - запросы только на https://iis.bsuir.by (другой адрес — исключение), без редиректов (пароль не утечёт на другой хост);
 * - пароль уходит один раз при входе и нигде не сохраняется; дальше — только cookie сессии;
 * - ответы с паролем/куками не логируются.
 */
object Iis {
    const val HOST = "https://iis.bsuir.by"
    const val BASE = "$HOST/api/v1"

    // загрузка и разбор — Cabinet.load; адреса сверены с веб-версией ИИС
    val SECTIONS = listOf(
        IisSection("rating", "Успеваемость", "отметки, лабы и сроки сдачи"),
        IisSection("markbook", "Зачётная книжка", "итоговые оценки по семестрам"),
        IisSection("omissions", "Пропуски", "часы по месяцам и по предметам"),
        IisSection("group", "Моя группа", "куратор, староста, одногруппники"),
        IisSection("certificates", "Справки", "заказанные справки и их статус"),
        IisSection("cv", "Обо мне", "факультет, специальность, контакты"),
    )

    private fun cookieOf(setCookies: List<String>) = setCookies.map { it.substringBefore(';').trim() }.filter { '=' in it && !it.endsWith("=") }

    /** Как браузер на телефоне: без этого ИИС (его защита) отвечает 503 вместо страницы. */
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

    private fun headers(session: IisSession?): Map<String, String> {
        val h = linkedMapOf(
            "Accept" to "application/json, text/plain, */*", "Content-Type" to "application/json",
            "User-Agent" to UA, "Accept-Language" to "ru-RU,ru;q=0.9", "Origin" to HOST, "Referer" to "$HOST/",
        )
        if (session != null) {
            h["Cookie"] = session.cookie
            // Spring Security: XSRF-токен из куки дублируется в заголовке
            session.cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("XSRF-TOKEN=") }?.let { h["X-XSRF-TOKEN"] = it.substringAfter('=') }
        }
        return h
    }

    /** Вход по номеру студенческого и паролю ИИС. */
    fun login(http: Http, username: String, password: String, pause: (Long) -> Unit = Thread::sleep): IisSession {
        // ровно два поля, как шлёт сайт: лишние поля сервер ИИС не принимает
        val body = buildJsonObject { put("username", username.trim()); put("password", password) }.toString()
        var r = http.send("POST", "$BASE/auth/login", body, headers(null))
        // 502–504 — ИИС перегружен или перезапускается: одна повторная попытка через пару секунд
        if (r.code in 502..504) { pause(2000); r = http.send("POST", "$BASE/auth/login", body, headers(null)) }
        when {
            r.code == 401 || r.code == 403 || r.code == 400 -> throw IisError("Неверный логин или пароль")
            r.code in 500..599 -> throw IisError("ИИС не пустил через приложение (ошибка ${r.code}${serverMessage(r.body)}). Войди через сайт ИИС — кнопка ниже")
            r.code !in 200..299 -> throw IisError("ИИС ответил ошибкой ${r.code}${serverMessage(r.body)}")
        }
        val cookie = cookieOf(r.cookies)
        if (cookie.isEmpty()) throw IisError("ИИС не выдал сессию — попробуй позже")
        return IisSession(cookie.joinToString("; "), r.body)
    }

    /** Текст ошибки из ответа сервера (JSON message/error), без HTML-страниц. */
    fun serverMessage(body: String): String {
        val o = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ""
        val m = listOf("message", "error", "detail").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        return m?.let { ": ${it.take(120)}" }.orEmpty()
    }

    /**
     * Сессия из входа на самом сайте ИИС (в окне приложения): берём куки сайта и проверяем их запросом профиля.
     * null — вход ещё не выполнен.
     */
    fun sessionFromCookies(http: Http, cookies: String): IisSession? {
        val c = cookies.split(';').map { it.trim() }.filter { '=' in it && !it.endsWith("=") }.joinToString("; ")
        if (c.isEmpty()) return null
        val s = IisSession(c)
        val r = http.send("GET", "$BASE/personal-information", null, headers(s))
        return if (r.code in 200..299 && r.body.trimStart().startsWith("{")) s.copy(profile = r.body) else null
    }

    /** GET раздела. 401/403 — сессия истекла. */
    fun get(http: Http, session: IisSession, path: String): String {
        val r = http.send("GET", BASE + path, null, headers(session))
        return when {
            r.code == 401 || r.code == 403 -> throw IisUnauthorized()
            r.code == 404 -> throw IisError("Раздел недоступен в ИИС")
            r.code in 500..599 -> throw IisError("ИИС сейчас недоступен (ошибка ${r.code}) — попробуй позже")
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

    /** Ссылка на фото из ИИС → https на iis.bsuir.by (бывает http:// или путь без хоста); иное — пусто. */
    fun photoUrl(link: String?): String {
        val l = link?.trim().orEmpty()
        val u = when {
            l.isEmpty() || l.startsWith("data:") -> return ""
            l.startsWith("//") -> "https:$l"
            l.startsWith("null/") -> "$HOST/" + l.removePrefix("null/")
            l.startsWith("/") -> HOST + l
            l.startsWith("http://") -> "https://" + l.removePrefix("http://")
            else -> l
        }
        return u.takeIf { it.startsWith("https://") && " " !in it }.orEmpty()
    }
}
