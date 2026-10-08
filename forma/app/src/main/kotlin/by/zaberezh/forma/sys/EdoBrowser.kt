package by.zaberezh.forma.sys

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import by.zaberezh.forma.core.food.PageRunner
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * edostavka.by пускает только настоящий браузер: перед сайтом стоит проверка, которую простой запрос из приложения
 * не проходит. Поэтому магазин открывается в невидимом WebView — обычным визитом на главную, как в браузере, — и уже
 * со страницы сайта идут его же запросы поиска (как когда ищешь на сайте руками). Ничего не подделывается: это браузер
 * телефона; ходит он только на edostavka.by, картинки не грузит, закрывается через 3 минуты без дела.
 */
object EdoBrowser : PageRunner {
    private const val HOST = "edostavka.by"
    private const val HOME = "https://edostavka.by/"
    private const val READY_MS = 15_000L     // главная + проверка браузера: обычно 3–6 с
    private const val CALL_MS = 9_000L       // один поиск со страницы: обычно ~1 с
    private const val IDLE_MS = 180_000L
    private const val DOWN_MS = 120_000L     // сайт не открылся — пару минут не пробуем, чтобы не тормозить поиск
    private const val READY_JS = "!!(window.__NEXT_DATA__ && window.__NEXT_DATA__.buildId) && location.hostname.endsWith('$HOST')"

    private val main = Handler(Looper.getMainLooper())
    private val calls = ConcurrentHashMap<String, CompletableFuture<String?>>()
    @Volatile private var app: Context? = null
    @Volatile private var downUntil = 0L
    private var web: WebView? = null          // только на главном потоке
    private val close = Runnable { web?.destroy(); web = null }

    fun init(c: Context) { app = c.applicationContext }

    override fun run(script: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null       // ждать ответа на главном потоке нельзя
        val ctx = app ?: return null
        if (System.currentTimeMillis() < downUntil) return null
        if (!ready(ctx)) { downUntil = System.currentTimeMillis() + DOWN_MS; idle(); return null }
        val id = UUID.randomUUID().toString()
        val f = CompletableFuture<String?>()
        calls[id] = f
        main.post { web?.evaluateJavascript(wrap(id, script), null) ?: f.complete(null) }
        return try {
            f.get(CALL_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            downUntil = System.currentTimeMillis() + DOWN_MS / 4
            null
        } finally { calls.remove(id); idle() }
    }

    /** Страница сайта открыта и прошла проверку браузера (появились данные сайта). */
    private fun ready(ctx: Context): Boolean {
        val until = System.currentTimeMillis() + READY_MS
        while (System.currentTimeMillis() < until) {
            val f = CompletableFuture<String?>()
            main.post {
                try { open(ctx).evaluateJavascript(READY_JS) { f.complete(it) } } catch (e: Throwable) { f.complete(null) }
            }
            val ok = try { f.get(3, TimeUnit.SECONDS) == "true" } catch (e: Exception) { false }
            if (ok) return true
            Thread.sleep(300)
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun open(ctx: Context): WebView = web ?: WebView(ctx).also { w ->
        main.removeCallbacks(close)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.blockNetworkImage = true          // картинки не нужны: быстрее и меньше трафика
        w.settings.loadsImagesAutomatically = false
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.addJavascriptInterface(Bridge, "GrindBridge")
        w.webViewClient = object : WebViewClient() {
            // только сам магазин: никаких переходов на другие сайты
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val h = request.url.host.orEmpty()
                return !(h == HOST || h.endsWith(".$HOST"))
            }
        }
        w.layout(0, 0, 1080, 1920)
        w.loadUrl(HOME)
        web = w
    }

    private fun idle() = main.post { main.removeCallbacks(close); main.postDelayed(close, IDLE_MS) }

    /** Результат скрипта — строкой JSON через мост; ошибка скрипта — {error}. */
    private fun wrap(id: String, script: String) =
        "(async()=>{let r;try{r=await ($script);}catch(e){r={error:String((e&&e.message)||e)};}" +
            "try{GrindBridge.done('$id',JSON.stringify(r));}catch(e){}})();void 0"

    private object Bridge {
        // зовёт страница; id — случайный, чужой скрипт его не угадает
        @JavascriptInterface fun done(id: String, json: String?) { calls[id]?.complete(json) }
    }
}
