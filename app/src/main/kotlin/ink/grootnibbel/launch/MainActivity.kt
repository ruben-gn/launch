package ink.grootnibbel.launch

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

private const val COLUMNS = 4
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Forces 16:9 so banner artwork renders at its native shape rather than being stretched. */
private class BannerFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY),
        )
    }
}

/** How much the focused tile grows. The ring hugs the grown tile, so it needs the number too. */
private const val SCALE = 1.04f

/**
 * Whether the ring slides to the newly focused tile or is simply placed on it. **Off, and that is a
 * taste call rather than a cost one** — settled 2026-08-30 against a live three-way on the panel,
 * once the frame drops that had been unfairly handicapping it were fixed (see the sky pause in
 * `moveRing`). Travel measured 0.98% janky, which is the same as snap.
 *
 * The case against it on this grid: every D-pad press moves one tile to an *adjacent* neighbour, so
 * the destination is never ambiguous and there is nothing for a sliding ring to disambiguate. Focus
 * is animated either way — the destination tile still scales to SCALE over MOVE_MS on MOVE_CURVE, so
 * the usual "animate focus so the eye can follow it" argument is already satisfied without the ring
 * moving at all. And on a held D-pad repeat, presses come faster than MOVE_MS, so the ring never
 * arrives and trails a tile that has already grown.
 *
 * Kept as a flag, not deleted: Ruben's verdict was "snap for now, but note that travel is an
 * option". Flipping this to true is the entire change.
 */
private const val TRAVEL = false

/** Shared by the ring and the tile's own scale, so a focus move lands as one event. */
private const val MOVE_MS = 200L
private val MOVE_CURVE = PathInterpolator(0.4f, 0f, 0.2f, 1f)

/**
 * The white ring, and there is only ever one of it.
 *
 * It hangs on the grid's *foreground* rather than on any tile, which is what lets it travel: it is a
 * single object at an arbitrary rect, not a decoration that belongs to whichever view has focus. On
 * the way it passes over the banners it crosses, which is the right occlusion for something moving
 * in front of the wall.
 *
 * Two strokes: a dark one hugging the banner, a white one outside it. The dark one earns its place
 * only on Netflix and YouTube, whose banners are near-white and would otherwise swallow the ring,
 * but it costs nothing on the other six. Both sit entirely outside the tile — the ring this replaces
 * was a `foreground` on the tile itself, so `clipToOutline` trimmed it inwards and it covered 5.5 dp
 * of every banner on every edge.
 */
private class RingLayer(private val radius: Float, private val width: Float) : Drawable() {

