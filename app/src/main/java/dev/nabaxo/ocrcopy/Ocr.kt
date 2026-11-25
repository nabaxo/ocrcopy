package dev.nabaxo.ocrcopy

import android.graphics.Bitmap
import android.graphics.Point
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.hypot
import kotlin.math.max

/**
 * A tilted rectangle following the text: 4 points, clockwise from the text's own
 * top-left (TL, TR, BR, BL). Same order ML Kit uses for cornerPoints.
 */
typealias Quad = List<Offset>

/**
 * One recognized character. [after] is what follows it in copied text: "", " " or "\n".
 * [box] is the upright bounds of [quad], used for hit-testing.
 */
class Glyph(val text: String, val quad: Quad, val word: Int, val line: Int, val after: String) {
    val box: Rect = boundsOf(quad)
}

/**
 * Padding around text, as fractions of the line height: more along the text than across it.
 * The bright (undimmed) line areas lose some size at their corners to the gooey rounding,
 * so the selection highlight gets less padding to sit clearly inside them.
 * Along the text the highlight still gets a fair margin: ML Kit's word and character boxes
 * can be off by about half a letter on tilted text, and this hides that.
 */
// Bright area along the text = selection padding + ~0.3 lost to the gooey corner rounding.
private const val TEXT_PAD_ALONG = 0.6f
private const val TEXT_PAD_ACROSS = 0.3f
private const val SELECTION_PAD_ALONG = 0.3f
private const val SELECTION_PAD_ACROSS = 0.15f
// Selection end that cuts through a word: less padding, so it doesn't spill over the
// unselected neighbour, but some, so a char box that is off a bit doesn't show bare.
private const val SELECTION_PAD_MID_WORD = 0.1f

/**
 * A frozen photo plus every character ML Kit found in it, in reading order.
 * [lineQuads] are padded line shapes; [textHeight] is the median line height in image pixels.
 */
class Scan(
    val bitmap: Bitmap,
    val glyphs: List<Glyph>,
    val lineQuads: List<Quad>,
    val textHeight: Float,
)

fun buildScan(bitmap: Bitmap, text: Text): Scan {
    val glyphs = mutableListOf<Glyph>()
    val lineQuads = mutableListOf<Quad>()
    val lineHeights = mutableListOf<Float>()
    var wordIndex = 0

    for (block in text.textBlocks) {
        for (line in block.lines) {
            val lineQuad = quadOf(line.cornerPoints, line.boundingBox) ?: continue
            val elements = line.elements.filter {
                it.text.isNotEmpty() && quadOf(it.cornerPoints, it.boundingBox) != null
            }
            if (elements.isEmpty()) continue

            val lineIndex = lineQuads.size
            val lineStart = glyphs.size

            elements.forEachIndexed { elementIndex, element ->
                // ML Kit occasionally returns two words as one element with a space inside
                // ("SEK 52063492814"). Split there, so double-tap/long-press stop at the space.
                val words = splitAtSpaces(splitIntoChars(element))
                words.forEachIndexed { wordInElement, chars ->
                    val isLastWordOfLine =
                        elementIndex == elements.lastIndex && wordInElement == words.lastIndex
                    chars.forEachIndexed { charIndex, (char, quad) ->
                        val isLastCharOfWord = charIndex == chars.lastIndex
                        val after = when {
                            !isLastCharOfWord -> ""
                            isLastWordOfLine -> "\n"
                            else -> " "
                        }
                        glyphs += Glyph(char, quad, wordIndex, lineIndex, after)
                    }
                    wordIndex++
                }
            }

            // ML Kit's line corner points are sometimes short at the start on tilted text
            // (first letter left outside). The per-character points are accurate, so build
            // the line shape from those instead.
            val lineGlyphs = glyphs.subList(lineStart, glyphs.size)
            if (lineGlyphs.isEmpty()) continue
            val enclosing = enclosingQuad(lineGlyphs, lineQuad)
            lineHeights += (enclosing[3] - enclosing[0]).getDistance()
            lineQuads += padQuad(enclosing, TEXT_PAD_ALONG, TEXT_PAD_ACROSS)
        }
    }
    val textHeight = lineHeights.sorted().getOrElse(lineHeights.size / 2) { 0f }
    return Scan(bitmap, glyphs, lineQuads, textHeight)
}

