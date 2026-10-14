package by.zaberezh.forma.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import by.zaberezh.forma.core.study.Iis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Кэш фото в памяти: не больше 64 штук (преподаватели, профиль) — память не растёт бесконечно. */
private val photos = android.util.LruCache<String, ImageBitmap>(64)

/**
 * Загрузка как в браузере (без User-Agent ИИС отвечает 503); переадресации — только на https, не больше трёх.
 * [cookie] (сессия ИИС для своего фото) уходит только на https://iis.bsuir.by.
 */
private fun load(start: String, cookie: String?): ImageBitmap? {
    if (start.startsWith("data:image")) return decodeSmall(Base64.decode(start.substringAfter(','), Base64.DEFAULT))
    // фото преподавателей общие — лежат в кэше на диске (система чистит его сама при нехватке места): расписание
    // не качает их заново при каждом запуске. Своё фото из кабинета (с сессией) на диск не пишем.
    val file = if (cookie == null) diskFile(start) else null
    file?.takeIf { it.isFile }?.let { f -> runCatching { decodeSmall(f.readBytes()) }.getOrNull()?.let { return it } }
    val bytes = fetch(start, cookie) ?: return null
    val img = decodeSmall(bytes) ?: return null
    if (file != null && bytes.size <= 512 shl 10) runCatching { file.writeBytes(bytes) }
    return img
}

private fun diskFile(url: String): java.io.File? = runCatching {
    val dir = java.io.File(by.zaberezh.forma.Forma.app.cacheDir, "photos").apply { mkdirs() }
    val name = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    java.io.File(dir, name)
}.getOrNull()

private fun fetch(start: String, cookie: String?): ByteArray? {
    var url = start
    repeat(4) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", Iis.UA)
            c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            c.setRequestProperty("Referer", "${Iis.HOST}/")
            if (cookie != null && url.startsWith("${Iis.HOST}/")) c.setRequestProperty("Cookie", cookie)
            when (c.responseCode) {
                in 200..299 -> return c.inputStream.use { it.readUpTo(MAX_BYTES + 1) }.takeIf { it.size <= MAX_BYTES }
                in 300..399 -> url = URL(URL(url), c.getHeaderField("Location") ?: return null).toString().takeIf { it.startsWith("https://") } ?: return null
                else -> return null
            }
        } finally { c.disconnect() }
    }
    return null
}

private const val MAX_BYTES = 8 shl 20   // больше — не фото профиля; в память не читаем
private const val MAX_PX = 320           // аватарки на экране ≤ 96 dp — хватит и на плотных экранах

/**
 * Декодирование с уменьшением: огромная картинка (6000×4000 = ~96 МБ в памяти) не уронит приложение,
 * а в кэше лежат маленькие копии.
 */
private fun decodeSmall(bytes: ByteArray): ImageBitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) return null
    var sample = 1
    while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** Круглое фото по ссылке или data:image (кэш в памяти); пока грузится или нет фото — инициалы. */
@Composable
fun RemoteImage(url: String, size: Dp, initials: String, cookie: String? = null) {
    val img by produceState(photos.get(url), url) {
        if (value == null && (url.startsWith("https://") || url.startsWith("data:image"))) value = withContext(Dispatchers.IO) {
            runCatching { load(url, cookie) }.getOrNull()
        }?.also { photos.put(url, it) }
    }
    Box(Modifier.size(size).clip(CircleShape).background(C.cardHi), contentAlignment = Alignment.Center) {
        val b = img
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(initials, color = C.accent, style = MaterialTheme.typography.labelLarge)
    }
}