    private val white = paint(0xEBFFFFFF.toInt())
    private val dark = paint(0xC8000000.toInt())
    private val rect = RectF()
    private val previous = RectF()
    private var shown = false

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
    }

    /** Where the ring is *now*, which is not the last target if a travel is still running. */
    fun readInto(out: RectF) = out.set(rect)

    /**
     * Deliberately does not invalidate. `invalidateSelf` on a view's foreground damages the whole
     * view, and the grid is 1728x514 — every frame of a travel would repaint the ambient background
     * under all of it. The caller damages the union of where the ring was and where it now is.
     */
    fun moveTo(left: Float, top: Float, right: Float, bottom: Float) {
        previous.set(if (shown) rect else RectF(left, top, right, bottom))
        rect.set(left, top, right, bottom)
        shown = true
    }

    /** The union of the last two positions, grown by how far the outer stroke reaches. */
    fun damageInto(out: Rect) {
        val pad = width * 2f + 2f
        out.set(
            (minOf(previous.left, rect.left) - pad).toInt(),
            (minOf(previous.top, rect.top) - pad).toInt(),
            (maxOf(previous.right, rect.right) + pad).toInt() + 1,
            (maxOf(previous.bottom, rect.bottom) + pad).toInt() + 1,
        )
    }

    override fun draw(canvas: Canvas) {
        if (!shown) return
        stroke(canvas, width / 2f, dark)
        stroke(canvas, width * 1.5f, white)
    }

    /**
     * `out` is the distance from the banner edge to the centre of the stroke. The radius is outset
     * by the same amount, or the two curves diverge around the corner — that divergence was most of
     * what made the ring this replaces ugly.
     */
    private fun stroke(canvas: Canvas, out: Float, paint: Paint) {
        canvas.drawRoundRect(
            rect.left - out, rect.top - out, rect.right + out, rect.bottom + out,
            radius + out, radius + out, paint,
        )
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Required by Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class MainActivity : Activity() {

    /**
     * Built once, not per resume, and that is what makes focus survive a return home: the view that
     * had focus still has it, because the tree it lives in was never torn down. No saved index, no
     * SharedPreferences read on the critical path. A process kill lands you back on the first tile,
     * which is the right amount of memory for a launcher to have.
     *
     * It used to rebuild in `onResume` to keep HDMI live-state and app installs current. Neither
     * needs it now: the HDMI rail is dormant, and `APPS` is a hardcoded list, so the only way the
     * tiles change is an edit and a reinstall — which restarts the process anyway. Re-enabling
     * `hdmiTiles` means finding somewhere to refresh connection state again, and `onResume` is still
     * the obvious place — but only for that rail, not for the whole tree.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(buildRoot())
    }

    /**
     * The remote's numeric keypad opens a tile outright, counting the grid the way you read it. Both
     * ranges are handled because these sets emit KEY_1..9 *and* KEY_NUMERIC_1..9, and which of the
     * two arrives depends on the remote — this TV pairs with several.
     *
     * Focus follows the launch, so coming back leaves the cursor on what you just opened rather than
     * wherever it was before you reached for a number.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val index = when (keyCode) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_1
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_1
            else -> return super.onKeyDown(keyCode, event)
        }
        val tile = tiles.getOrNull(index) ?: return true
        grid.getChildAt(index)?.requestFocus()
        launch(tile)
        return true
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(unpause)
        ambient.paused = false
    }

    private val ambient = AmbientBackground()

    private val handler = Handler(Looper.getMainLooper())

    private fun buildRoot(): View {
        val root = FrameLayout(this).apply {
            // ~5% overscan margin; TVs crop the edges of the panel.
            setPadding(dp(48), dp(27), dp(48), dp(27))
            clipChildren = false
            clipToPadding = false
        }

        root.addView(sky(), FrameLayout.LayoutParams(MATCH, MATCH).apply {
            // Full-bleed: cancel the root's overscan padding, which is there for the grid, not for
            // the background.
            setMargins(-dp(48), -dp(27), -dp(48), -dp(27))
        })

        val grid = buildGrid(appTiles(this))
        // The grid block floats in the middle; the empty space above and below is the gradient.
        root.addView(grid, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        root.addView(clockView(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        grid.post { grid.getChildAt(0)?.requestFocus() }
        return root
    }

    /**
     * The ambient background gets its own view and its own hardware layer, and that is a frame-rate
     * fix rather than tidiness.
     *
     * As the root's background it was three full-screen shader fills recorded into the root's
     * display list, so every frame that damaged anything re-blended all three across the damaged
     * region — 50 times a second during a focus move, for something that only changes 6 times a
     * second. Measured over 20 focus moves: 25% of frames janky, 90th percentile 32 ms against a
     * 20 ms budget, and the profiler blamed "slow issue draw commands". The same run with a flat
     * colour behind it was 0% janky at 8 ms.
     *
     * In its own layer it renders to an offscreen buffer only when it actually invalidates, and
     * every other frame composites it as a single opaque quad.
     */
    private fun sky(): View = View(this).apply {
        background = ambient
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Android's retired lockscreen clock face, still shipped on this set. No family name in
     * fonts.xml, hence the path. Its charset is 0-9, colon and space only — which is exactly the
     * clock's and the shortcut digits' whole alphabet, and nothing else can ever use it.
     */
    private val clockFace by lazy { android.graphics.Typeface.createFromFile("/system/fonts/AndroidClock.ttf") }

    /**
     * TextClock ticks itself off ACTION_TIME_TICK, so no handler to own or tear down.
     * format12Hour = null forces the 24h format regardless of the TV's own 12/24 setting.
     *
     * The typeface's charset is 0-9, colon and space only, so the format above can never grow a
     * date or a weekday without changing the typeface too.
     */
    private fun clockView(): View = TextClock(this).apply {
        format12Hour = null
        format24Hour = "HH:mm"
        timeZone = "Europe/Amsterdam"
        typeface = clockFace
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
        letterSpacing = 0.05f
    }

    private lateinit var ring: RingLayer
    private lateinit var grid: ViewGroup

    /** In grid order, so a tile's index is the number you press to open it. */
    private lateinit var tiles: List<Tile>

    private fun buildGrid(apps: List<Tile>): ViewGroup {
        tiles = apps
        ring = RingLayer(radius = dp(18).toFloat(), width = dp(3).toFloat())
        grid = GridLayout(this).apply {
            columnCount = COLUMNS
            rowCount = (apps.size + COLUMNS - 1) / COLUMNS
            clipChildren = false
            foreground = ring
        }
        apps.forEachIndexed { index, tile ->
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / COLUMNS),
                GridLayout.spec(index % COLUMNS, 1f),
            ).apply {
                width = 0
                height = WRAP
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
            grid.addView(tileView(tile, index + 1), params)
        }
        return grid
    }

    private fun tileView(tile: Tile, number: Int): View {
        // 18dp, not less: NPO Start's banner bakes in its own ~15dp rounded corner with a light
        // fill outside the curve, which shows as pale crescents unless our clip is at least as round.
        val radius = dp(18).toFloat()
        val face = GradientDrawable().apply {
            cornerRadius = radius
            setColor(tile.color)
        }
        // Two views, not one: the inner holds the artwork and does the rounding, the outer is what
        // the grid lays out and scales. Anything drawn by a view with clipToOutline is trimmed to
        // that outline, which is why the ring hangs off the grid rather than off a tile at all.
        val art = FrameLayout(this).apply {
            background = face
            clipToOutline = true
        }

        val view = BannerFrame(this).apply {
            isFocusable = true
            clipChildren = false
            alpha = if (tile.dim) 0.4f else 1f
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        when (val artwork = tile.art) {
            is TileArt.Art -> art.addView(
                ImageView(this).apply {
                    setImageDrawable(artwork.drawable)
                    scaleType = ImageView.ScaleType.FIT_XY
                },
                FrameLayout.LayoutParams(MATCH, MATCH),
            )

            is TileArt.Solid -> {
                art.setPadding(dp(14), dp(14), dp(14), dp(12))
                tile.icon?.let { icon ->
                    art.addView(
                        ImageView(this).apply { setImageDrawable(icon) },
                        FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.START),
                    )
                }
                art.addView(
                    TextView(this).apply {
                        text = tile.label
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        maxLines = 2
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    },
                    FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM or Gravity.START),
                )
            }
        }

        val (digit, digitParams) = shortcut(number, tile.lightCorner)
        view.addView(digit, digitParams)

        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) moveRing(v)
            art.elevation = if (hasFocus) dp(8).toFloat() else 0f
            v.animate()
                .scaleX(if (hasFocus) SCALE else 1f)
                .scaleY(if (hasFocus) SCALE else 1f)
                .setDuration(MOVE_MS)
                .setInterpolator(MOVE_CURVE)
                .start()
        }
        view.setOnClickListener { launch(tile) }
        return view
    }

    private val unpause = Runnable { ambient.paused = false }

    /**
     * The number key that opens this tile, drawn bare in the top-left corner — no chip, no circle.
     * Chosen off a live eight-way on the panel against chips, circles and badges placed outside the
     * tile entirely.
     *
     * The colour follows the banner underneath it, because nothing else can: Netflix and YouTube
     * are near-white and the other six near-black, so a fixed digit colour is invisible on half the
     * wall — a light chip on YouTube disappeared completely in that test. The shadow is the
     * opposite tone at low alpha, which keeps the digit legible where a banner is mid-grey and the
     * choice is closest to a coin toss.
     *
     * It is set in the clock's own face at 13sp, not bold at 17sp as it first shipped: bold was the
     * heaviest type on the wall after the banners, and the digits are the only other numbers on
     * screen, so they now rhyme with the clock instead of competing with it. Thin and small stays
     * crisp where thin and faded goes muddy on the mid-grey banners.
     */
    private fun shortcut(number: Int, lightCorner: Boolean): Pair<View, FrameLayout.LayoutParams> {
        val text = TextView(this).apply {
            text = number.toString()
            typeface = clockFace
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (lightCorner) 0xE616161F.toInt() else 0xF2FFFFFF.toInt())
            setShadowLayer(
                dp(3).toFloat(), 0f, dp(1).toFloat(),
                if (lightCorner) 0x33FFFFFF else 0x99000000.toInt(),
            )
            // The focused tile raises its artwork to 8dp and elevation reorders siblings, so
            // without this the digit would slide behind the banner on the tile you are looking at.
            // No outline provider, so the lift casts no shadow of its own.
            elevation = dp(12).toFloat()
            outlineProvider = null
        }
        val params = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        params.setMargins(dp(12), dp(6), 0, 0)
        return text to params
    }

    private val from = RectF()
    private val to = RectF()

    private val dirty = Rect()

    /**
     * **This panel is 50 Hz, not 60.** At 140 ms a travel was seven frames, and a hop between
     * columns is 432 px, so the ring moved 62 px per frame — the stepping the eye reads as low
     * frame rate. The pipeline was never the problem: measured at 5 ms median and 2.9% janky.
     *
     * 200 ms buys ten frames, and the curve spends them where they are worth most: fast out of the
     * old tile, then a long glide into the new one. The eye tracks the arrival, so the landing is
     * the part that has to be smooth, and the last few frames now move only a few px each.
     */
    private val travel = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = MOVE_MS
        interpolator = MOVE_CURVE
        addUpdateListener {
            val f = it.animatedValue as Float
            place(
                from.left + (to.left - from.left) * f,
                from.top + (to.top - from.top) * f,
                from.right + (to.right - from.right) * f,
                from.bottom + (to.bottom - from.bottom) * f,
            )
        }
    }

    private fun place(left: Float, top: Float, right: Float, bottom: Float) {
        ring.moveTo(left, top, right, bottom)
        ring.damageInto(dirty)
        grid.invalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
    }

    /** The ring hugs the *grown* tile, so the target is the layout rect outset by half the growth. */
    private fun moveRing(view: View) {
        // The framework grants initial focus itself, during its first traversal and before the
        // tile has been measured, so the first move read bounds of all zeros and placed the ring as
        // a degenerate rect at the grid's origin — a tiny white ring outside the first tile, which
        // then corrected itself on the first D-pad press. Wait for a size and run again.
        if (view.width == 0 || view.height == 0) {
            view.post { if (view.isFocused) moveRing(view) }
            return
        }

        // The sky is a full-screen hardware layer: when the ambient invalidates, the whole 1920x1080
        // buffer is re-rendered from three shaders, and that one frame blows the 20 ms budget. It
        // fires 6 times a second, so a 200 ms move collides with about 1.2 of them — one dropped
        // frame out of ten. Holding it still for the length of the move costs nothing visible: the
        // glow is a pure function of the wall clock, so it picks up where it would have been.
        ambient.paused = true
        handler.removeCallbacks(unpause)
        handler.postDelayed(unpause, MOVE_MS + 40)

        val grow = (SCALE - 1f) / 2f
        val dx = view.width * grow
        val dy = view.height * grow
        travel.cancel()
        // Read the ring's live position *before* setting the new target: `from` used to be assigned
        // straight after start(), which lands before the animator's first frame, so every travel
        // interpolated the destination to itself and looked exactly like a snap.
        ring.readInto(from)
        to.set(view.left - dx, view.top - dy, view.right + dx, view.bottom + dy)
        if (TRAVEL && !from.isEmpty) {
            travel.start()
        } else {
            place(to.left, to.top, to.right, to.bottom)
        }
    }

    private fun launch(tile: Tile) {
        try {
            startActivity(Intent(tile.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // HDMI passthrough can fail with SecurityException as well as ActivityNotFound; surface
            // whichever it is rather than swallowing it, so a dead tile is diagnosable from the sofa.
            Toast.makeText(this, "${tile.label}: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
