package by.zaberezh.forma.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.ai.Claude
import by.zaberezh.forma.core.food.FoodItem
import by.zaberezh.forma.core.food.LibraryResolver
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
        scope.launch {
            val st = ctx.settings
            var ai: Claude? = null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    LibraryResolver(ctx.store).resolve(q)
                        ?: st.apiKey.takeIf { it.isNotBlank() }?.let { Claude(it, st.model, st.apiUrl).also { x -> ai = x }.foods(q) }
                        ?: error("Нет в библиотеке, а API-ключ не задан (Настройки → Claude). Можно ввести КБЖУ вручную.")
                }
            }
            busy.value = false
            val spent = ai?.used?.takeIf { it > 0 }?.let { "потрачено ~${"%,d".format(it).replace(',', ' ')} токенов" }
            r.onSuccess { draft.value = (draft.value ?: emptyList()) + it; info.value = spent ?: "из библиотеки, без ИИ" }
                .onFailure { err.value = Claude.explain(it) + (spent?.let { "\n$it" } ?: "") }
            if (!visible) Notify.post(app, 5, if (r.isSuccess) "КБЖУ посчитано" else "КБЖУ: ошибка",
                listOf(if (r.isSuccess) "«$q» — проверь и сохрани во вкладке «Еда»" else (err.value ?: "")))
        }
    }

    fun clear() { draft.value = null; text.value = ""; err.value = null }
}
