package by.zaberezh.forma.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import by.zaberezh.forma.core.study.CabinetData
import by.zaberezh.forma.core.study.Certificate
import by.zaberezh.forma.core.study.Certificates
import by.zaberezh.forma.core.study.GroupInfo
import by.zaberezh.forma.core.study.LabProgress
import by.zaberezh.forma.core.study.MarkRow
import by.zaberezh.forma.core.study.Markbook
import by.zaberezh.forma.core.study.Omissions
import by.zaberezh.forma.core.study.Person
import by.zaberezh.forma.core.study.Rating
import by.zaberezh.forma.core.study.RatingSubject
import java.util.Locale

// ---------- общее ----------
private val RU = Locale.forLanguageTag("ru")
fun Double.avg(digits: Int = 1): String = String.format(RU, "%.${digits}f", this)

/** Цвет оценки по 10-балльной шкале. */
fun gradeColor(x: Double?): Color = when {
    x == null -> C.muted
    x >= 8.5 -> C.good
    x >= 6.5 -> C.accent
    x >= 4.0 -> C.warn
    else -> C.bad
}

/** Оценка из зачётки: число, «зачтено», «не зачтено» или пусто → короткий текст и цвет. */
private fun markLook(mark: String): Pair<String, Color> {
    val m = mark.trim().lowercase()
    val n = m.replace(',', '.').toDoubleOrNull()
    return when {
        m.isEmpty() -> "—" to C.muted
        n != null -> mark.trim() to gradeColor(n)
        m.startsWith("не") || m.contains("неуд") || m.contains("неяв") -> "н/з" to C.bad
        m.contains("зач") -> "зач" to C.good
        else -> mark.trim().take(3) to C.accent
    }
}

/** Квадратная плашка с оценкой. */
@Composable
private fun Grade(text: String, color: Color, size: Dp = 44.dp) = Box(
    Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center,
) { Text(text, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1) }

/** Маленькая отметка в ряду отметок. */
@Composable
private fun MarkChip(mark: Int) = Box(
    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(gradeColor(mark.toDouble()).copy(alpha = 0.16f)), contentAlignment = Alignment.Center,
) { Text("$mark", color = gradeColor(mark.toDouble()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }

/** Крупная цифра с подписью — для сводок. */
@Composable
private fun Figure(value: String, label: String, modifier: Modifier, color: Color = C.text) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    Text(label, style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center)
}

@Composable
private fun Bar(fraction: Float, color: Color) = Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
}

@Composable
private fun Empty(text: String) = Block { Muted(text) }

private fun short(d: String) = d.take(5)   // dd.MM.yyyy → dd.MM

private fun hours(n: Int) = "$n ч"

private fun people(n: Int) = when {
    n % 100 in 11..14 -> "$n человек"
    n % 10 in 2..4 -> "$n человека"
    else -> "$n человек"
}

/** Краткая строка для плитки раздела, когда данные уже загружены. */
fun summaryOf(d: CabinetData?): String? = when (d) {
    is Rating -> listOfNotNull(
        d.average?.let { "средний ${it.avg()}" } ?: "отметок пока нет",
        d.labsTotal.takeIf { it > 0 }?.let { "лабы ${d.labsDone}/$it" },
    ).joinToString(" · ")
    is Markbook -> d.average?.let { "средний балл ${it.avg(2)}" } ?: "оценок пока нет"
    is Omissions -> if (d.total == 0 && d.unexcused.isEmpty()) "пропусков нет" else "${hours(d.total)} без уважительной"
    is GroupInfo -> people(d.students.size) + (d.curator?.let { " · куратор ${initials(it.fio)}" } ?: "")
    is Certificates -> if (d.items.isEmpty()) "справок нет" else "${d.items.size} шт" +
        d.items.count { it.status == 2 }.takeIf { it > 0 }?.let { " · $it в работе" }.orEmpty()
    is Person -> listOf(d.faculty, d.speciality).filter(String::isNotBlank).joinToString(" · ").ifBlank { null }
    null -> null
}

/** «Шепетюк Виталий Васильевич» → «Шепетюк В. В.» */
fun initials(fio: String): String {
    val p = fio.split(" ").filter(String::isNotBlank)
    return (listOfNotNull(p.firstOrNull()) + p.drop(1).map { "${it.first()}." }).joinToString(" ")
}

/** Раздел целиком — элементы ленты. */
fun LazyListScope.cabinetSection(d: CabinetData, me: String) {
    when (d) {
        is Rating -> rating(d)
        is Markbook -> markbook(d)
        is Omissions -> omissions(d)
        is GroupInfo -> group(d, me)
        is Certificates -> certificates(d)
        is Person -> item { PersonView(d) }
    }
}

