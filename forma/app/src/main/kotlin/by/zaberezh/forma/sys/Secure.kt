package by.zaberezh.forma.sys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрованный сейф: AES-256-GCM, ключ живёт в Android Keystore (не извлекается из телефона и не попадает в бэкапы,
 * файлы приложения из бэкапов исключены целиком). У каждого сейфа свой ключ и свой файл — стирание одного
 * (выход из ИИС) не трогает другой (ключ Claude).
 */
open class Vault(private val alias: String, private val file: String) {
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (ks().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(file, Context.MODE_PRIVATE)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun put(c: Context, k: String, v: String) {
        val ci = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        // имя записи — «дополнительные данные» GCM: шифротекст нельзя подсунуть под другим именем
        ci.updateAAD(k.toByteArray())
        val enc = ci.doFinal(v.toByteArray())
        prefs(c).edit().putString(k, "2:" + b64(ci.iv) + ":" + b64(enc)).apply()
    }

    fun get(c: Context, k: String): String? = runCatching {
        val raw = prefs(c).getString(k, null) ?: return null
        val parts = raw.split(':')
        val v2 = parts.size == 3 && parts[0] == "2"
        val (iv, enc) = (if (v2) parts.drop(1) else parts).map { Base64.decode(it, Base64.NO_WRAP) }
        val ci = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        if (v2) ci.updateAAD(k.toByteArray())
        String(ci.doFinal(enc))
    }.getOrNull()

    fun remove(c: Context, k: String) { prefs(c).edit().remove(k).apply() }

    /** Стереть всё: и зашифрованные данные, и сам ключ. */
    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
        runCatching { ks().deleteEntry(alias) }
    }
}

/** Сессия ИИС: только cookie и профиль, пароль сюда не пишется никогда. */
object SecureStore : Vault("forma_iis", "iis_secure")

/** Секреты приложения: ключ Claude API (в базе и в экспорте его больше нет). */
object Secrets : Vault("grind_secrets", "secrets") {
    private const val CLAUDE = "claude_key"
    @Volatile private var cache: String? = null

    fun claudeKey(c: Context): String = cache ?: (get(c, CLAUDE).orEmpty()).also { cache = it }

    /** Ключ Claude (контекст приложения). Пусто — не задан. */
    fun claudeKey(): String = claudeKey(by.zaberezh.forma.Forma.app)

    fun setClaudeKey(c: Context, key: String) {
        val k = key.trim()
        if (k.isEmpty()) remove(c, CLAUDE) else put(c, CLAUDE, k)
        cache = k
    }

    /** Ключ из старых версий лежал в настройках открытым текстом — переносим в сейф и стираем из базы. */
    fun migrate(c: Context, store: by.zaberezh.forma.core.store.Store) {
        val st = by.zaberezh.forma.core.SETTINGS.get(store)
        if (st.apiKey.isNotBlank()) {
            setClaudeKey(c, st.apiKey)
            by.zaberezh.forma.core.SETTINGS.set(store, st.copy(apiKey = ""))
        }
    }
}
