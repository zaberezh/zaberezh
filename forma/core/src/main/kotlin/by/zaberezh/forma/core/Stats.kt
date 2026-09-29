package by.zaberezh.forma.core

import kotlin.math.roundToInt

/** Наклон МНК: y на x. null, если данных мало. */
fun slope(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2) return null
    val mx = points.sumOf { it.first } / points.size
    val my = points.sumOf { it.second } / points.size
    val den = points.sumOf { (it.first - mx) * (it.first - mx) }
    if (den == 0.0) return null
    return points.sumOf { (it.first - mx) * (it.second - my) } / den
}

fun Double.r1(): String = if (this == rint(this)) toLong().toString() else String.format(java.util.Locale.US, "%.1f", this)
fun Double.r2(): String = String.format(java.util.Locale.US, "%.2f", this)
fun Double.pct(): String = (if (this >= 0) "+" else "") + String.format(java.util.Locale.US, "%.1f", this) + "%"
fun Double.i(): Int = roundToInt()
private fun rint(d: Double) = Math.rint(d)
