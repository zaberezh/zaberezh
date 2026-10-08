package by.zaberezh.forma.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.ai.Claude
import by.zaberezh.forma.core.food.FoodItem
import by.zaberezh.forma.core.food.FoodPipeline
import by.zaberezh.forma.sys.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Состояние поиска КБЖУ живёт вне экрана: уход с вкладки «Еда» не сбрасывает ни поиск, ни черновик.
 * Если поиск закончился, пока вкладка закрыта, — уведомление.
 */
object FoodSearch {
    val text = mutableStateOf("")
    val draft = mutableStateOf<List<FoodItem>?>(null)
    val busy = mutableStateOf(false)
    val err = mutableStateOf<String?>(null)
    val info = mutableStateOf<String?>(null)
    @Volatile var visible = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun resolve(ctx: Ctx, c: Context) {
        val q = text.value.trim()
        if (q.isEmpty() || busy.value) return
        busy.value = true; err.value = null; info.value = null
        val app = c.applicationContext
        val apiKey = by.zaberezh.forma.sys.Secrets.claudeKey(c)
        scope.launch {
            val st = ctx.settings
            var ai: Claude? = null
            var how = ""
            var missing: String? = null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val pipeline = FoodPipeline(ctx.store,
                        claude = if (apiKey.isBlank()) null else { rest, hints ->
                            Claude(apiKey, st.model, st.apiUrl).also { x -> ai = x }.foods(rest, hints)
                        },
                        stage = { msg -> scope.launch { info.value = msg } },
                        log = { Claude.debug("поиск: $it") },
                        edostavka = by.zaberezh.forma.sys.EdoBrowser)
                    val res = pipeline.run(q)
                    how = res.how
                    res.error?.let { if (res.items.isEmpty()) throw it }
                    if (res.missing.isNotEmpty()) missing = "Не найдено: «${res.missing.joinToString(", ")}» — " +
                        (res.error?.let { "Claude: ${Claude.explain(it)}. " } ?: if (apiKey.isBlank()) "задай API-ключ Claude (Настройки) или " else "") +
                        "добавь вручную (кнопка «Править КБЖУ»)."
                    if (res.items.isEmpty() && res.missing.isNotEmpty()) error(missing!!)
                    res.items
                }
            }
            busy.value = false
            val spent = ai?.let { a -> a.used.takeIf { it > 0 }?.let { " · режим: ${a.mode} · ~${"%,d".format(it).replace(',', ' ')} токенов" } } ?: ""
            r.onSuccess { draft.value = (draft.value ?: emptyList()) + it; info.value = how + spent; err.value = missing }
                .onFailure { err.value = Claude.explain(it) + spent }
            if (!visible) Notify.post(app, 5, if (r.isSuccess) "КБЖУ посчитано" else "КБЖУ: ошибка",
                listOf(if (r.isSuccess) "«$q» — проверь и сохрани во вкладке «Еда»" else (err.value ?: "")))
        }
    }

    fun clear() { draft.value = null; text.value = ""; err.value = null }
}
