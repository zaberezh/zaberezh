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
 * Шифрованное хранилище для сессии ИИС: AES-256-GCM, ключ живёт в Android Keystore (не извлекается из телефона,
 * не попадает в бэкапы). Файл iis_secure исключён из резервного копирования и не входит в экспорт данных.
 * Пароль сюда не пишется никогда — только cookie сессии и профиль.
 */
object SecureStore {
    private const val ALIAS = "forma_iis"
    private const val PREFS = "iis_secure"

    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (ks().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun put(c: Context, k: String, v: String) {
        val ci = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = ci.doFinal(v.toByteArray())
        prefs(c).edit().putString(k, b64(ci.iv) + ":" + b64(enc)).apply()
    }

    fun get(c: Context, k: String): String? = runCatching {
        val raw = prefs(c).getString(k, null) ?: return null
        val (iv, enc) = raw.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val ci = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(ci.doFinal(enc))
    }.getOrNull()

    /** Стереть всё: и зашифрованные данные, и сам ключ. */
    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
        runCatching { ks().deleteEntry(ALIAS) }
    }
}
