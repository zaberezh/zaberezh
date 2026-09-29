@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package by.zaberezh.forma.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.Forma
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.r1

/** Контекст, пересоздаваемый при любом изменении данных. */
@Composable
fun rememberCtx(): Ctx {
    val v = Forma.store.version.intValue
    return remember(v) { Ctx(Forma.store) }
}

@Composable
fun Screen(content: LazyListScope.() -> Unit) = LazyColumn(
    Modifier.fillMaxSize().padding(horizontal = 12.dp),
    contentPadding = PaddingValues(vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
)

@Composable
fun Block(title: String? = null, content: @Composable ColumnScope.() -> Unit) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
fun Line(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)

@Composable
fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Err(text: String?) { if (!text.isNullOrBlank()) Text(text, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall) }

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = true, lines: Int = 1) =
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier,
        singleLine = lines == 1, minLines = lines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
    )

fun String.num(): Double? = trim().replace(',', '.').toDoubleOrNull()

fun Context.copy(text: String) =
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("forma", text))

/** Числовое поле, привязанное к модели: хранит свой текст, наружу отдаёт распарсенное число. */
@Composable
fun NumBound(label: String, value: Double, onValue: (Double) -> Unit, modifier: Modifier = Modifier) {
    var t by remember { mutableStateOf(if (value == 0.0) "" else value.r1()) }
    Field(label, t, { t = it; onValue(it.num() ?: 0.0) }, modifier)
}
