package com.notokens.monolog.ime

import kotlin.math.abs
import kotlin.math.sqrt

internal data class SwipePoint(val x: Float, val y: Float) {
    fun distanceSquared(other: SwipePoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }
}

internal object QwertyLayout {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    const val width = 10f
    const val rowSpacing = 1.25f
    const val homeRowInset = .5f
    const val shiftWeight = 1.4f

    private val lastRowKeyWidth = width / (rows.last().length + 2 * shiftWeight)
    private val centers = rows.flatMapIndexed { row, letters ->
        val keyWidth = if (row == 2) lastRowKeyWidth else 1f
        val inset = when (row) {
            1 -> homeRowInset
            2 -> shiftWeight * keyWidth
            else -> 0f
        }
        letters.mapIndexed { column, letter ->
            letter to SwipePoint(inset + (column + .5f) * keyWidth, (row + .5f) * rowSpacing)
        }
    }.toMap()

    fun center(letter: Char): SwipePoint = centers.getValue(letter)

    fun letterAt(point: SwipePoint, rows: List<String> = this.rows): Char? {
        val row = (point.y / rowSpacing).toInt()
        if (point.y < 0f || row !in rows.indices) return null
        val inset = rowInset(row, rows)
        if (point.x !in inset..(width - inset)) return null
        val column = ((point.x - inset) * rows[row].length / (width - 2 * inset)).toInt()
        return rows[row].getOrNull(column)
    }

    private fun rowInset(row: Int, rows: List<String>): Float = when (row) {
        1 -> (width - rows[row].length) / 2f
        2 -> width * shiftWeight / (rows[row].length + 2 * shiftWeight)
        else -> 0f
    }

    fun nativeCenters(rows: List<String>): FloatArray {
        val centers = FloatArray(54) { 1000f }
        rows.forEachIndexed { row, keys ->
            val inset = rowInset(row, rows)
            val step = (width - 2 * inset) / keys.length
            keys.forEachIndexed { column, letter ->
                val index = when (letter) {
                    in 'a'..'z' -> letter - 'a'
                    'ñ' -> 26
                    else -> return@forEachIndexed
                }
                centers[index * 2] = inset + (column + .5f) * step
                centers[index * 2 + 1] = (row + .5f) * rowSpacing
            }
        }
        return centers
    }
}

internal class SwipeDecoder(words: List<String>, private val commonWords: Set<String> = emptySet()) {
    private val candidates = words.filter { word ->
        word.length in 2..24 && word.first() in 'a'..'z' && word.last() in 'a'..'z' &&
            word.all { it in 'a'..'z' || it == '\'' }
    }.groupBy { it.first() to it.last() }

    fun decode(points: List<SwipePoint>): String? {
        if (points.size < 2) return null
        val trace = SwipePath(points)
        if (trace.length < .55f) return null
        val samples = trace.samples()
        val startingKeys = near(points.first())
        val endingKeys = near(points.last())
        val start = points.first()
        val end = points.last()
        val chord = sqrt(start.distanceSquared(end))
        val directness = if (chord < .55f) 0f else {
            val detour = samples.maxOf {
                abs((end.x - start.x) * (it.y - start.y) - (end.y - start.y) * (it.x - start.x)) / chord
            }
            (1f - detour / .50f).coerceIn(0f, 1f)
        }
        var bestWord: String? = null
        var bestCost = 1.05f
        for ((first, startCost) in startingKeys) {
            for ((last, endCost) in endingKeys) {
                val words = candidates[first to last].orEmpty()
                val extraKeyCost = if (startCost + endCost <= .8f && directness > 0f &&
                    words.any { word -> word.count { it != '\'' } == 2 }) .16f * directness else 0f
                for (word in words) {
                    val keys = word.mapNotNull { it.takeIf { it != '\'' }?.let(QwertyLayout::center) }
                    val template = SwipePath(keys)
                    if (template.length < .55f) continue
                    val lengthCost = abs(template.length - trace.length) / (template.length + trace.length)
                    if (lengthCost > .45f) continue
                    val shape = template.samples()
                    var distance = 0f
                    for (i in samples.indices) distance += samples[i].distanceSquared(shape[i])
                    val extraVisits = keys.zipWithNext().count { (a, b) -> a != b }.minus(1).coerceAtLeast(0)
                    val cost = distance / samples.size + .55f * (startCost + endCost) +
                        lengthCost * .45f + extraKeyCost * extraVisits +
                        word.length * .006f - if (word in commonWords) .14f else 0f
                    if (cost < bestCost) {
                        bestCost = cost
                        bestWord = word
                    }
                }
            }
        }
        return bestWord
    }

    private fun near(point: SwipePoint): List<Pair<Char, Float>> = ('a'..'z').mapNotNull { letter ->
        val distance = point.distanceSquared(QwertyLayout.center(letter))
        if (distance <= 1.45f) letter to distance else null
    }
}

// Expand common suffixes marked by the bundled Hunspell dictionary.
internal fun swipeWordForms(word: String, flags: String): List<String> = buildList {
    add(word)
    if ('\'' in word) return@buildList
    val consonantY = word.endsWith('y') && word[word.lastIndex - 1] !in "aeiou"
    if ('G' in flags) add(word.removeSuffix("e") + "ing")
    if ('D' in flags) add(when {
        word.endsWith('e') -> word + "d"
        consonantY -> word.dropLast(1) + "ied"
        else -> word + "ed"
    })
    if ('S' in flags) add(when {
        consonantY -> word.dropLast(1) + "ies"
        word.last() in "sxzh" -> word + "es"
        else -> word + "s"
    })
    if ('Y' in flags) add(word + "ly")
}

private class SwipePath(private val points: List<SwipePoint>) {
    private val distances = FloatArray(points.size).also { distances ->
        for (i in 1 until points.size) {
            distances[i] = distances[i - 1] + sqrt(points[i - 1].distanceSquared(points[i]))
        }
    }
    val length: Float get() = distances.last()

    fun samples(): List<SwipePoint> {
        var next = 1
        // Compare paths by distance traveled so pauses and fast segments have the same weight.
        return List(20) { index ->
            val distance = length * index / 19
            while (next < points.lastIndex && distances[next] <= distance) next++
            val begin = points[next - 1]
            val end = points[next]
            val segment = distances[next] - distances[next - 1]
            val fraction = if (segment == 0f) 1f else (distance - distances[next - 1]) / segment
            SwipePoint(begin.x + (end.x - begin.x) * fraction, begin.y + (end.y - begin.y) * fraction)
        }
    }
}

internal fun formatSwipeWord(word: String, shift: ShiftState, previous: Char?): String {
    val separator = if (previous?.let { it.isLetterOrDigit() || it in ".,!?;:" } == true) " " else ""
    val text = when (shift) {
        ShiftState.OFF -> word
        ShiftState.ONCE -> word.replaceFirstChar { it.uppercaseChar() }
        ShiftState.CAPS_LOCK -> word.uppercase()
    }
    return separator + text
}
