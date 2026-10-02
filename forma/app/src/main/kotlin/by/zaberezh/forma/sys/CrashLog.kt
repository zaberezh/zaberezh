package by.zaberezh.forma.sys

import android.content.Context
import by.zaberezh.forma.core.ai.Claude
import java.io.File

/**
 * Последний сбой приложения — в файл внутри приложения (не в облако, никуда не отправляется).
 * На «Сегодня» появляется карточка: текст можно скопировать и прислать — по нему видно, где именно упало.
 */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(app: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                file(app).writeText(Claude.redact("Grind $ver · ${java.time.LocalDateTime.now().withNano(0)} · поток ${t.name}\n" +
                    "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n\n" + e.stackTraceToString()).take(30_000))
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(c: Context): String? = runCatching { file(c).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(c: Context) { runCatching { file(c).delete() } }
}
