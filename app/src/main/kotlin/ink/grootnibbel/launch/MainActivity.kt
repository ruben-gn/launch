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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

private const val COLUMNS = 4

/**
 * The Philips "Bronnen" drawer, reached by pressing SOURCE a second time. It is AOSP's standard
 * action, which `org.droidtv.channels/.sources.SourcesDrawerActivity` answers, and it is the only
 * route to the tuner, USB and recordings — things a passthrough input URI cannot express.
 */
private const val NATIVE_SOURCES = "com.android.tv.action.VIEW_INPUTS"

/**
 * Card face: white at 10%, not an opaque colour. Over the scrim the difference is small, because the
 * scrim has already taken the light out of whatever sits behind the card — but opaque `0xFF1B1B24`
 * was *darker* than the dimmed wall in places and read as four holes punched in it, where a
 * translucent face reads as four panels lifted off it. It also picks up the ambient glow, so the
 * cards shift with the background instead of sitting on top of it as a fixed grey.
 */
private const val SOURCE_COLOR = 0x1AFFFFFF

/** How far the wall goes down behind the sources row. */
private const val SCRIM = 0.55f
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

    /**
     * Stops drawing, leaving `previous` where the ring last was so the caller can damage that area.
     * Needed because the ring belongs to the grid, not to a tile: when focus leaves the grid
     * entirely for the sources row, nothing else would ever take it off the screen.
     */
    fun hide() {
        previous.set(rect)
        shown = false
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
     * needs it now: `APPS` is a hardcoded list, so the only way the tiles change is an edit and a
     * reinstall — which restarts the process anyway — and the sources row no longer reads connection
     * state at all (see `hdmiSources`), so there is nothing about it left to keep current.
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
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT) {
            // Second press escalates to the full Philips list rather than closing; Back closes.
            if (sourcesShown) launch("Bronnen", Intent(NATIVE_SOURCES)) else showSources()
            return true
        }
        val index = when (keyCode) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_1
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (sourcesShown) return true
        val tile = tiles.getOrNull(index) ?: return true
        grid.getChildAt(index)?.requestFocus()
        launch(tile.label, tile.launch)
        return true
    }

    /** Back closes the sources row; as the home activity there is nothing else for it to do. */
    override fun onBackPressed() {
        if (sourcesShown) hideSources() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(unpause)
        ambient.paused = false
    }

    private val ambient = AmbientBackground()

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout

    private fun buildRoot(): View {
        root = FrameLayout(this).apply {
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
        // Added here, before the sources row exists, purely for z-order: the row is added to the
        // root on first SOURCE press and so lands above this, which is the whole point of it.
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            // Full-bleed, same as the sky: the overscan padding is there for the grid, and a scrim
            // that stopped at it would leave a bright 48 dp frame around a dimmed screen.
            setMargins(-dp(48), -dp(27), -dp(48), -dp(27))
        })
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
    /**
     * Black over the whole screen while the sources row is open.
     *
     * Plain alpha on the grid and the clock would have been cheaper — no full-screen blend — but it
     * would have left the ambient glow at full strength behind a dimmed wall, and "dim the rest of
     * the screen" means the background too. It is `GONE` rather than transparent when idle so it
     * costs nothing at all on the frames that matter, which are all the other ones.
     */
    private val scrim: View by lazy {
        View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
        }
    }

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
        view.setOnClickListener { launch(tile.label, tile.launch) }
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

    /**
     * Holds the ambient glow still for the length of a focus move.
     *
     * The sky is a full-screen hardware layer: when the ambient invalidates, the whole 1920x1080
     * buffer is re-rendered from three shaders, and that one frame blows the 20 ms budget. It fires
     * 6 times a second, so a 200 ms move collides with about 1.2 of them — one dropped frame out of
     * ten. Holding it still costs nothing visible: the glow is a pure function of the wall clock, so
     * it picks up where it would have been.
     *
     * Every animated focus move calls this, in the grid and in the sources row alike.
     */
    private fun holdAmbient() {
        ambient.paused = true
        handler.removeCallbacks(unpause)
        handler.postDelayed(unpause, MOVE_MS + 40)
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

        holdAmbient()

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

    private fun launch(label: String, intent: Intent) {
        try {
            startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // HDMI passthrough can fail with SecurityException as well as ActivityNotFound; surface
            // whichever it is rather than swallowing it, so a dead tile is diagnosable from the sofa.
            Toast.makeText(this, "$label: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }


    /**
     * The sources row, which the remote's SOURCE key reveals in the empty space under the grid.
     *
     * **The key had to be caught here because nothing else on this set catches it.** Scancode 610 on
     * the TPV remotes maps to KEYCODE_TV_INPUT, and it is not a global key — no GLOBAL_BUTTON
     * broadcast is sent, it is simply delivered to whatever is in the foreground. Philips relied on
     * their own launcher to handle it, so once this app became home the key went nowhere at all.
     *
     * It is four cards on the grid's own column spec — same width, same 8 dp margins — so the row
     * lands exactly under the four columns and reads as one more row of the same wall. That is why
     * there is no fifth card for the native drawer: a fifth would break the column rhythm for
     * something wanted once in a while, so it sits on a second press of SOURCE instead.
     *
     * Built on first use and kept, like the grid: rebuilding it would cost the input enumeration on
     * every press, and inputs do not come and go.
     */
    private var sourcesRow: ViewGroup? = null
    private var sourcesRing: RingLayer? = null
    private var sourcesShown = false

    /** Where focus was in the grid when the row opened, so closing puts it back where it was. */
    private var gridFocus: View? = null

    private fun showSources() {
        val row = sourcesRow ?: buildSourcesRow()?.also {
            sourcesRow = it
            root.addView(
                it,
                FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
                    .apply { bottomMargin = dp(24) },
            )
        } ?: run {
            Toast.makeText(this, "No HDMI inputs", Toast.LENGTH_LONG).show()
            return
        }

        gridFocus = grid.focusedChild
        // The ring belongs to the grid, so nothing would take it off screen once focus leaves for
        // the row — and two rings at once is the one thing this focus treatment must never show.
        ring.hide()
        ring.damageInto(dirty)
        grid.invalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
        // Without this a D-pad Up out of the row would land in the grid with the row still open.
        grid.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        sourcesShown = true
        holdAmbient()
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(SCRIM).setDuration(MOVE_MS).setInterpolator(MOVE_CURVE).start()
        row.visibility = View.VISIBLE
        row.alpha = 0f
        row.translationY = dp(24).toFloat()
        row.animate().alpha(1f).translationY(0f)
            .setDuration(MOVE_MS).setInterpolator(MOVE_CURVE).start()
        row.post { row.getChildAt(0)?.requestFocus() }
    }

    private fun hideSources() {
        val row = sourcesRow ?: return
        sourcesShown = false
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        // Focus first, then animate: the row is still on screen and still focusable while it fades.
        (gridFocus ?: grid.getChildAt(0))?.requestFocus()
        holdAmbient()
        scrim.animate().alpha(0f).setDuration(MOVE_MS).setInterpolator(MOVE_CURVE)
            .withEndAction { scrim.visibility = View.GONE }
            .start()
        row.animate().alpha(0f).translationY(dp(24).toFloat())
            .setDuration(MOVE_MS).setInterpolator(MOVE_CURVE)
            .withEndAction { row.visibility = View.GONE }
            .start()
    }

    private fun buildSourcesRow(): ViewGroup? {
        val sources = hdmiSources(this)
        if (sources.isEmpty()) return null
        val ring = RingLayer(radius = dp(14).toFloat(), width = dp(3).toFloat())
        sourcesRing = ring
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            foreground = ring
        }
        sources.forEach { source ->
            val params = LinearLayout.LayoutParams(0, dp(64), 1f)
            params.setMargins(dp(8), dp(8), dp(8), dp(8))
            row.addView(sourceCard(source), params)
        }
        return row
    }

    /**
     * Typographic, because there is nothing else to draw with: `loadIcon` returns null for every
     * input on this set, and an invented connector glyph would be the only picture on screen that
     * nobody shipped.
     *
     * Two lines where you named the input — "KPN" over "HDMI 3" — and a title over a blank second
     * line where you did not, because then the port is the only name it has and a subtitle repeating
     * it is noise. The blank line is kept rather than dropped so every title shares one baseline.
     * The corner is 14 dp rather than the tiles' 18: the card is half their height, and a radius
     * that reads as a soft corner on a 112 dp tile reads as a lozenge on a 64 dp one.
     */
    private fun sourceCard(source: Source): View {
        val card = FrameLayout(this).apply {
            isFocusable = true
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(SOURCE_COLOR)
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lines.addView(
            TextView(this).apply {
                text = source.title
                setTextColor(0xF2FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        // Added even when it is blank, and that is what aligns the row: an unnamed port has no
        // second line, so without a placeholder its title centres between its neighbours' two lines
        // and no two titles sit on the same baseline. An empty TextView still measures one line.
        lines.addView(
            TextView(this).apply {
                text = source.port
                setTextColor(0x8CFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
            },
        )
        card.addView(lines, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))

        card.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) moveSourcesRing(v)
            v.animate()
                .scaleX(if (hasFocus) SCALE else 1f)
                .scaleY(if (hasFocus) SCALE else 1f)
                .setDuration(MOVE_MS)
                .setInterpolator(MOVE_CURVE)
                .start()
        }
        card.setOnClickListener { launch(source.title, source.launch) }
        return card
    }

    /** The grid's ring travels behind a flag; this one only ever snaps, so it needs no animator. */
    private fun moveSourcesRing(view: View) {
        val ring = sourcesRing ?: return
        val row = sourcesRow ?: return
        if (view.width == 0 || view.height == 0) {
            view.post { if (view.isFocused) moveSourcesRing(view) }
            return
        }
        val grow = (SCALE - 1f) / 2f
        val dx = view.width * grow
        val dy = view.height * grow
        ring.moveTo(view.left - dx, view.top - dy, view.right + dx, view.bottom + dy)
        ring.damageInto(dirty)
        row.invalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
        holdAmbient()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
