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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import by.zaberezh.forma.core.study.Cabinet
import by.zaberezh.forma.core.study.CabinetData
import by.zaberezh.forma.core.study.Iis
import by.zaberezh.forma.core.study.IisSection
import by.zaberezh.forma.core.study.IisSession
import by.zaberezh.forma.core.study.IisUnauthorized
import by.zaberezh.forma.core.study.Person
import by.zaberezh.forma.core.study.adoptGroup
import by.zaberezh.forma.Forma
import by.zaberezh.forma.sys.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun loadSession(c: Context): IisSession? =
    SecureStore.get(c, "cookie")?.let { IisSession(it, SecureStore.get(c, "profile").orEmpty()) }

/** Сессия ИИС живёт около получаса: старше этого — сначала тихо входим заново, а не ловим ошибки по всем разделам. */
private const val STALE_MS = 20 * 60_000L

/**
 * Разделы кабинета — в памяти, пока приложение открыто (на диск не пишутся): вернулся во вкладку — данные видны
 * сразу, свежие догружаются в фоне. «Выйти» стирает.
 */
private object CabinetCache {
    val data = mutableStateMapOf<String, Result<CabinetData>>()
}

/**
 * Личный кабинет ИИС в дизайне приложения. Безопасность: пароль уходит только на https://iis.bsuir.by;
 * хранится сессия (и, если включено «Оставаться в системе», логин с паролем) — только в сейфе Android Keystore.
 * Сессия истекла — тихий повторный вход сохранённым паролем; «Выйти» стирает всё вместе с ключом.
 */
@Composable
fun CabinetScreen() {
    val c = LocalContext.current
    var session by remember { mutableStateOf(loadSession(c)) }
    var notice by remember { mutableStateOf<String?>(null) }
    var site by remember { mutableStateOf(false) }
    val s = session
    val scope = rememberCoroutineScope()
    var renewing by remember { mutableStateOf(false) }
    // когда последний раз входили: свежая сессия получила «не авторизован» — второй раз не входим (иначе по кругу)
    var loggedAt by remember { mutableStateOf(SecureStore.get(c, "cookie_at")?.toLongOrNull() ?: 0L) }
    // проверка возраста сессии при открытии: протухла — сначала новый вход, потом разделы (а не «не загрузилось» на всех)
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val at = SecureStore.get(c, "cookie_at")?.toLongOrNull() ?: 0L
        if (session != null && by.zaberezh.forma.sys.IisAuth.canRenew(c) && System.currentTimeMillis() - at > STALE_MS) {
            renewing = true
            withContext(Dispatchers.IO) { by.zaberezh.forma.sys.IisAuth.renew(c) }?.let { session = it; loggedAt = System.currentTimeMillis() }
            renewing = false
        }
        ready = true
    }
    fun enter(x: IisSession, creds: Pair<String, String>? = null) {
        // сбой сейфа или планировщика не должен ронять вход: сессия работает и без них, просто не запомнится
        val saved = runCatching {
            SecureStore.put(c, "cookie", x.cookie); SecureStore.put(c, "profile", x.profile)
            SecureStore.put(c, "cookie_at", System.currentTimeMillis().toString())
        }.isSuccess
        runCatching { if (creds != null) by.zaberezh.forma.sys.IisAuth.remember(c, creds.first, creds.second) else by.zaberezh.forma.sys.IisAuth.forget(c) }
        session = x; site = false; ready = true; loggedAt = System.currentTimeMillis()
        notice = if (saved) null else "Вошёл, но сессию не удалось сохранить — после перезапуска войди снова"
        runCatching { by.zaberezh.forma.sys.IisCheck.loggedIn(c) }
    }
    when {
        site -> SiteLogin(onDone = { enter(it) }, onCancel = { wipeWeb(); site = false })
        s == null -> LoginForm(notice, onLogin = ::enter, onSite = { wipeWeb(); site = true })
        else -> Cabinet(s, ready && !renewing, onExpired = {
            // сессия ИИС истекла: тихо входим заново сохранённым паролем; не вышло — просим войти.
            // Только что входили, а ИИС всё равно не пускает — второй раз не входим: это не сессия, а сам раздел
            if (System.currentTimeMillis() - loggedAt < 2 * 60_000L) false
            else {
                if (!renewing) {
                    renewing = true
                    scope.launch {
                        val fresh = withContext(Dispatchers.IO) { by.zaberezh.forma.sys.IisAuth.renew(c) }
                        renewing = false
                        if (fresh != null) { session = fresh; loggedAt = System.currentTimeMillis() }
                        else { SecureStore.clear(c); wipeWeb(); CabinetCache.data.clear(); session = null; notice = "Сессия закончилась — войди снова" }
                    }
                }
                true
            }
        },
            onLogout = { SecureStore.clear(c); wipeWeb(); CabinetCache.data.clear(); session = null; notice = null })
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LoginForm(notice: String?, onLogin: (IisSession, Pair<String, String>?) -> Unit, onSite: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var stay by remember { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeOpen = WindowInsets.isImeVisible
    // «Назад» на форме входа: сначала закрывает клавиатуру; пока идёт вход — никуда не уводит
    BackHandler(enabled = imeOpen || busy) { keyboard?.hide(); focus.clearFocus() }
    Screen {
        item {
            Block("Вход в ИИС") {
                notice?.let { Text(it, color = C.warn, style = MaterialTheme.typography.bodySmall) }
                Field("Номер студенческого билета", user, { user = it.filter(Char::isLetterOrDigit).take(12) })
                Field("Пароль ИИС", pass, { pass = it }, number = false, secret = true)
                Primary(if (busy) "Вхожу…" else "Войти", {
                    keyboard?.hide(); focus.clearFocus()
                    busy = true; err = null
                    val u = user; val p = pass; val keep = stay
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Iis.login(Iis.jdk, u, p) } }
                        busy = false
                        r.onSuccess { sess ->
                            pass = ""   // в поле пароль не держим
                            onLogin(sess, if (keep) u to p else null)
                        }.onFailure { err = it.message ?: "Не удалось войти" }
                    }
                }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy && user.isNotBlank() && pass.isNotBlank())
                Err(err)
                Line {
                    Column(Modifier.weight(1f)) {
                        Text("Оставаться в системе", style = MaterialTheme.typography.bodyMedium)
                        Muted("Сессия ИИС живёт недолго: с этим приложение само войдёт снова. Пароль — только в сейфе Keystore.")
                    }
                    androidx.compose.material3.Switch(checked = stay, onCheckedChange = { stay = it })
                }
                Secondary("Войти через сайт ИИС", onSite, Modifier.fillMaxWidth().heightIn(min = 52.dp))
                Muted("Если вход выше не работает — откроется страница iis.bsuir.by прямо в приложении.")
            }
        }
        item {
            Block("Как хранятся данные") {
                Muted("• Пароль уходит по HTTPS только на iis.bsuir.by. С «Оставаться в системе» он хранится зашифрованным AES-256 ключом Android Keystore (ключ нельзя достать из телефона), без — не хранится вовсе. В экспорт и резервные копии не попадает.")
                Muted("• Сессия ИИС — тоже только в этом сейфе.")
                Muted("• При входе через сайт пароль вводится на странице ИИС, приложение его не видит; после входа встроенный браузер очищается.")
                Muted("• Запросы на любые другие адреса и переадресации запрещены. «Выйти» стирает сессию и ключ шифрования.")
            }
        }
    }
}

