package by.zaberezh.forma.sys

import android.content.Context
import by.zaberezh.forma.core.study.Iis
import by.zaberezh.forma.core.study.IisSession

/**
 * «Оставаться в системе»: сессия ИИС живёт недолго, поэтому по желанию храним логин и пароль —
 * только в сейфе Android Keystore (AES-256-GCM, ключ не извлекается из телефона, в бэкапы не попадает).
 * Когда ИИС отвечает «сессия истекла», приложение тихо входит заново. «Выйти» стирает всё.
 */
object IisAuth {
    fun remember(c: Context, user: String, pass: String) {
        SecureStore.put(c, "user", user); SecureStore.put(c, "pass", pass)
    }

    fun forget(c: Context) { SecureStore.remove(c, "user"); SecureStore.remove(c, "pass") }

    fun canRenew(c: Context) = SecureStore.get(c, "pass") != null

    /** Тихий повторный вход сохранённым паролем; новая сессия сразу уходит в сейф. null — пароля нет или он уже не подходит. */
    @Synchronized fun renew(c: Context): IisSession? {
        val u = SecureStore.get(c, "user") ?: return null
        val p = SecureStore.get(c, "pass") ?: return null
        val s = runCatching { Iis.login(Iis.jdk, u, p) }.getOrNull() ?: return null
        runCatching { SecureStore.put(c, "cookie", s.cookie); SecureStore.put(c, "profile", s.profile) }
        return s
    }
}
