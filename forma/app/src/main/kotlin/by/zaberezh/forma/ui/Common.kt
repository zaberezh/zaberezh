@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package by.zaberezh.forma.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.Forma
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.r1
import kotlin.math.roundToInt

// ---------- палитра ----------
/** Два раздела одного приложения: те же серые, акцент — голубой у «Здоровья», лавандовый у «Учёбы» (той же яркости). */
object C {
    val bg = Color(0xFF0D0F12)
    val card = Color(0xFF16191E)
    val cardHi = Color(0xFF1E2228)
    val line = Color(0xFF2A2F37)
    val text = Color(0xFFE8EAED)
    val muted = Color(0xFF8E97A3)
    val health = Color(0xFF7FB4FF)
    val study = Color(0xFFB79CFF)
    val good = Color(0xFF5FD08C)
    val warn = Color(0xFFF2C94C)
    val bad = Color(0xFFFF6B6B)

    /** 0 — «Здоровье», 1 — «Учёба». Чтение в композиции перекрашивает весь экран. */
    var section by mutableIntStateOf(0)
    val accent: Color get() = if (section == 1) study else health
}

fun scheme(accent: Color) = darkColorScheme(
    primary = accent, onPrimary = if (accent == C.study) Color(0xFF1C1033) else Color(0xFF0A1A30),
    background = C.bg, onBackground = C.text,
    surface = C.bg, onSurface = C.text,
    surfaceVariant = C.card, onSurfaceVariant = C.muted,
    surfaceContainer = C.card, surfaceContainerHigh = C.cardHi, surfaceContainerHighest = C.cardHi,
    surfaceContainerLow = C.card, surfaceContainerLowest = C.bg,
    outline = C.line, outlineVariant = C.line, error = C.bad,
)

// ---------- данные ----------
/** Контекст, пересоздаваемый при любом изменении данных. */
@Composable
fun rememberCtx(): Ctx {
    val v = Forma.store.version.intValue
    return remember(v) { Ctx(Forma.store) }
}

fun String.num(): Double? = trim().replace(',', '.').toDoubleOrNull()

fun Context.copy(text: String) =
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("forma", text))

// ---------- раскладка ----------
@Composable
fun Screen(content: LazyListScope.() -> Unit) = LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
    // близость: карточки разнесены сильнее, чем элементы внутри карточки — группы читаются сразу
    verticalArrangement = Arrangement.spacedBy(16.dp),
    content = content,
)

/** Карточка-раздел. [trailing] — справа от заголовка (счётчик, статус). */
@Composable
fun Block(title: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) = Card(
    Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    // явный цвет текста: без него заголовки и текст карточек выходили серыми
    colors = CardDefaults.cardColors(containerColor = C.card, contentColor = C.text),
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null || trailing != null) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title ?: "", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = C.text)
            trailing?.invoke()
        }
        content()
    }
}

/**
 * Общая область внутри карточки: связанные элементы (поле + кнопка, быстрые действия) на чуть более светлом фоне —
 * видно, что это одно действие, отдельное от данных над ним.
 */
@Composable
fun Inset(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.cardHi).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
)

/** Кнопка-стрелка: одинаковая во всём приложении (недели, дни). */
@Composable
fun NavArrow(t: String, onClick: () -> Unit, enabled: Boolean = true) = Box(
    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(C.cardHi).clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(t, style = MaterialTheme.typography.titleLarge, color = if (enabled) C.accent else C.line) }

/** Переключатель дня «‹ Сегодня ›» — один и тот же на всех экранах; в будущее не листается. */
@Composable
fun DateNav(date: java.time.LocalDate, today: java.time.LocalDate, onChange: (java.time.LocalDate) -> Unit) = Line {
    NavArrow("‹", { onChange(date.minusDays(1)) })
    Text(
        if (date == today) "Сегодня" else if (date == today.minusDays(1)) "Вчера"
        else java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", RU).format(date).replaceFirstChar { it.uppercase() },
        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        color = if (date == today) C.accent else C.text,
    )
    NavArrow("›", { onChange(date.plusDays(1)) }, enabled = date < today)
}

@Composable
fun Line(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)

/** Ряд кнопок/чипов с переносом — ничего не вылезает за край. */
@Composable
fun Buttons(content: @Composable FlowRowScope.() -> Unit) =
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

// ---------- текст ----------
@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = C.muted)

@Composable
fun Err(text: String?) { if (!text.isNullOrBlank()) Text(text, color = C.bad, style = MaterialTheme.typography.bodySmall) }

