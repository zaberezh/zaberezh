package by.zaberezh.forma.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import by.zaberezh.forma.core.Ctx
import by.zaberezh.forma.core.ai.Claude
import by.zaberezh.forma.core.food.FoodItem
import by.zaberezh.forma.core.food.Edostavka
import by.zaberezh.forma.core.food.LibraryResolver
import by.zaberezh.forma.core.food.WebHints
import by.zaberezh.forma.core.food.shopResolve
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
            var how = ""
            var missing: String? = null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    LibraryResolver(ctx.store).resolve(q)?.also { how = "из своей библиотеки, без ИИ" } ?: run {
                        // 1) магазинные продукты — прямо с edostavka.by (точно и бесплатно)
                        val shop = shopResolve(q, Edostavka(log = { Claude.debug("Едоставка: $it") }))
                        val rest = shop.unresolved.joinToString(", ")
                        when {
                            rest.isEmpty() -> shop.items.also { how = "с edostavka.by, без ИИ" }
                            st.apiKey.isBlank() -> if (shop.items.isNotEmpty()) shop.items.also {
                                how = "с edostavka.by"; missing = "Не найдено: «$rest» — добавь вручную или задай API-ключ."
                            } else error("Не нашёл на edostavka.by, а API-ключ не задан (Настройки → Claude). Можно ввести КБЖУ вручную.")
                            // 2) остальное — Claude, с найденными страницами магазина как подсказкой
                            else -> shop.items + Claude(st.apiKey, st.model, st.apiUrl).also { x -> ai = x }.foods(rest, shop.hints,
                                // поиск калорийности в интернете прямо с телефона (у посредника веб-поиска может не быть)
                                shop.unresolved.take(3).flatMap { part -> runCatching { WebHints.find(part) }.getOrDefault(emptyList()).take(4) }
                                    .also { Claude.debug("интернет: найдено строк ${it.size}") },
                            ).also {
                                how = if (shop.items.isEmpty()) "Claude" else "${shop.items.size} с edostavka.by + Claude"
                            }
                        }
                    }
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
