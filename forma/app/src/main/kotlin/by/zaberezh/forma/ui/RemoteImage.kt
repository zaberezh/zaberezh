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
import java.util.concurrent.ConcurrentHashMap

private val photos = ConcurrentHashMap<String, ImageBitmap>()

/**
 * Загрузка как в браузере (без User-Agent ИИС отвечает 503); переадресации — только на https, не больше трёх.
 * [cookie] (сессия ИИС для своего фото) уходит только на https://iis.bsuir.by.
 */
private fun load(start: String, cookie: String?): ImageBitmap? {
    if (start.startsWith("data:image")) {
        val bytes = Base64.decode(start.substringAfter(','), Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
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
                in 200..299 -> return c.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                in 300..399 -> url = URL(URL(url), c.getHeaderField("Location") ?: return null).toString().takeIf { it.startsWith("https://") } ?: return null
                else -> return null
            }
        } finally { c.disconnect() }
    }
    return null
}

/** Круглое фото по ссылке или data:image (кэш в памяти); пока грузится или нет фото — инициалы. */
@Composable
fun RemoteImage(url: String, size: Dp, initials: String, cookie: String? = null) {
    val img by produceState(photos[url], url) {
        if (value == null && (url.startsWith("https://") || url.startsWith("data:image"))) value = withContext(Dispatchers.IO) {
            runCatching { load(url, cookie) }.getOrNull()
        }?.also { photos[url] = it }
    }
    Box(Modifier.size(size).clip(CircleShape).background(C.cardHi), contentAlignment = Alignment.Center) {
        val b = img
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(initials, color = C.accent, style = MaterialTheme.typography.labelLarge)
    }
}
