package by.zaberezh.forma.core.gym

import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.r1
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Компактный запрос к Claude на план дня: база упражнений, история 14 дней, неделя и черновик алгоритма. */
fun aiPlanPrompt(ctx: Ctx, date: LocalDate, focus: String?): String = buildString {
    val s = ctx.store
    val p = GymModule.program(s)
    val ru = Locale.forLanguageTag("ru")
    val stored = GymModule.storedPlan(s, date)
    val draft = GymModule.planFor(ctx, date, focus ?: stored?.focus, stored?.day)
    val type = GymModule.split(ctx).firstOrNull { it.id == draft.day }
    appendLine("Составь тренировку на $date (${date.dayOfWeek.getDisplayName(TextStyle.FULL, ru)}). " +
        "Тип дня: ${type?.let { "${it.title} — только мышцы ${it.muscles.joinToString(",")}" } ?: "всё тело"}. " +
        "Акцент дня: ${focus?.let { FOCUS[it]?.first } ?: "нет"}.")
    appendLine("\nБаза упражнений (id | название | мышцы | схема | сейчас):")
    p.exercises.forEach { e ->
        appendLine("${e.id} | ${e.name} | ${e.muscles.entries.joinToString(",") { "${it.key}${if (it.value < 1) "½" else ""}" }} | " +
            "${e.sets}×${e.repMin}-${e.repMax} | ${GymModule.strength(s, e)}")
    }
    appendLine("\nТренировки за 14 дней (дата: упражнение лучший подход):")
    GymModule.sessions(s).filter { it.first > date.minusDays(15) && it.first < date }.forEach { (d, sets) ->
        appendLine("$d: " + sets.groupBy { it.ex }.entries.joinToString("; ") { (id, l) ->
            val b = l.maxBy { it.w * (1 + it.r / 30.0) }
            "${p.ex(id)?.name ?: id} ${b.w.r1()}×${b.r} (${l.size} подх.)"
        })
    }
    val wk = GymModule.week(ctx)
    appendLine("\nДни зала на этой неделе: ${wk.plan.joinToString()}")
    appendLine("Черновик алгоритма: " + draft.items.joinToString("; ") { "${it.ex}×${it.sets}" })
    appendLine("\nПравила: только id из базы; 15–20 рабочих подходов; сначала базовые, потом изоляция; " +
        "не бери мышцу, нагруженную вчера; приоритет — видимый рост (средняя дельта, спина, грудь, руки); " +
        "порядок и пары суперсетов приложение расставит само по методике. " +
        "Верни план вызовом report_plan, в note — 1–2 предложения, почему так (без мотивации).")
}
