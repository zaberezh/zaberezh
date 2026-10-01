package by.zaberezh.forma.ui

import android.graphics.BitmapFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val photos = ConcurrentHashMap<String, ImageBitmap>()

/** Круглое фото по ссылке (кэш в памяти); пока грузится или нет фото — инициалы. */
@Composable
fun RemoteImage(url: String, size: Dp, initials: String) {
    val img by produceState(photos[url], url) {
        if (value == null && url.startsWith("https://")) value = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                try { c.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap() } finally { c.disconnect() }
            }.getOrNull()
        }?.also { photos[url] = it }
    }
    Box(Modifier.size(size).clip(CircleShape).background(C.cardHi), contentAlignment = Alignment.Center) {
        val b = img
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(initials, color = C.accent, style = MaterialTheme.typography.labelLarge)
    }
}