@Composable
private fun Cabinet(session: IisSession, ready: Boolean, onExpired: () -> Boolean, onLogout: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<IisSection?>(null) }
    val data = CabinetCache.data
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val stale = remember { mutableStateMapOf<String, String>() }   // обновить не вышло — показаны прошлые данные
    BackHandler(enabled = open != null) { open = null }

    suspend fun fetch(id: String) {
        busy[id] = true
        // прервали (вход заново, ушёл с экрана) — «загружаю…» не должно остаться навсегда
        val r = try { withContext(Dispatchers.IO) {
            runCatching { Cabinet.load(Iis.jdk, session, id) }.onSuccess { d ->
                // успеваемость: лабы из ИИС — во вкладку «Лабы», отметки — в снимок для фоновых уведомлений
                // (их сбой не ломает раздел и не роняет приложение)
                if (d is by.zaberezh.forma.core.study.Rating) runCatching {
                    by.zaberezh.forma.sys.IisCheck.apply(d); by.zaberezh.forma.core.study.IisWatch.check(Forma.store, d)
                }
            }
        } } finally { busy[id] = false }
        val e = r.exceptionOrNull()
        when {
            e is IisUnauthorized && onExpired() -> {}
            // только что вошли, а раздел всё равно «не авторизован» — показываем ошибку раздела, без входа по кругу
            e is IisUnauthorized -> if (data[id]?.isSuccess == true) stale[id] = "ИИС не пустил в раздел" else
                data[id] = Result.failure(by.zaberezh.forma.core.study.IisError("ИИС не пустил в раздел, хотя вход выполнен — попробуй «Обновить» позже или открой сайт ИИС"))
            // свежее не загрузилось, а прошлое есть — оставляем прошлое и говорим об этом, а не меняем данные на ошибку
            e != null && data[id]?.isSuccess == true -> stale[id] = e.message ?: "ошибка"
            else -> { data[id] = r; stale.remove(id) }
        }
    }
    // все разделы сразу и параллельно (по 3 запроса, чтобы ИИС не принял за атаку): плитки показывают сводку,
    // раздел открывается мгновенно; пока идёт проверка сессии — ждём, а не шлём запросы со старой
    LaunchedEffect(session, ready) {
        if (!ready) return@LaunchedEffect
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        kotlinx.coroutines.coroutineScope {
            // в очереди — тоже «загружаю…», а не «—»; прервали — признак снимается
            Iis.SECTIONS.forEach { x -> busy[x.id] = true; launch { try { gate.acquire(); try { fetch(x.id) } finally { gate.release() } } finally { busy[x.id] = false } } }
        }
    }

    val fromLogin = remember(session) { runCatching { Cabinet.person(null, null, session.profile) }.getOrElse { Cabinet.person(null, null) } }
    val person = data["cv"]?.getOrNull() as? Person ?: fromLogin
    // группа из ИИС сама становится группой расписания
    var groupNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(person.group) {
        val store = Forma.store
        if (runCatching { adoptGroup(store, person.group) }.getOrDefault(false)) {
            groupNote = "Группа ${person.group} взята из ИИС — обновляю расписание…"
            groupNote = runCatching { downloadTimetable(store) }.getOrElse { Result.failure(it) }.fold({ "Расписание группы ${person.group} загружено" }, { "Группа ${person.group} сохранена, расписание обновится при открытии" })
        }
    }
    Screen {
        item {
            Block {
                Line {
                    RemoteImage(person.photo, 56.dp, initials(person.fio).filter(Char::isUpperCase).take(2).ifBlank { "ИИС" }, session.cookie)
                    Column(Modifier.weight(1f)) {
                        Text(person.fio.ifBlank { "Личный кабинет" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Muted(listOfNotNull(
                            person.group.ifBlank { null }?.let { "группа $it" }, person.course?.let { "$it курс" }, person.faculty.ifBlank { null },
                        ).joinToString(" · ").replaceFirstChar { it.uppercase() })
                    }
                }
                Note(groupNote)
                Line {
                    Secondary("Сайт ИИС", { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://iis.bsuir.by"))) }, Modifier.weight(1f))
                    Secondary("Выйти", { scope.launch { withContext(Dispatchers.IO) { Iis.logout(Iis.jdk, session) }; onLogout() } }, Modifier.weight(1f))
                }
            }
        }
        val sec = open
        if (sec == null) {
            Iis.SECTIONS.chunked(2).forEach { row ->
                item {
                    // одна высота у плиток в ряду (и одинаковая структура у всех — сетка ровная)
                    androidx.compose.foundation.layout.Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { x ->
                            SectionTile(x, tileOf(data[x.id]?.getOrNull()), busy[x.id] == true || (!ready && data[x.id] == null), data[x.id]?.isFailure == true, Modifier.weight(1f).fillMaxHeight()) { open = x }
                        }
                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            item {
                Line {
                    Secondary("‹ Разделы", { open = null })
                    Text(sec.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (busy[sec.id] == true) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Flat("Обновить", { scope.launch { fetch(sec.id) } })
                }
            }
            val r = data[sec.id]
            when {
                r == null -> item { Block { Line { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Muted(if (ready) "Загружаю из ИИС…" else "Вхожу в ИИС…") } } }
                r.isFailure -> item { Block { Err(r.exceptionOrNull()?.message ?: "Ошибка"); Muted("Попробуй «Обновить» или открой раздел на сайте ИИС.") } }
                else -> {
                    stale[sec.id]?.let { m -> item { Block { Muted("Не обновилось ($m) — показаны данные с прошлой загрузки.") } } }
                    cabinetSection(r.getOrThrow(), person.fio)
                }
            }
        }
    }
}

@Composable
private fun SectionTile(sec: IisSection, info: TileInfo?, loading: Boolean, failed: Boolean, modifier: Modifier, onClick: () -> Unit) = Card(
    modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = C.card, contentColor = C.text),
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(sec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = C.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            info?.value ?: if (loading) "…" else "—",
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            color = info?.color ?: C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // подпись всегда в две строки: высота у всех плиток одинаковая
        Text(
            info?.caption ?: when { loading -> "загружаю…"; failed -> "не загрузилось — открой и нажми «Обновить»"; else -> sec.about },
            style = MaterialTheme.typography.bodySmall, color = if (info == null && failed) C.bad else C.muted,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