/** Split a word's characters at whitespace characters, dropping them and empty pieces. */
private fun splitAtSpaces(chars: List<Pair<String, Quad>>): List<List<Pair<String, Quad>>> {
    val words = mutableListOf<List<Pair<String, Quad>>>()
    var current = mutableListOf<Pair<String, Quad>>()
    for (char in chars) {
        if (char.first.isBlank()) {
            if (current.isNotEmpty()) words += current
            current = mutableListOf()
        } else {
            current += char
        }
    }
    if (current.isNotEmpty()) words += current
    return words
}

/**
 * Shapes for a selection: one tilted rectangle per line the selection covers, from the
 * run's first glyph's left edge to its last glyph's right edge, padded a bit less than
 * the line areas so it stays inside them.
 * An end that cuts through a word gets less padding along the text: full padding would
 * spill over the neighbouring, unselected character and make it look selected.
 */
fun selectionQuads(glyphs: List<Glyph>, range: IntRange): List<Quad> {
    val quads = mutableListOf<Quad>()
    var runStart = range.first
    for (i in range) {
        val endsRun = i == range.last || glyphs[i + 1].line != glyphs[i].line
        if (!endsRun) continue
        val first = glyphs[runStart].quad
        val last = glyphs[i].quad
        val run = listOf(first[0], last[1], last[2], first[3])
        val startsMidWord = runStart > 0 && glyphs[runStart - 1].word == glyphs[runStart].word
        val endsMidWord = i < glyphs.lastIndex && glyphs[i + 1].word == glyphs[i].word
        val startPad = if (startsMidWord) SELECTION_PAD_MID_WORD else SELECTION_PAD_ALONG
        val endPad = if (endsMidWord) SELECTION_PAD_MID_WORD else SELECTION_PAD_ALONG
        quads += padQuad(run, startPad, endPad, SELECTION_PAD_ACROSS)
        runStart = i + 1
    }
    return quads
}

/**
 * Smallest rectangle, tilted along the text direction, that holds every glyph of a line.
 * Direction comes from the first to last glyph's bottom-left corner; falls back to
 * the ML Kit line quad's top edge for one-character lines.
 */
private fun enclosingQuad(lineGlyphs: List<Glyph>, lineQuad: Quad): Quad {
    val baseline = lineGlyphs.last().quad[3] - lineGlyphs.first().quad[3]
    val along = if (baseline.getDistance() > 1f) unit(baseline) else unit(lineQuad[1] - lineQuad[0])
    val down = Offset(-along.y, along.x) // along rotated 90° clockwise (y points down)

    val origin = lineGlyphs.first().quad[0]
    var minA = Float.MAX_VALUE
    var maxA = -Float.MAX_VALUE
    var minD = Float.MAX_VALUE
    var maxD = -Float.MAX_VALUE
    for (glyph in lineGlyphs) {
        for (p in glyph.quad) {
            val a = dot(p - origin, along)
            val d = dot(p - origin, down)
            minA = minOf(minA, a)
            maxA = maxOf(maxA, a)
            minD = minOf(minD, d)
            maxD = maxOf(maxD, d)
        }
    }
    fun corner(a: Float, d: Float) = origin + along * a + down * d
    return listOf(corner(minA, minD), corner(maxA, minD), corner(maxA, maxD), corner(minA, maxD))
}

private fun dot(a: Offset, b: Offset) = a.x * b.x + a.y * b.y

/**
 * Per-character quads. ML Kit usually gives symbols with corner points; if it doesn't,
 * cut the word's quad into equal slices along the text direction.
 */
