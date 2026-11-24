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

/** A frozen photo plus every character ML Kit found in it, in reading order. */
class Scan(val bitmap: Bitmap, val glyphs: List<Glyph>, val lineQuads: List<Quad>)

fun buildScan(bitmap: Bitmap, text: Text): Scan {
    val glyphs = mutableListOf<Glyph>()
    val lineQuads = mutableListOf<Quad>()
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
                val chars = splitIntoChars(element)
                val isLastWordOfLine = elementIndex == elements.lastIndex
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

            // ML Kit's line corner points are sometimes short at the start on tilted text
            // (first letter left outside). The per-character points are accurate, so build
            // the line shape from those instead.
            val lineGlyphs = glyphs.subList(lineStart, glyphs.size)
            lineQuads += padQuad(enclosingQuad(lineGlyphs, lineQuad))
        }
    }
    return Scan(bitmap, glyphs, lineQuads)
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
        return symbols.mapIndexed { i, symbol -> symbol.text to symbolQuads[i]!! }
    }

    val (tl, tr, br, bl) = quadOf(element.cornerPoints, element.boundingBox)!!
    val chars = element.text.map { it.toString() }
    return chars.mapIndexed { i, char ->
        val t0 = i.toFloat() / chars.size
        val t1 = (i + 1).toFloat() / chars.size
        char to listOf(lerp(tl, tr, t0), lerp(tl, tr, t1), lerp(bl, br, t1), lerp(bl, br, t0))
    }
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

/** Grow a quad along its own axes by a fraction of its height, so letters aren't touching the edge. */
private fun padQuad(quad: Quad, fraction: Float = 0.2f): Quad {
    val (tl, tr, br, bl) = quad
    val along = unit(tr - tl)
    val down = unit(bl - tl)
    val pad = (bl - tl).getDistance() * fraction
    val a = along * pad
    val d = down * pad
    return listOf(tl - a - d, tr + a - d, br + a + d, bl - a + d)
}

private fun unit(v: Offset): Offset {
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
