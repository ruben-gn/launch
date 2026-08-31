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
private const val INPUT_COLOR = 0xFF1B1B24.toInt()

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

fun hdmiTiles(context: Context): List<Tile> {
    val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        ?: return emptyList()
    val inputs = runCatching { manager.tvInputList }.getOrNull() ?: return emptyList()
    return inputs
        .filter { it.type == TvInputInfo.TYPE_HDMI }
        .sortedBy { it.id }
        .map { info ->
            val state = runCatching { manager.getInputState(info.id) }
                .getOrDefault(TvInputManager.INPUT_STATE_DISCONNECTED)
            Tile(
                label = info.loadLabel(context)?.toString().orEmpty().ifBlank { info.id.substringAfterLast('/') },
                art = TileArt.Solid(INPUT_COLOR),
                color = INPUT_COLOR,
                icon = null,
                lightCorner = false,
                dim = state != TvInputManager.INPUT_STATE_CONNECTED,
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
