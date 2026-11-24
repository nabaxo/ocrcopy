package dev.nabaxo.ocrcopy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Gooey" fill of [quads]: outer corners rounded, and shapes closer than about [radius]
 * melted together with smooth curves between them (metaball look).
 *
 * How: draw the shapes as a mask, blur it, then cut back to a sharp edge at 50%.
 * Straight edges stay where they were; corners round off; small gaps fill in.
 *
 * Returns a bitmap covering the whole image at reduced size. Stretch it over the image
 * when drawing (bilinear filtering keeps edges smooth). Pixels inside the shapes are
 * [inside], outside are [outside] (ARGB ints).
 */
fun gooeyLayer(
    imageWidth: Int,
    imageHeight: Int,
    quads: List<Quad>,
    radius: Float,
    inside: Int,
    outside: Int,
): Bitmap {
    // Work at up to 1/4 size, but keep the blur at least ~3 mask pixels so curves stay smooth.
    val downscale = (radius / 3f).toInt().coerceIn(1, 4)
    val w = ceil(imageWidth / downscale.toFloat()).toInt()
    val h = ceil(imageHeight / downscale.toFloat()).toInt()

    val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(layer)
    canvas.scale(1f / downscale, 1f / downscale)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val path = Path()
    for (quad in quads) {
        path.moveTo(quad[0].x, quad[0].y)
        for (i in 1..3) path.lineTo(quad[i].x, quad[i].y)
        path.close()
    }
    canvas.drawPath(path, paint)

    val pixels = IntArray(w * h)
    layer.getPixels(pixels, 0, w, 0, 0, w, h)
    val coverage = FloatArray(w * h) { (pixels[it] ushr 24) / 255f }

    // Three box blurs in a row approximate a Gaussian with sigma ≈ box radius.
    val boxRadius = max(1, (radius / downscale).roundToInt())
    repeat(3) {
        boxBlurRows(coverage, w, h, boxRadius)
        boxBlurColumns(coverage, w, h, boxRadius)
    }

    for (i in coverage.indices) {
        // Narrow smoothstep around 50% = sharp edge with about one pixel of anti-aliasing.
        val t = smoothstep(0.45f, 0.55f, coverage[i])
        pixels[i] = lerpArgb(outside, inside, t)
    }
    layer.setPixels(pixels, 0, w, 0, 0, w, h)
    return layer
}

private fun boxBlurRows(values: FloatArray, w: Int, h: Int, r: Int) {
    val row = FloatArray(w)
    val norm = 1f / (2 * r + 1)
    for (y in 0 until h) {
        val base = y * w
        fun at(x: Int) = if (x in 0 until w) values[base + x] else 0f
        var sum = 0f
        for (x in -r..r) sum += at(x)
        for (x in 0 until w) {
            row[x] = sum * norm
            sum += at(x + r + 1) - at(x - r)
        }
        System.arraycopy(row, 0, values, base, w)
    }
}

private fun boxBlurColumns(values: FloatArray, w: Int, h: Int, r: Int) {
    val column = FloatArray(h)
    val norm = 1f / (2 * r + 1)
    for (x in 0 until w) {
        fun at(y: Int) = if (y in 0 until h) values[y * w + x] else 0f
        var sum = 0f
        for (y in -r..r) sum += at(y)
        for (y in 0 until h) {
            column[y] = sum * norm
            sum += at(y + r + 1) - at(y - r)
        }
        for (y in 0 until h) values[y * w + x] = column[y]
    }
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    fun channel(shift: Int): Int {
        val a = (from ushr shift) and 0xFF
        val b = (to ushr shift) and 0xFF
        return (a + (b - a) * t).roundToInt() shl shift
    }
    return channel(24) or channel(16) or channel(8) or channel(0)
}