@Composable
fun Note(text: String?) { if (!text.isNullOrBlank()) Text(text, color = C.good, style = MaterialTheme.typography.bodySmall) }

/** Строка «подпись … значение». */
@Composable
fun Stat(label: String, value: String, color: Color = C.text) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = C.muted)
    Text(value, Modifier.weight(1f, fill = false).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium,
        color = color, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
}

/** Крупное число с подписью. */
@Composable
fun BigValue(value: String, unit: String, sub: String? = null) = Column {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(" $unit", Modifier.padding(bottom = 4.dp), color = C.muted)
    }
    if (sub != null) Muted(sub)
}

/** Маленькая плашка статуса. */
@Composable
fun Pill(text: String, color: Color = C.accent) = Box(
    Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp)
) { Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1) }

fun statusColor(status: String) = when (status) {
    "растёт" -> C.good
    "медленно" -> C.accent
    "стоит" -> C.warn
    "падает" -> C.bad
    else -> C.muted
}

/** Полоса прогресса «факт / цель». */
@Composable
fun Progress(label: String, cur: Double, target: Int, unit: String = "") {
    val f = if (target > 0) (cur / target).toFloat() else 0f
    val color = when { f > 1.1f -> C.warn; f >= 0.9f -> C.good; else -> C.accent }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${cur.roundToInt()} / $target $unit".trim(), style = MaterialTheme.typography.bodyMedium, color = C.muted)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
            Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

// ---------- кнопки ----------
private val BtnPad = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

@Composable
fun Primary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    Button(onClick, modifier.height(44.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = BtnPad) { Text(text, maxLines = 1) }

@Composable
fun Secondary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    OutlinedButton(onClick, modifier.height(44.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = BtnPad) { Text(text, maxLines = 1) }

@Composable
fun Flat(text: String, onClick: () -> Unit, color: Color = C.accent) =
    TextButton(onClick) { Text(text, color = color, maxLines = 1) }

/** Кнопка удаления с подтверждением. */
@Composable
fun DeleteButton(what: String, onConfirm: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    Flat("Удалить", { ask = true }, C.muted)
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Удалить $what?") },
        confirmButton = { TextButton({ ask = false; onConfirm() }) { Text("Удалить", color = C.bad) } },
        dismissButton = { TextButton({ ask = false }) { Text("Отмена") } },
        containerColor = C.cardHi,
    )
}

// ---------- ввод ----------
@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = true, lines: Int = 1, secret: Boolean = false, suffix: String? = null,
) {
    val suf: (@Composable () -> Unit)? = if (suffix == null) null else { { Text(suffix, color = C.muted) } }
    OutlinedTextField(
    value = value, onValueChange = onChange, modifier = modifier,
    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    suffix = suf,
    singleLine = lines == 1, minLines = lines,
    shape = RoundedCornerShape(12.dp),
    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
    )
}

/** Числовое поле, привязанное к модели: хранит свой текст, наружу отдаёт число. */
@Composable
fun NumBound(label: String, value: Double, onValue: (Double) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    var t by remember { mutableStateOf(if (value == 0.0) "" else value.r1()) }
    Field(label, t, { t = it; onValue(it.num() ?: 0.0) }, modifier, suffix = suffix)
}

/** Сетка полей по 2 в ряд. */
@Composable
fun <T> Grid2(items: List<T>, cell: @Composable (T, Modifier) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    items.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { cell(it, Modifier.weight(1f)) }
            if (row.size == 1) Box(Modifier.weight(1f))
        }
    }
}

/** Кружок дня недели: ✓ сделано, обводка — по плану, подсветка — сегодня. */
@Composable
fun DayDot(label: String, done: Boolean, planned: Boolean, today: Boolean, modifier: Modifier) = Column(
    modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = if (today) C.accent else C.muted)
    val bg = when { done -> C.good; else -> C.cardHi }
    Box(
        Modifier.height(34.dp).fillMaxWidth(0.8f).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (planned && !done) Modifier.border(2.dp, C.accent, RoundedCornerShape(10.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(when { done -> "✓"; planned -> "зал"; else -> "" }, color = if (done) C.bg else C.accent, style = MaterialTheme.typography.labelMedium)
    }
}

/** Кружок-галочка: пустой — не сделано, зелёный с ✓ — сделано. */
@Composable
fun CheckCircle(done: Boolean, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 24.dp) = Box(
    Modifier.size(size).clip(RoundedCornerShape(50)).background(if (done) C.good else C.bg)
        .border(2.dp, if (done) C.good else C.muted, RoundedCornerShape(50))
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { if (done) Text("✓", color = C.bg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