// ---------- успеваемость ----------
private fun LazyListScope.rating(r: Rating) {
    item {
        Block {
            Row(Modifier.fillMaxWidth()) {
                Figure(r.average?.avg() ?: "—", "средний балл", Modifier.weight(1f), gradeColor(r.average))
                Figure("${r.marks.size}", "отметок", Modifier.weight(1f))
                Figure(hours(r.missed), "пропущено", Modifier.weight(1f), if (r.missed > 0) C.warn else C.text)
            }
            if (r.labsTotal > 0) {
                HorizontalDivider(color = C.line)
                Line {
                    Text("Лабы и задания", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${r.labsDone} из ${r.labsTotal}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Bar(r.labsDone.toFloat() / r.labsTotal, if (r.labsDone >= r.labsTotal) C.good else C.accent)
                if (r.labsOverdue > 0) Text("Просрочено сроков: ${r.labsOverdue}", color = C.bad, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (r.subjects.isEmpty()) item { Empty("В ИИС пока нет предметов этого семестра.") }
    r.subjects.forEach { s -> item { SubjectCard(s) } }
}

@Composable
private fun SubjectCard(s: RatingSubject) {
    var open by remember { mutableStateOf(false) }
    val marks = s.types.flatMap { t -> t.lessons.flatMap { it.marks.map { m -> m.mark } } }
    Block {
        Column(Modifier.animateContentSize().fillMaxWidth().clickable { open = !open }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Muted(listOfNotNull(
                        s.types.map { it.abbrev }.filter(String::isNotBlank).distinct().joinToString(" · ").ifBlank { null },
                        if (marks.isEmpty()) "отметок нет" else "отметок: ${marks.size}",
                        s.missed.takeIf { it > 0 }?.let { "пропуск ${hours(it)}" },
                    ).joinToString(" · "))
                }
                Grade(s.average?.avg() ?: "—", gradeColor(s.average))
            }
            s.types.forEach { t -> t.labs?.let { LabLine(t.abbrev, it) } }
            if (marks.isNotEmpty()) Buttons { marks.takeLast(if (open) marks.size else 12).forEach { MarkChip(it) } }
            if (open) {
                val rows = s.types.flatMap { t -> t.lessons.filter { it.marks.isNotEmpty() || it.missed > 0 }.map { t.abbrev to it } }
                    .sortedBy { (_, l) -> l.date.split('.').reversed().joinToString("") }
                if (rows.isEmpty()) Muted("Занятий с отметками или пропусками пока нет.")
                else {
                    HorizontalDivider(color = C.line)
                    rows.forEach { (type, l) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(short(l.date), Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                            Text(type, Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall, color = C.muted)
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                l.marks.forEach { MarkChip(it.mark) }
                                l.marks.mapNotNull { it.task }.takeIf { it.isNotEmpty() }?.let { Muted("зад. ${it.joinToString(", ")}") }
                            }
                            if (l.missed > 0) Text("−${hours(l.missed)}", color = C.warn, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                s.percents.forEach { (date, pct) -> Stat("Тест ${short(date)}", "${pct.avg(0)}%") }
            }
            Muted(if (open) "Свернуть" else "Подробнее по занятиям", Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun LabLine(type: String, p: LabProgress) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Line {
        Text(if (type.isBlank()) "Задания" else "$type — сдано", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("${p.done} из ${p.total}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = if (p.done >= p.total) C.good else C.text)
    }
    Bar(p.done.toFloat() / p.total, if (p.done >= p.total) C.good else C.accent)
    when {
        p.overdue > 0 -> Text("Просрочено: ${p.overdue}" + (p.next?.let { " · следующий срок ${short(it)}" } ?: ""), color = C.bad, style = MaterialTheme.typography.bodySmall)
        p.next != null -> Muted("Следующий срок: ${short(p.next!!)}" + (p.nextTask?.let { " — задание №$it" } ?: ""))
        else -> {}
    }
}

// ---------- зачётка ----------
private fun LazyListScope.markbook(m: Markbook) {
    item {
        Block {
            Row(Modifier.fillMaxWidth()) {
                Figure(m.average?.avg(2) ?: "—", "средний балл", Modifier.weight(1f), gradeColor(m.average))
                Figure(m.semesters.sumOf { s -> s.marks.count { it.mark.isNotBlank() } }.toString(), "оценок", Modifier.weight(1f))
                Figure(m.number.ifBlank { "—" }, "№ зачётки", Modifier.weight(1.4f))
            }
        }
    }
    if (m.semesters.isEmpty()) item { Empty("Зачётная книжка пока пустая.") }
    m.semesters.forEach { s ->
        item {
            Block("${s.number} семестр", trailing = { s.average?.let { Pill("средний ${it.avg(2)}", gradeColor(it)) } }) {
                if (s.marks.isEmpty()) Muted("Предметов пока нет.")
                if (s.marks.isNotEmpty() && s.marks.all { it.mark.isBlank() }) Muted("Оценок пока нет — ниже то, что предстоит сдать.")
                s.marks.forEachIndexed { i, x ->
                    if (i > 0) HorizontalDivider(color = C.line)
                    MarkLine(x)
                }
            }
        }
    }
}

@Composable
private fun MarkLine(x: MarkRow) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(x.full, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Muted(listOfNotNull(
            x.form.ifBlank { null },
            x.hours.ifBlank { null }?.let { "$it ч" },
            x.credits?.let { "${it.avg(1)} з.е." },
            x.date.ifBlank { null },
        ).joinToString(" · "))
        if (x.teacher.isNotBlank()) Muted(x.teacher)
        if (x.retakes > 0) Text("Пересдач: ${x.retakes}", color = C.warn, style = MaterialTheme.typography.bodySmall)
    }
    val (t, c) = markLook(x.mark)
    Grade(t, c)
}

// ---------- пропуски ----------
private fun LazyListScope.omissions(o: Omissions) {
    item {
        Block {
            BigValue("${o.total}", "ч", "без уважительной причины в этом семестре")
            if (o.total == 0 && o.unexcused.isEmpty()) Note("Пропусков без уважительной причины нет")
            val max = o.monthly.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            o.monthly.forEach { (month, n) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(month.replaceFirstChar { it.uppercase() }, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f)) { Bar(n.toFloat() / max, if (n == 0) C.line else C.warn) }
                    Text(hours(n), Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, color = if (n > 0) C.warn else C.muted)
                }
            }
        }
    }
    o.unexcused.groupBy { it.term }.toSortedMap(compareByDescending { it }).forEach { (term, list) ->
        item {
            Block(if (term > 0) "Без уважительной — $term семестр" else "Без уважительной причины", trailing = { Pill(hours(list.sumOf { it.hours }), C.warn) }) {
                list.forEachIndexed { i, x ->
                    if (i > 0) HorizontalDivider(color = C.line)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(short(x.date), Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                        Column(Modifier.weight(1f)) {
                            Text(x.subject, style = MaterialTheme.typography.bodyMedium)
                            if (x.type.isNotBlank()) Muted(x.type)
                        }
                        Text(hours(x.hours), color = C.warn, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
    if (o.certificates.isNotEmpty()) item {
        Block("Справки об уважительной причине") {
            o.certificates.forEachIndexed { i, x ->
                if (i > 0) HorizontalDivider(color = C.line)
                Text(x.name.ifBlank { "Справка" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Muted(listOf(x.from, x.to).filter(String::isNotBlank).joinToString(" — "))
                if (x.note.isNotBlank()) Muted(x.note)
            }
        }
    }
}

// ---------- группа ----------
private fun LazyListScope.group(g: GroupInfo, me: String) {
    g.curator?.let { c -> item { CuratorCard(c) } }
    item {
        Block("Группа ${g.number}", trailing = { Pill(people(g.students.size)) }) {
            g.students.filter { it.head }.forEach { h -> Muted("Староста — ${h.fio}") }
            g.students.forEachIndexed { i, st ->
                val self = me.isNotBlank() && st.fio.equals(me, ignoreCase = true)
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${i + 1}", Modifier.width(24.dp), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.End)
                    Text(st.fio, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = if (self) C.accent else C.text, fontWeight = if (self || st.head) FontWeight.SemiBold else FontWeight.Normal)
                    if (st.head) Pill("староста")
                    if (self) Pill("ты", C.good)
                }
            }
        }
    }
}

@Composable
private fun CuratorCard(c: by.zaberezh.forma.core.study.Curator) {
    val ctx = LocalContext.current
    Block("Куратор") {
        Text(c.fio, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (c.position.isNotBlank() && !c.position.equals("куратор", true)) Muted(c.position)
        Buttons {
            if (c.phone.isNotBlank()) Secondary("Позвонить · ${c.phone}", { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}"))) })
            if (c.email.isNotBlank()) Secondary("Написать", { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${c.email}"))) })
        }
    }
}

// ---------- справки ----------
private fun LazyListScope.certificates(d: Certificates) {
    if (d.items.isEmpty()) item { Empty("Заказанных справок нет. Заказать можно на сайте ИИС.") }
    d.items.forEach { x -> item { CertificateCard(x) } }
}

@Composable
private fun CertificateCard(x: Certificate) {
    val color = when (x.status) { 1 -> C.good; 2 -> C.warn; 3 -> C.bad; else -> C.muted }
    Block("Справка №${x.number}", trailing = { Pill(x.statusText, color) }) {
        if (x.place.isNotBlank()) Text(x.place.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
        if (x.type.isNotBlank()) Stat("Вид", x.type)
        if (x.ordered.isNotBlank()) Stat("Заказана", x.ordered)
        if (x.issued.isNotBlank()) Stat(if (x.status == 1) "Готова" else "Будет готова", x.issued)
        if (x.rejection.isNotBlank()) Err("Причина отказа: ${x.rejection}")
    }
}

// ---------- обо мне ----------
@Composable
private fun PersonView(p: Person) = Block {
    if (p.fioBy.isNotBlank()) Muted(p.fioBy)
    listOf(
        "Дата рождения" to p.birth, "Факультет" to p.faculty, "Специальность" to p.speciality,
        "Курс" to (p.course?.toString() ?: ""), "Группа" to p.group, "Почта" to p.email, "Телефон" to p.phone,
        "Рейтинг" to (p.rating?.toString() ?: ""),
    ).filter { it.second.isNotBlank() }.forEach { (k, v) -> Stat(k, v) }
}
