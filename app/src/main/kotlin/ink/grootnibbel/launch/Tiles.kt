package ink.grootnibbel.launch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** What paints a tile's face. Swapping solid colour for banner artwork later replaces only this. */
sealed interface TileArt {
    data class Solid(val color: Int) : TileArt
    data class Art(val drawable: Drawable) : TileArt
}

data class Tile(
    val label: String,
    val art: TileArt,
    /** Sits behind the art, so a banner with transparent edges has something to land on. */
    val color: Int,
    val icon: Drawable?,
    /**
     * Whether the corner the shortcut digit sits in is light. Netflix and YouTube ship near-white
     * banners and the other six are near-black, so no single digit colour serves the whole wall.
     */
    val lightCorner: Boolean,
    val dim: Boolean,
    val launch: Intent,
)

/** Grid order, read left-to-right then down. */
private val APPS = listOf(
    "com.netflix.ninja",
    "com.disney.disneyplus",
    "nl.nlziet",
    "com.google.android.youtube.tv",
    "com.amazon.amazonvideo.livingroom",
    "com.wbd.hbomax",
    "nl.uitzendinggemist",
    "com.spotify.tv.android",
)

private const val FALLBACK_COLOR = 0xFF2A2A32.toInt()

fun appTiles(context: Context): List<Tile> {
    val pm = context.packageManager
    return APPS.mapNotNull { pkg ->
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: return@mapNotNull null
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
        val icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
        val banner = intent.component?.let { runCatching { pm.getActivityBanner(it) }.getOrNull() }
            ?: runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
        Tile(
            label = pm.getApplicationLabel(info).toString(),
            art = if (banner == null) TileArt.Solid(FALLBACK_COLOR) else TileArt.Art(banner),
            color = FALLBACK_COLOR,
            icon = icon,
            lightCorner = banner != null && hasLightCorner(banner),
            dim = false,
            launch = intent,
        )
    }
}

/**
 * One of the TV's own inputs, as the sources row draws it.
 *
 * Not a `Tile`: an input has no banner artwork to render, and it carries a second line that a tile
 * has nowhere to put. `title` is what you named the thing, `port` is where it is plugged in.
 */
data class Source(val title: String, val port: String, val launch: Intent)

/**
 * The four HDMI ports, in port order.
 *
 * `loadCustomLabel` is the whole reason this reads well: it returns the name *you* gave the input in
 * the Philips setup — "KPN", "Digitale ontvanger" — and `loadLabel` returns the port it hangs off.
 * Where you never named one, the port is the only name there is, so it becomes the title and the
 * second line is dropped.
 *
 * **Connection state is deliberately not read.** It looks like the obvious way to dim a dead port,
 * and the dormant version of this function did exactly that, but the signal does not mean what it
 * says on this set: measured 2026-09-04, HDMI 1 reports CONNECTED while HDMI 2, 3 and 4 all report
 * CONNECTED_STANDBY — including the two ports Philips' own hotplug listener says are empty. So the
 * state cannot separate "a box in standby" from "nothing plugged in", and dimming on it would have
 * greyed out the KPN receiver, which is one of the two inputs actually in use.
 */
fun hdmiSources(context: Context): List<Source> {
    val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        ?: return emptyList()
    val inputs = runCatching { manager.tvInputList }.getOrNull() ?: return emptyList()
    return inputs
        .filter { it.type == TvInputInfo.TYPE_HDMI }
        .sortedBy { it.id }
        .map { info ->
            val port = runCatching { info.loadLabel(context) }.getOrNull()?.toString().orEmpty()
                .ifBlank { info.id.substringAfterLast('/') }
            val custom = runCatching { info.loadCustomLabel(context) }.getOrNull()?.toString()
            Source(
                title = custom?.takeIf { it.isNotBlank() } ?: port,
                port = if (custom.isNullOrBlank()) "" else port,
                launch = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id)),
            )
        }
}

/**
 * Mean luminance of the patch of banner the shortcut digit is drawn over.
 *
 * The banner is rasterised once at 32x18 — its own 16:9 shape, small enough that this costs
 * nothing and blurry enough that a single stray pixel cannot swing the answer. The sampled cells
 * cover roughly 4-17% across and 7-30% down, which is where the digit lands.
 */
private fun hasLightCorner(drawable: Drawable): Boolean {
    val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, 32, 18)
    drawable.draw(Canvas(bitmap))

    var total = 0.0
    var count = 0
    for (y in 1..5) {
        for (x in 1..5) {
            val pixel = bitmap.getPixel(x, y)
            total += 0.2126 * Color.red(pixel) +
                0.7152 * Color.green(pixel) +
                0.0722 * Color.blue(pixel)
            count++
        }
    }
    bitmap.recycle()
    return total / count > 140
}