private fun splitIntoChars(element: Text.Element): List<Pair<String, Quad>> {
    val symbols = element.symbols
    val symbolQuads = symbols.map { quadOf(it.cornerPoints, it.boundingBox) }
    if (symbols.isNotEmpty() && symbolQuads.all { it != null }) {
        val quads = trimOversizedEnds(symbolQuads.map { it!! })
        return symbols.mapIndexed { i, symbol -> symbol.text to quads[i] }
    }

    val (tl, tr, br, bl) = quadOf(element.cornerPoints, element.boundingBox)!!
    val chars = element.text.map { it.toString() }
    return chars.mapIndexed { i, char ->
        val t0 = i.toFloat() / chars.size
        val t1 = (i + 1).toFloat() / chars.size
        char to listOf(lerp(tl, tr, t0), lerp(tl, tr, t1), lerp(bl, br, t1), lerp(bl, br, t0))
    }
}

/**
 * ML Kit sometimes stretches a word's first or last character box over something next to
 * it, like a printed table line ("| 5206..." gives a "5" box reaching the line). The text
 * is still right, but the highlight and handles end up too far out. If an end character is
 * more than 1.5x the word's median character width, trim it from its outer side.
 * Needs 3+ characters for a meaningful median.
 *
 * Tried and rejected: a height-based limit (missed these cases), an uppercase exception
 * (worse), and scanning photo pixels for the ink edge (counted the line as part of the word).
 * Known flaw: may clip a little off a capital that starts a lowercase word ("Anges").
 */
private fun trimOversizedEnds(quads: List<Quad>): List<Quad> {
    if (quads.size < 3) return quads
    fun width(q: Quad) = (q[1] - q[0]).getDistance()
    val medianWidth = quads.map { width(it) }.sorted()[quads.size / 2]
    val maxWidth = medianWidth * 1.5f
    val result = quads.toMutableList()

    if (width(result.first()) > maxWidth) {
        // Keep the right edge, pull the left edge in.
        val (tl, tr, br, bl) = result.first()
        result[0] = listOf(tr - unit(tr - tl) * maxWidth, tr, br, br - unit(br - bl) * maxWidth)
    }
    if (width(result.last()) > maxWidth) {
        // Keep the left edge, pull the right edge in.
        val (tl, tr, br, bl) = result.last()
        result[result.lastIndex] = listOf(tl, tl + unit(tr - tl) * maxWidth, bl + unit(br - bl) * maxWidth, bl)
    }
    return result
}

/** Prefer ML Kit's tilted corner points; fall back to the upright box. */
private fun quadOf(points: Array<Point>?, box: android.graphics.Rect?): Quad? {
    if (points != null && points.size == 4) {
        return points.map { Offset(it.x.toFloat(), it.y.toFloat()) }
    }
    if (box == null) return null
    val l = box.left.toFloat()
    val t = box.top.toFloat()
    val r = box.right.toFloat()
    val b = box.bottom.toFloat()
    return listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))
}

/**
 * Grow a quad along its own axes so letters aren't touching the edge. Both paddings are
 * fractions of the quad's height, so they scale with text size.
 */
private fun padQuad(quad: Quad, alongFraction: Float, downFraction: Float): Quad =
    padQuad(quad, alongFraction, alongFraction, downFraction)

/** Like [padQuad], with separate padding at the start and the end along the text. */
private fun padQuad(quad: Quad, startFraction: Float, endFraction: Float, downFraction: Float): Quad {
    val (tl, tr, br, bl) = quad
    val height = (bl - tl).getDistance()
    val along = unit(tr - tl)
    val s = along * (height * startFraction)
    val e = along * (height * endFraction)
    val d = unit(bl - tl) * (height * downFraction)
    return listOf(tl - s - d, tr + e - d, br + e + d, bl - s + d)
}

internal fun unit(v: Offset): Offset {
    val length = v.getDistance()
    return if (length == 0f) Offset.Zero else v / length
}

private fun lerp(a: Offset, b: Offset, t: Float) = a + (b - a) * t

private fun boundsOf(quad: Quad) = Rect(
    quad.minOf { it.x },
    quad.minOf { it.y },
    quad.maxOf { it.x },
    quad.maxOf { it.y },
)

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
