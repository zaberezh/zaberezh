package by.zaberezh.forma.core.gym

/**
 * Вариант движения: одна мышца, но разная работа — угол, растяжение, функция. Разные варианты растят мышцу
 * полнее, чем один и тот же, повторённый дважды (региональная гипертрофия):
 * - спина: вертикальная тяга (подтягивания, верхний блок — ширина) и горизонтальная (тяги к поясу — толщина);
 * - грудь: горизонтальный жим, наклонный (верх груди — Chaves 2024), брусья/вниз головой, сведения;
 * - бицепс бедра: тяга от бедра (румынская — разгибание таза) и сгибания ног (сгибание колена) — это две разные
 *   функции, одной не закрыть другую (Maeo 2021: сгибания сидя растят и в верхней части бедра);
 * - трицепс: из-за головы (длинная головка в растяжении — больше роста, Maeo 2023) и разгибания на блоке;
 * - бицепс: обычные сгибания, в растяжении (на наклонной), «короткие» (Скотт), молотки (плечевая мышца);
 * - икры: стоя (икроножная) и сидя (камбаловидная).
 */
fun pattern(e: Exercise): String {
    val n = e.name.lowercase().replace('ё', 'е')
    fun has(vararg w: String) = w.any { it in n }
    return when (e.muscles.maxByOrNull { it.value }?.key) {
        "chest" -> when {
            has("брусь", "dips", "вниз головой", "отрицательн", "decline") -> "dips"
            has("наклон", "инклайн", "incline", "верх груди") -> "incline"
            has("сведен", "развод", "бабочк", "кроссовер", "пек-дек", "пек дек", "fly", "пуловер", "pullover") -> "fly"
            else -> "flat"
        }
        "back" -> when {
            has("подтяг", "верхн", "вертикал", "пулдаун", "pulldown", "pull-up", "pullup", "chin") -> "vertical"
            has("пуловер", "прямыми руками", "pullover") -> "pullover"
            has("шраг", "shrug") -> "traps"
            else -> "horizontal"   // тяги в наклоне, к поясу, горизонтального блока, Т-гриф, одной рукой
        }
        "side_delts" -> when {
            has("жим", "press") -> "press"
            has("подбородк", "upright", "тяга") -> "upright"
            has("кроссовер", "блок", "трос", "cable") -> "cable"
            else -> "raise"
        }
        "front_delts" -> if (has("жим", "press")) "press" else "raise"
        "rear_delts" -> if (has("лицу", "face")) "facepull" else "fly"
        "biceps" -> when {
            has("молот", "hammer", "нейтральн") -> "hammer"
            has("наклон", "incline", "байес", "bayes", "за спин") -> "stretch"
            has("скотт", "проповед", "preacher", "концентр", "spider", "паук") -> "short"
            has("подтяг", "тяга", "chin") -> "pull"
            else -> "curl"
        }
        "triceps" -> when {
            has("из-за голов", "за голову", "над голов", "overhead") || has("француз") && has("сидя", "стоя") -> "overhead"
            has("француз", "лежа", "лёжа", "skull") -> "lying"
            has("брусь", "узк", "жим", "отжим", "dips", "close") -> "press"
            else -> "pushdown"   // разгибания на блоке, канат, кикбеки
        }
        "quads" -> when {
            has("разгибан", "extension") -> "extension"
            has("выпад", "болгар", "зашаг", "сплит", "lunge", "split", "step") -> "single"
            else -> "squat"      // присед, жим ногами, гакк
        }
        "hams" -> if (has("сгибан", "curl", "нордич", "nordic")) "curl" else "hinge"
        "glutes" -> when {
            has("мост", "thrust", "хип") -> "thrust"
            has("отведен", "abduct") -> "abduction"
            else -> "glute"
        }
        "calves" -> if (has("сидя", "seated")) "seated" else "standing"
        "abs" -> when {
            has("подъем ног", "подъём ног", "в висе", "leg raise") -> "legs"
            has("планк", "ролик", "plank", "wheel") -> "brace"
            else -> "crunch"
        }
        "forearms" -> when {
            has("разгибан", "ладони вниз", "extension") -> "extensors"
            has("обратн", "молот", "reverse", "hammer") -> "brachioradialis"
            has("вис", "фермер", "удержан", "hang", "farmer", "hold") -> "grip"
            else -> "flexors"
        }
        else -> "other"
    }
}

/**
 * Чего не хватает в базе, чтобы тренировки недели дополняли друг друга, а не повторялись.
 * Только важное: варианты, без которых мышца растёт заметно хуже, и мышцы с одним упражнением на всю неделю.
 */
fun baseAdvice(p: Program): List<String> {
    val by = p.exercises.groupBy { it.muscles.maxByOrNull { m -> m.value }?.key.orEmpty() }
    fun pats(m: String) = by[m].orEmpty().map(::pattern).toSet()
    val out = mutableListOf<String>()
    fun need(m: String, pat: String, text: String) { if (by[m].orEmpty().isNotEmpty() && pat !in pats(m)) out += text }
    need("back", "vertical", "Спина: нет вертикальной тяги (подтягивания, верхний блок) — она даёт ширину спины.")
    need("back", "horizontal", "Спина: нет горизонтальной тяги (тяга в наклоне, к поясу, горизонтальный блок) — она даёт толщину.")
    need("hams", "curl", "Бицепс бедра: нет сгибаний ног — румынская тяга не нагружает его функцию сгибания колена.")
    need("hams", "hinge", "Бицепс бедра: нет румынской тяги — сгибания ног не тренируют разгибание таза.")
    need("triceps", "overhead", "Трицепс: нет разгибаний из-за головы — длинная головка растёт лучше в растяжении.")
    val chest = pats("chest")
    if (by["chest"].orEmpty().isNotEmpty() && chest.none { it == "flat" || it == "incline" || it == "dips" })
        out += "Грудь: нет жима — сведения хорошо дополняют жим, но не заменяют его."
    else if ("flat" in chest && "incline" !in chest) out += "Грудь: нет жима на наклонной — верх груди растёт от него лучше."
    // одно упражнение на мышцу, которую тренируешь 2+ раза в неделю, — каждый раз одно и то же
    val single = listOf("chest", "back", "side_delts", "biceps", "triceps", "quads").filter { by[it].orEmpty().size == 1 }
    if (single.isNotEmpty()) out += "Одно упражнение на " + single.joinToString(", ") { MUSCLES[it]?.substringBefore(" (") ?: it } +
        " — добавь второй вариант, чтобы тренировки недели дополняли друг друга."
    return out
}
