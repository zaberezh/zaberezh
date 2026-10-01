package by.zaberezh.forma.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import by.zaberezh.forma.core.study.Iis
import by.zaberezh.forma.core.study.IisSection
import by.zaberezh.forma.core.study.IisSession
import by.zaberezh.forma.core.study.IisUnauthorized
import by.zaberezh.forma.core.study.JCard
import by.zaberezh.forma.core.study.JsonView
import by.zaberezh.forma.sys.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun loadSession(c: Context): IisSession? =
    SecureStore.get(c, "cookie")?.let { IisSession(it, SecureStore.get(c, "profile").orEmpty()) }

/**
 * Личный кабинет ИИС в дизайне приложения. Безопасность: пароль уходит один раз только на https://iis.bsuir.by
 * и нигде не сохраняется; хранится лишь cookie сессии, зашифрованная ключом Android Keystore; «Выйти» стирает и её, и ключ.
 */
@Composable
fun CabinetScreen() {
    val c = LocalContext.current
    var session by remember { mutableStateOf(loadSession(c)) }
    var notice by remember { mutableStateOf<String?>(null) }
    var site by remember { mutableStateOf(false) }
    val s = session
    fun enter(x: IisSession) {
        SecureStore.put(c, "cookie", x.cookie)
        SecureStore.put(c, "profile", x.profile)
        session = x; notice = null; site = false
    }
    when {
        site -> SiteLogin(onDone = ::enter, onCancel = { wipeWeb(); site = false })
        s == null -> LoginForm(notice, onLogin = ::enter, onSite = { wipeWeb(); site = true })
        else -> Cabinet(s, onExpired = { SecureStore.clear(c); wipeWeb(); session = null; notice = "Сессия закончилась — войди снова" },
            onLogout = { SecureStore.clear(c); wipeWeb(); session = null; notice = null })
    }
}

/** Стереть всё, что оставил сайт ИИС во встроенном браузере: куки и локальное хранилище. */
private fun wipeWeb() {
    runCatching { CookieManager.getInstance().apply { removeAllCookies(null); flush() }; WebStorage.getInstance().deleteAllData() }
}

