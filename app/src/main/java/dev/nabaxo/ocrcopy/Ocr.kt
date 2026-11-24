package dev.nabaxo.ocrcopy

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.hypot
import kotlin.math.max

/** One recognized character. [after] is what follows it in copied text: "", " " or "\n". */
class Glyph(val text: String, val box: Rect, val word: Int, val after: String)

/** A frozen photo plus every character ML Kit found in it, in reading order. */
class Scan(val bitmap: Bitmap, val glyphs: List<Glyph>, val wordBoxes: List<Rect>)

fun buildScan(bitmap: Bitmap, text: Text): Scan {
    val glyphs = mutableListOf<Glyph>()
    val wordBoxes = mutableListOf<Rect>()

    for (block in text.textBlocks) {
        for (line in block.lines) {
            val elements = line.elements.filter { it.boundingBox != null && it.text.isNotEmpty() }
            elements.forEachIndexed { elementIndex, element ->
                val wordIndex = wordBoxes.size
                wordBoxes += element.boundingBox!!.toRect()

                val chars = splitIntoChars(element)
                val isLastWordOfLine = elementIndex == elements.lastIndex
                chars.forEachIndexed { charIndex, (char, box) ->
                    val isLastCharOfWord = charIndex == chars.lastIndex
                    val after = when {
                        !isLastCharOfWord -> ""
                        isLastWordOfLine -> "\n"
                        else -> " "
                    }
                    glyphs += Glyph(char, box, wordIndex, after)
                }
            }
        }
    }
    return Scan(bitmap, glyphs, wordBoxes)
}

/**
 * Per-character boxes. ML Kit usually gives symbols with boxes; if it doesn't,
 * cut the word box into equal slices so selection still works by character.
 */
private fun splitIntoChars(element: Text.Element): List<Pair<String, Rect>> {
    val symbols = element.symbols
    val symbolsUsable = symbols.isNotEmpty() && symbols.all { it.boundingBox != null }
    if (symbolsUsable) {
        return symbols.map { it.text to it.boundingBox!!.toRect() }
    }

    val wordBox = element.boundingBox!!.toRect()
    val chars = element.text.map { it.toString() }
    val sliceWidth = wordBox.width / chars.size
    return chars.mapIndexed { i, char ->
        val left = wordBox.left + i * sliceWidth
        char to Rect(left, wordBox.top, left + sliceWidth, wordBox.bottom)
    }
}

private fun android.graphics.Rect.toRect() =
    Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

fun selectedText(glyphs: List<Glyph>, range: IntRange): String = buildString {
    for (i in range) {
        append(glyphs[i].text)
        if (i < range.last) append(glyphs[i].after)
    }
}

fun wordRange(glyphs: List<Glyph>, index: Int): IntRange {
    val word = glyphs[index].word
    var first = index
    while (first > 0 && glyphs[first - 1].word == word) first--
    var last = index
    while (last < glyphs.lastIndex && glyphs[last + 1].word == word) last++
    return first..last
}

/** Index of the glyph closest to [point] (image coords), or null if none within [maxDistance]. */
fun glyphAt(glyphs: List<Glyph>, point: Offset, maxDistance: Float = Float.MAX_VALUE): Int? {
    var bestIndex = -1
    var bestDistance = Float.MAX_VALUE
    glyphs.forEachIndexed { i, glyph ->
        val distance = distanceToRect(glyph.box, point)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = i
        }
    }
    if (bestIndex < 0 || bestDistance > maxDistance) return null
    return bestIndex
}

private fun distanceToRect(rect: Rect, p: Offset): Float {
    val dx = max(max(rect.left - p.x, 0f), p.x - rect.right)
    val dy = max(max(rect.top - p.y, 0f), p.y - rect.bottom)
    return hypot(dx, dy)
}
