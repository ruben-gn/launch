package ink.grootnibbel.launch

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 2.0 * Math.PI

/**
 * 6 fps. The rate is set by how much any one pixel may change between frames — under about one
 * 8-bit level and the stepping is invisible. Recolouring dominates that budget, because it moves
 * every pixel at once while sliding a 918 px falloff by a few pixels barely moves any of them: at
 * this speed the worst case is ~0.35 levels from colour against ~0.20 from position, so ~0.55 in
 * total. Focus animations are unaffected — they drive their own invalidations at display rate.
 */
private const val FRAME_MS = 166L

/** Wall-clock seconds are multiplied by this, so the periods below are in "simulated" seconds. */
private const val SPEED = 24.0

/** How far the centre wanders from the middle, per axis. Small enough that it never drifts off. */
private const val RANGE = 0.30f

/** Radius as a fraction of screen height, and how much it breathes. */
private const val RADIUS = 0.85f
private const val RADIUS_PULSE = 0.22f

private const val BASE_TL = 0xFF16161F.toInt()
private const val BASE_BR = 0xFF0A0A0E.toInt()

/** Teal, indigo, plum. The glow ping-pongs along this arc, never past its ends. */
private val PALETTE = intArrayOf(0x0C2429, 0x1A1840, 0x331A34)

// Primes, so the four motions share no factor and the whole thing has no loop point to notice.
private const val PERIOD_X = 5503.0
private const val PERIOD_Y = 4423.0
private const val PERIOD_R = 887.0
private const val PERIOD_C = 1789.0

/**
 * Alpha down the radius, sampled from (1 - t^2)^2 — the same soft falloff the old per-pixel field
 * used, handed to Skia as gradient stops instead of being computed a pixel at a time.
 */
private val STOPS = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
private val FALLOFF = floatArrayOf(1f, 0.9216f, 0.7056f, 0.4096f, 0.1296f, 0f)

/** The gradient is authored at this radius and scaled by matrix, so the pulse costs no rebuild. */
private const val REF_RADIUS = 512f

/**
 * One soft glow wandering the panel over the old gradient, cycling slowly through teal, indigo and
 * plum. Nothing here is linked to what has focus; it simply drifts, so the screen is never quite
 * the same twice.
 *
 * Three shaders, no per-pixel work: Skia draws the base ramp, the glow and the grain as three
 * quads. Position and the radius pulse ride on the glow's local matrix, so the only thing that
 * forces a shader rebuild is the colour, which crosses an 8-bit step about twice a second.
 *
 * Phase comes from the wall clock, not from when the view was built — MainActivity rebuilds its
 * whole hierarchy on every return to Home, and a phase starting at zero each time would mean an
 * identical background every time you walked in.
 */
class AmbientBackground : Drawable() {

    private val basePaint = Paint()
    private val glowPaint = Paint()
    private val matrix = Matrix()
    private val glowColors = IntArray(FALLOFF.size)
    private var radiusBase = 0f
    private var lastRgb = -1

    // The base ramp spans about six 8-bit levels across the whole screen, which bands badly on a
    // panel this size. A tile of 0-3/255 white breaks the quantisation up.
    private val noisePaint = Paint().apply {
        val n = 64
        val rnd = Random(7)
        val bitmap = Bitmap.createBitmap(
            IntArray(n * n) { (rnd.nextInt(0, 4) shl 24) or 0xFFFFFF }, n, n, Bitmap.Config.ARGB_8888
        )
        shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /**
     * Held true while a focus move is in flight. The glow is a pure function of the wall clock, so
     * frames skipped here are not frames owed later — it resumes wherever it would have been anyway.
     */
    var paused = false

    private val tick = Runnable { if (paused) rearm() else invalidateSelf() }

    private fun rearm() {
        unscheduleSelf(tick)
        scheduleSelf(tick, SystemClock.uptimeMillis() + FRAME_MS)
    }

    override fun onBoundsChange(bounds: Rect) {
        basePaint.shader = LinearGradient(
            0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(),
            BASE_TL, BASE_BR, Shader.TileMode.CLAMP,
        )
        radiusBase = RADIUS * bounds.height()
        lastRgb = -1
    }

    override fun draw(canvas: Canvas) {
        val t = System.currentTimeMillis() / 1000.0 * SPEED

        // Ping-pong along the palette rather than cycling it, so the arc has ends.
        val p = ((sin(TAU * t / PERIOD_C) * 0.5 + 0.5) * (PALETTE.size - 1)).toFloat()
        val lo = p.toInt().coerceAtMost(PALETTE.size - 2)
        val rgb = mix(PALETTE[lo], PALETTE[lo + 1], p - lo)
        if (rgb != lastRgb) {
            for (i in FALLOFF.indices) {
                glowColors[i] = ((FALLOFF[i] * 255).toInt() shl 24) or rgb
            }
            glowPaint.shader = RadialGradient(
                0f, 0f, REF_RADIUS, glowColors, STOPS, Shader.TileMode.CLAMP,
            )
            lastRgb = rgb
        }

        val r = radiusBase * (1f + RADIUS_PULSE * sin(TAU * t / PERIOD_R).toFloat())
        val cx = (0.5f + RANGE * drift(t, PERIOD_X, 0.0)) * bounds.width()
        val cy = (0.5f + RANGE * drift(t, PERIOD_Y, 1.7)) * bounds.height()
        matrix.setScale(r / REF_RADIUS, r / REF_RADIUS)
        matrix.postTranslate(cx, cy)
        glowPaint.shader.setLocalMatrix(matrix)

        canvas.drawRect(bounds, basePaint)
        canvas.drawRect(bounds, glowPaint)
        canvas.drawRect(bounds, noisePaint)

        // Re-arm from draw, not from setVisible: View only calls setVisible when the value
        // *changes*, and Drawable starts out visible, so an override there never fires. Driving it
        // from draw also stops the loop for free — once the view stops drawing us, whether it was
        // detached or the activity went away, invalidateSelf has nothing to invalidate.
        rearm()
    }

    /**
     * Three octaves of sine: a slow sweep, a wobble over it, and a small fast jitter, on periods
     * with no common factor. One sine per axis traces a clean Lissajous figure you start to
     * anticipate; this wanders and crosses itself instead.
     *
     * The amplitudes fall off faster than the periods do (0.72 / 0.18 / 0.06 against 1 / 2.63 /
     * 5.71) so the added detail changes the *shape* of the path without speeding the glow up —
     * each octave's peak velocity scales as amplitude over period.
     */
    private fun drift(t: Double, period: Double, phase: Double): Float =
        (0.72 * sin(TAU * t / period + phase) +
            0.18 * sin(TAU * t / (period / 2.63) + phase * 2.1 + 1.7) +
            0.06 * sin(TAU * t / (period / 5.71) + phase * 3.7 + 4.2)).toFloat()

    private fun mix(a: Int, b: Int, f: Float): Int {
        val r = lerp(a shr 16 and 0xFF, b shr 16 and 0xFF, f)
        val g = lerp(a shr 8 and 0xFF, b shr 8 and 0xFF, f)
        return (r shl 16) or (g shl 8) or lerp(a and 0xFF, b and 0xFF, f)
    }

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Required by Drawable", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity() = PixelFormat.OPAQUE
}