/**
 * Вход на самом сайте ИИС во встроенном окне — если API не пускает приложение.
 * Пароль вводится на странице iis.bsuir.by; приложение его не видит. После входа берём куку сессии сайта,
 * проверяем её запросом профиля, шифруем как обычно и стираем всё из встроенного браузера.
 * Переходы на любые другие сайты в окне запрещены.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SiteLogin(onDone: (IisSession) -> Unit, onCancel: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        var last = ""
        while (true) {
            delay(1200)
            val ck = CookieManager.getInstance().getCookie("${Iis.BASE}/personal-information").orEmpty()
            val key = "$ck|$url"
            if (ck.isBlank() || key == last) continue
            last = key
            checking = true
            val sess = withContext(Dispatchers.IO) { runCatching { Iis.sessionFromCookies(Iis.jdk, ck) }.getOrNull() }
            checking = false
            if (sess != null) { wipeWeb(); onDone(sess); break }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Line {
                Secondary("‹ Назад", onCancel)
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Muted("Войди на сайте ИИС как обычно — приложение само подхватит вход. Пароль вводится на странице iis.bsuir.by, приложение его не видит.")
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true          // сайт ИИС — приложение на JS
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setGeolocationEnabled(false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            return !(u.scheme == "https" && u.host == "iis.bsuir.by")   // true = не открывать
                        }
                        override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) { url = u.orEmpty() }
                    }
                    loadUrl("${Iis.HOST}/login")
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun LoginForm(notice: String?, onLogin: (IisSession) -> Unit, onSite: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Screen {
        item {
            Block("Вход в ИИС") {
                notice?.let { Text(it, color = C.warn, style = MaterialTheme.typography.bodySmall) }
                Field("Номер студенческого билета", user, { user = it.filter(Char::isLetterOrDigit).take(12) })
                Field("Пароль ИИС", pass, { pass = it }, number = false, secret = true)
                Primary(if (busy) "Вхожу…" else "Войти", {
                    busy = true; err = null
                    val u = user; val p = pass
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Iis.login(Iis.jdk, u, p) } }
                        busy = false
                        r.onSuccess { sess ->
                            pass = ""   // пароль не держим даже в поле
                            onLogin(sess)
                        }.onFailure { err = it.message ?: "Не удалось войти" }
                    }
                }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy && user.isNotBlank() && pass.isNotBlank())
                Err(err)
                Secondary("Войти через сайт ИИС", onSite, Modifier.fillMaxWidth().heightIn(min = 52.dp))
                Muted("Если вход выше не работает — откроется страница iis.bsuir.by прямо в приложении.")
            }
        }
        item {
            Block("Как хранятся данные") {
                Muted("• Пароль отправляется один раз по HTTPS только на iis.bsuir.by и нигде не сохраняется — ни в приложении, ни в экспорте.")
                Muted("• Остаётся только ключ сессии ИИС, зашифрованный AES-256 ключом из Android Keystore: его нельзя достать из телефона, он не попадает в резервные копии.")
                Muted("• При входе через сайт пароль вводится на странице ИИС, приложение его не видит; после входа встроенный браузер очищается.")
                Muted("• Запросы на любые другие адреса и переадресации запрещены. «Выйти» стирает сессию и ключ шифрования.")
            }
        }
    }
}

@Composable
private fun Cabinet(session: IisSession, onExpired: () -> Unit, onLogout: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember(session) { Iis.profile(session.profile) }
    var open by remember { mutableStateOf<IisSection?>(null) }
    val data = remember { mutableStateMapOf<String, Result<List<JCard>>>() }
    var loading by remember { mutableStateOf<String?>(null) }

    fun load(sec: IisSection, force: Boolean = false) {
        if (!force && data[sec.id]?.isSuccess == true) return
        loading = sec.id
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { JsonView.cards(Iis.get(Iis.jdk, session, sec.path)) } }
            loading = null
            if (r.exceptionOrNull() is IisUnauthorized) onExpired() else data[sec.id] = r
        }
    }
    LaunchedEffect(open) { open?.let { load(it) } }

    Screen {
        item {
            Block {
                Line {
                    RemoteImage(profile?.photo.orEmpty(), 56.dp, profile?.fio?.split(" ")?.take(2)?.mapNotNull { it.firstOrNull() }?.joinToString("") ?: "ИИС")
                    Column(Modifier.weight(1f)) {
                        Text(profile?.fio?.ifBlank { null } ?: "Личный кабинет", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        profile?.group?.takeIf { it.isNotBlank() }?.let { Muted("Группа $it") }
                    }
                }
                Line {
                    Secondary("Открыть на сайте", { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://iis.bsuir.by"))) }, Modifier.weight(1f))
                    Secondary("Выйти", { scope.launch { withContext(Dispatchers.IO) { Iis.logout(Iis.jdk, session) }; onLogout() } }, Modifier.weight(1f))
                }
            }
        }
        val sec = open
        if (sec == null) {
            Iis.SECTIONS.chunked(2).forEach { row ->
                item {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { x -> SectionTile(x, Modifier.weight(1f)) { open = x } }
                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            item {
                Line {
                    Secondary("‹ Разделы", { open = null })
                    Text(sec.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (loading == sec.id) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Flat("Обновить", { load(sec, force = true) })
                }
            }
            val r = data[sec.id]
            r?.exceptionOrNull()?.let { e -> item { Block { Err(e.message ?: "Ошибка"); Muted("Раздел можно открыть на сайте ИИС кнопкой выше.") } } }
            r?.getOrNull()?.let { cards ->
                if (cards.isEmpty()) item { Block { Muted("Пусто.") } }
                cards.forEach { card -> item { CardView(card) } }
            }
        }
    }
}

@Composable
private fun SectionTile(sec: IisSection, modifier: Modifier, onClick: () -> Unit) = Card(
    modifier.heightIn(min = 96.dp).clickable(onClick = onClick), shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = C.card),
) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(sec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = C.accent)
        Muted(sec.about)
    }
}

/** Карточка ответа ИИС: поля строками, вложенное — раскрывающимися подкарточками. */
@Composable
private fun CardView(card: JCard) = Block(card.title.ifBlank { null }) {
    card.rows.forEach { (k, v) -> if (k.isBlank()) Text(v, style = MaterialTheme.typography.bodyMedium) else Stat(k, v) }
    card.children.forEachIndexed { i, ch ->
        if (i > 0 || card.rows.isNotEmpty()) HorizontalDivider(color = C.line)
        SubCard(ch)
    }
}

@Composable
private fun SubCard(card: JCard) {
    var open by remember(card) { mutableStateOf(card.children.isEmpty() && card.rows.size <= 4) }
    Column(Modifier.animateContentSize().fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Line {
            Text(card.title.ifBlank { "—" }, Modifier.weight(1f).clickable { open = !open }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Flat(if (open) "Свернуть" else "Подробнее", { open = !open }, C.muted)
        }
        if (open) {
            card.rows.forEach { (k, v) -> if (k.isBlank()) Muted(v) else Stat(k, v) }
            card.children.forEach { ch -> Column(Modifier.padding(start = 12.dp)) { SubCard(ch) } }
        }
    }
}
