# launch

A native Kotlin launcher for the Philips living-room TV. Replaces FLauncher.

Eight hardcoded app tiles in a 4×2 grid, rendered as each app's own `android:banner` artwork at
native 16:9, over a dark gradient with one soft glow drifting slowly across it, and a 24-hour clock
in the top right. Zero dependencies — no Compose, no AndroidX, no leanback library, just `Activity`
and the framework's `GridLayout`.

| | |
|---|---|
| APK | 884 KB |
| Resident memory (as home) | ~21.5 MB PSS |
| Background animation | 2.9% of one core, 0% janky frames |
| FLauncher, for comparison | ~148 MB when it was home |

---

## The TV

| | |
|---|---|
| Model | Philips TPM171E ("SuperMega") |
| Android | 8.0.0, SDK 26, `osType MSAF_2018_O` |
| RAM | 1.9 GB |
| Screen | 1920×1080, density 320 → a **960×540 dp** layout canvas |
| ABI | `armeabi-v7a` only — 32-bit ARM, no arm64 |
| Input | `leanback_only`, no touchscreen — D-pad is the only way to drive it |
| LAN | `192.168.1.141` |

The dp canvas is the constraint that shapes everything. After a 5% overscan margin you have about
864×486 dp to work with, which is roughly a large phone in landscape.

---

## Reaching the TV from the VPS

**The VPS cannot reach the TV directly.** They are on different networks. Everything goes through
the Raspberry Pi, which sits on the same LAN as the TV:

```
VPS (hobby)  ──ssh──▶  Pi (rpi)  ──adb over LAN──▶  TV (192.168.1.141:5555)
```

So every TV command is an `ssh rpi` wrapping an `adb` call. `adb` is at `/usr/bin/adb` on the Pi.

### Connect

```sh
ssh rpi 'adb connect 192.168.1.141:5555 && adb devices'
```

Expect `192.168.1.141:5555   device`. The debugging key is already whitelisted on the TV and stays
whitelisted across reboots, so this is normally all you need.

### Run a command

```sh
ssh rpi 'adb -s 192.168.1.141:5555 shell <command>'
```

Note for zsh users: `A="adb -s 192.168.1.141:5555"; $A shell ...` **does not work** — zsh doesn't
word-split unquoted variables, so it looks for a command literally named `adb -s 192.168…`. Use a
shell variable for the address only: `D=192.168.1.141:5555; adb -s $D shell ...`.

### Screenshot (the visual iteration loop)

```sh
ssh rpi 'adb -s 192.168.1.141:5555 shell screencap -p > /tmp/shot.png'
scp rpi:/tmp/shot.png ./shot.png
```

**Always check the byte size.** A failed `screencap` writes an empty file rather than returning an
error, so a 0-byte `shot.png` means the TV wasn't reachable, not that the screen was black.

### When it won't connect

`No route to host`, plus an `INCOMPLETE` entry in `ip neigh show 192.168.1.141`, means **the TV is
powered off or in deep standby** — not that adb is broken. Turn the set on and reconnect. Check with:

```sh
ssh rpi 'ping -c2 -W2 192.168.1.141'
```

### If the debugging key is ever revoked

This is the painful one, so it is written down.

**The ADB authorization dialog never renders on this TV.** Enabling USB debugging opens port 5555
and `adbd` answers the handshake, but the prompt is never drawn and never takes focus, so
`adb connect` sits at `unauthorized` forever. Rebooting, revoking authorizations and toggling USB
debugging all fail to fix it.

The workaround that does work:

1. Pair with the Philips **JointSpace API on port 1926** (digest auth; the PIN *does* render on
   screen, which proves overlays are fine in general). The HMAC secret for the grant step is the
   well-known Philips one.
2. Start an `adb connect` and leave it pending.
3. While it is pending, POST to `activities/launch` with component
   `com.android.systemui/com.android.systemui.usb.UsbDebuggingActivity`.

The activity still doesn't draw, but it processes the pending request and whitelists the key. The
connection flips from `unauthorized` to `device`.

Helper script and pairing credentials are on the Pi at **`~/tv-debloat/js.py`** and
**`~/tv-debloat/pair_creds`**. Copies also exist at `/tmp/js.py` and `/tmp/pair_creds`, where they
originally lived — those will vanish on the next reboot of the Pi, so use the `~/tv-debloat/` ones.

---

## Build

The SDK lives at `~/Android/Sdk` on the VPS (cmdline-tools, `platforms;android-36`,
`build-tools;36.0.0`). JDK 21.

```sh
ANDROID_HOME=/home/ruben/Android/Sdk ./gradlew --no-daemon assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Takes ~30s.

Two things that will bite you:

- **AGP 9 has Kotlin support built in.** Applying `org.jetbrains.kotlin.android` is a hard build
  failure. Configure the toolchain with `kotlin { jvmToolchain(21) }` *inside* the `android {}` block.
- **`targetSdk` is deliberately 26**, matching the TV. Raising it pulls in package-visibility rules
  that would hide the eight apps behind a `<queries>` list in the manifest.

`debug.keystore` is committed so every build signs identically and installs over the last one.

## Deploy

```sh
scp app/build/outputs/apk/debug/app-debug.apk rpi:/tmp/launch.apk
ssh rpi 'adb -s 192.168.1.141:5555 install -r /tmp/launch.apk'
```

Whole loop is about 90 seconds from edit to pixels on the TV.

## Default home

This app is currently the default home.

```sh
# make it home
ssh rpi 'adb -s 192.168.1.141:5555 shell cmd package set-home-activity ink.grootnibbel.launch/.MainActivity'

# verify — should report ink.grootnibbel.launch
ssh rpi 'adb -s 192.168.1.141:5555 shell input keyevent KEYCODE_HOME'
ssh rpi 'adb -s 192.168.1.141:5555 shell dumpsys window | grep mCurrentFocus'
```

Philips' `org.droidtv.homeintentresolver` does **not** intercept the Home key, so third-party
launchers work properly here.

FLauncher has been uninstalled, but its APK is kept on the Pi at
`~/tv-debloat/flauncher-0.18.0.apk` if you ever want it back.

---

## Way of working

This launcher is written by Claude (Claude Code, running on the VPS) working with Ruben, who owns
the TV, the taste and every decision about what ships. This README is Claude-written too, including
this section. What follows is the honest division, because "who actually did what" is otherwise
impossible to reconstruct from a repo like this.

### Idea to deployed

1. **Ruben describes the idea**, usually loosely — "spice it up a bit but not distracting", "make it
   more chaotic", "they shouldn't move around a fixed point". Rarely a spec, and that is fine.
2. **Claude investigates the hardware before proposing anything.** What fonts are actually on the
   set, which GPU it has, what `fonts.xml` will resolve. Several proposals died or improved at this
   step — the clock's typeface exists because `ls /system/fonts` was run before opinions were formed.
3. **Claude proposes two or three options with tradeoffs and a recommendation.** Where it is a
   question of *looks*, the options are mocked up by compositing onto a real screencap, so they can
   be compared without a build.
4. **Ruben picks**, or redirects entirely.
5. **Claude simulates the numbers offline** wherever there are numbers — drift speeds, frame-rate
   budgets, prime periods — because a Python script takes a second and a deploy takes ninety.
6. **Claude writes it, builds, deploys, screenshots and measures.**
7. **Ruben looks at the actual panel and judges.** This is the step Claude cannot do, and it is
   where most decisions are actually made.
8. Iterate, changing one variable at a time. When it settles, Claude writes it down here.

### Who does what

**Ruben** owns everything about what the thing *is*: which apps and in what order, banners over
colour tiles, the empty space under the grid, the clock face and its size, how far the background
colour roams, how chaotic the drift feels, and every yes/no. He is also the only one who sees it
*moving*, in a real room, at a real distance — he watched the background at 60× and decided he liked
being able to catch it moving, which no screenshot could have told either of us.

**Claude** does the investigation, the code, the build/deploy/measure loop, the offline simulation
and mockups, and the writing-down. It brings options and evidence; it does not get a vote on taste.

### The honest part

- **Claude cannot see the TV.** It samples stills over SSH. Judder, flicker, banding that crawls,
  "is this distracting out of the corner of your eye in a dark room" — none of that survives a
  screenshot. Any claim here about how something *feels* came from Ruben.
- **Claude's estimates have been wrong more often than right, always optimistically.** The five-bloom
  background was estimated at 6–15% of a core and measured at 28.5%. The shader rewrite was predicted
  at ~2% and measured 17.1%. Treat a number in this README as reliable when it says *measured*.
- **Claude has stated false things confidently.** A note claiming this machine could not build
  Android hardened into an accepted fact; it was a description of an unconfigured machine, and was
  corrected on 2026-08-28 by installing the SDK in about two minutes.
- **Claude misreads intent.** "Five times slower" was read against the wrong baseline and produced a
  page of argument against a position Ruben had not taken. Restating the ask before acting catches
  most of these.
- **Work gets thrown away, and that is the process working.** An entire ambient background was
  designed, built, deployed and rejected on sight. The revert took one build. Cheap reverts are what
  make loose specs affordable.
- **Confident prose is not evidence.** Claude writes fluently about things it has not verified. The
  habits below exist to force the difference.

### Practices that make it work

**Look at the panel; don't reason about it.** Every visual decision here came from a real screencap.
When the detail is too small to see, crop and magnify: `pngtopnm | pnmcut | pamscale`. That is how
the 18dp corner radius was settled.

**Measure; don't estimate.**

```sh
D=192.168.1.141:5555; P=ink.grootnibbel.launch
ssh rpi "adb -s $D shell dumpsys gfxinfo $P reset"     # then wait ~25s
ssh rpi "adb -s $D shell dumpsys gfxinfo $P | grep -iE 'total frames|janky|percentile'"
ssh rpi "adb -s $D shell top -b -n 1 | grep grootnibbel"
ssh rpi "adb -s $D shell dumpsys meminfo $P | grep TOTAL:"
```

`gfxinfo` says whether the *pipeline* is coping; `top` says what it costs. They answer different
questions and you usually need both — the 6 fps background is 0% janky either way, but the CPU
figure moved by 6×. To separate your own code from the pipeline, log `System.nanoTime` around the
hot function for a few hundred frames, then strip it before shipping.

**Write the success criterion first, and let it fail honestly.** "No pixel changes more than two
levels in sixty seconds" is a criterion; "make it look good" is not. One of these turned out to be
mis-specified rather than unmet, and saying so beat quietly moving the threshold.

**Diff frames to prove something is happening.** Slow animation is indistinguishable from a bug in a
single screenshot. Capture a series and compare means and per-pixel maxima; identical frames mean
nothing is animating. That is how the `setVisible` bug surfaced.

**A speed multiplier is the only way to review slow motion.** `SPEED` in `Ambient.kt` exists so an
hour can pass in a minute. Set it high, watch, set it back, re-check the frame-rate budget.

**Tooling on the VPS is netpbm and nothing else.** No PIL, no ImageMagick, no numpy. Pixel maths is
pure Python, so subsample — a full 1920×1080×3 triple loop is too slow, every 4th pixel is not.

---

## Code

```
app/src/main/kotlin/ink/grootnibbel/launch/
  Tiles.kt         the 8 packages, banner resolution, HDMI input enumeration
  MainActivity.kt  grid layout, D-pad focus, launching, the clock
  Ambient.kt       the drifting background glow
```

`TileArt` is a sealed interface with `Art(drawable)` and `Solid(colour)`. All eight apps ship a
banner, so `Art` is what you see; `Solid` is the fallback for an app that ships none, and paints a
flat `FALLBACK_COLOR` tile carrying the app's icon and label.

Colour-only tiles were the original design and were rejected once seen on the TV: NPO Start averaged
to a muddy lavender, HBO Max's monochrome icon has no brand colour to find, and Netflix and YouTube
both resolved to near-identical reds sitting side by side.

**The dominant-colour extractor was deleted on 2026-08-30**, having outlived that rejection by two
days as dead weight: every app in `APPS` ships an opaque banner, so the colour it produced was never
drawn — not as a `Solid` face, and not as the backing colour behind artwork that is nowhere
transparent. It cost eight icon rasterisations and ~4,600 `getPixel` JNI hops on every return to
Home. There is no git here, so it is written down instead. It drew the icon into a 24x24 bitmap and
took a weighted average of the pixels, weighting each by `saturation² × (1 - |value - 0.6|)` so that
vivid mid-tones carried the result and white or black chrome could not sway it; pixels under 50%
alpha were skipped. The final average was then pinned to `value = 0.60` in HSV, so every tile on the
wall read at one depth rather than as a jumble of brightnesses. If it is ever wanted back, that last
step is the part worth keeping — it is what stopped the set looking accidental.

**Tile corner radius is 18dp and must not be reduced.** NPO Start's banner bakes in its own rounded
corner (~15dp) with a pale mauve fill outside the curve. If our clip is less round than theirs, that
fill shows as pale crescents in the tile corners. The other seven banners run edge-to-edge and don't
care. 14dp was tried first and left a 1–2px fringe; 18dp is clean at 10x zoom.

### Focus

The ring is drawn **entirely outside the tile**, and there is only ever one of it: it hangs on the
*grid's* foreground rather than on any tile, as a single `RingLayer` drawable at an arbitrary rect.
The ring it replaced was a `foreground` on the tile itself, so `clipToOutline` trimmed it inwards and
it covered 5.5 dp of every banner on all four edges — measured on the panel as 6 px of white and
5 px of dark eating into the artwork. It also kept the outer layer's 18 dp radius on a layer inset by
3 dp, so the two arcs diverged around every corner. That doubled-arc wobble was most of what made it
look cheap; an inset rounded rect needs its radius reduced by the same amount it is inset.

It stays **two-tone** — a dark stroke hugging the banner, white outside it — but the original reason
no longer applies. That was written when the ring sat *on* the artwork and vanished against Netflix's
and YouTube's white banners; outside the tile it is always against dark background. It is kept
because it costs nothing and still separates cleanly where a near-white banner sits behind it.

A focus move lands as one event: the ring is placed on the new tile while that tile scales to 1.04
over `MOVE_MS` (200 ms) on `MOVE_CURVE`.

**The ring does not travel between tiles, and that was a decision rather than an omission.** A
travelling ring was built, measured and judged on the panel over a live three-way A/B. It costs
nothing in frames — 0.98% janky against snap's 0.70% — so the case against it is entirely about
whether it earns its place, and on this grid it does not: every D-pad press moves one tile to an
*adjacent* neighbour, so the destination is never ambiguous and there is nothing for a sliding ring
to disambiguate. Focus is animated either way by the tile's own scale, which satisfies the usual
"animate focus so the eye can follow it" guidance without the ring moving at all. And on a held
D-pad repeat, presses arrive faster than `MOVE_MS`, so the ring never arrives and trails a tile that
has already grown. It survives behind `TRAVEL = false` in `MainActivity.kt`; flipping that constant
is the whole change.

**Focus survives a return to Home**, and costs no saved state to do it. The tree is built once in
`onCreate` rather than per resume, so the view that had focus still has it — it was never torn down.
A process kill lands you back on the first tile, which is the right amount of memory for a launcher.

The grid used to rebuild on every resume to keep HDMI live-state and app installs current. Neither
needs it: the HDMI rail is dormant, and `APPS` is hardcoded, so the only way the tiles change is an
edit and a reinstall, which restarts the process anyway. Re-enabling `hdmiTiles()` means refreshing
that rail's connection state somewhere — `onResume` is still the obvious place, but for that rail
only, not for the whole tree.

### The numeric keypad

The remote's number keys open a tile outright, counting the grid the way you read it — 1 is top
left, 8 is bottom right. `onKeyDown` in `MainActivity`. Focus follows the launch, so coming back
leaves the cursor on what you just opened.

Each tile carries its number in the top-left corner as a **bare digit — no chip, no circle, no
badge**. That was chosen off a live eight-way on the panel, one treatment per tile so all eight
could be judged at a glance: dark chip top-left / top-right / bottom-right, light chip, circle,
bare digit, and the digit placed outside the tile above and below it.

Two things settled it. The chips all ran into the banner-contrast problem the focus ring had — a
light chip on YouTube's white banner was invisible, a dark chip on NLZIET's black corner nearly so.
And the two treatments that sat *outside* the tile, which is how the ring escapes that problem,
failed for a different reason: the row gap is only 32 px, so a digit dropped in it sat 12 px from
the tile above and 26 px from the tile it labelled. It attached to the wrong tile.

It is set in **the clock's own face at 13sp** — `AndroidClock.ttf`, loaded once and shared (see
"The clock"). It first shipped bold at 17sp, which made it the heaviest type on the wall after the
banner artwork itself; Ruben's read was that the numbers were too prominent. Weight and size were
the two levers worth pulling, and the thin clock face is the one that also earns its place: the
digits are the only other numbers on screen, so they now rhyme with the clock rather than compete
with it, and that font's `0`–`9`-only charset is no limitation at all for a single digit. Alpha was
deliberately *not* the lever — thin and small stays crisp where thin and faded goes muddy on the
mid-grey banners (HBO Max, NPO Start). The drop shadow came down with the size, to 3dp at `0x99`
dark / `0x33` light.

The digit takes its colour from the artwork under it. `hasLightCorner()` in `Tiles.kt`
rasterises the banner once at 32x18 — its own 16:9 shape, small enough to cost nothing and blurry
enough that no single pixel can swing the answer — and averages luminance over the cells the digit
covers, roughly 4-17% across and 7-30% down. Above 140/255 the digit is drawn near-black, below it
white, with a low-alpha shadow in the opposite tone for the mid-grey cases. Netflix and YouTube get
dark digits; the other six get white.

(Yes, this is a colour sampler, two days after `dominantColor()` was deleted for being one. The
difference is that this one's output is actually drawn, and it answers one binary question off 25
pixels rather than computing a hue nothing rendered.)

Both `KEYCODE_1..9` and `KEYCODE_NUMPAD_1..9` are handled. The sets this TV pairs with (`TPV_SMTRC`,
`TPV_MutilRC` — TP Vision's own remotes) advertise `KEY_1..9` *and* `KEY_NUMERIC_1..9`, per
`getevent -lp`, and which of the two arrives depends on the remote in your hand. With eight apps, 9
and 0 do nothing.

### The background

One soft radial glow wandering over the old TL→BR ramp, cycling slowly through teal, indigo and
plum. `Ambient.kt`. It is deliberately **not** tied to what has focus — it just drifts.

**It is a pure function of the wall clock, holding no state.** This is the whole trick, and it earns
its keep twice. It is why phase does not restart when the hierarchy is built — anything phased from
construction would show an *identical* screen every time you walked into the room, which is the
opposite of the point. And it is why the glow can be **stopped dead for the length of a focus move**
and nobody can tell: frames skipped are not frames owed, so it resumes exactly where it would have
been. See below.

**The sky is its own view, on its own hardware layer, and it holds still during a focus move.** Both
halves of that are frame-rate fixes, and together they took focus moves from 25% janky to under 1%.

As the root's *background*, the three shader fills were recorded into the root's display list and
re-blended on every frame that damaged anything — 50 times a second during a focus move, for
something that changes 6 times a second. Measured over 20 moves: 25% of frames janky, 90th
percentile 32 ms against a 20 ms budget, with the profiler blaming "slow issue draw commands". The
same run with a flat `ColorDrawable` behind everything was 0% janky at 8 ms. Moving it into `sky()`
with `LAYER_TYPE_HARDWARE` took that to ~9-11%: in its own layer it renders offscreen only when it
actually invalidates, and every other frame composites one quad. PSS was unchanged at 23.8 MB.

What was left was the invalidations themselves. Each one re-renders the whole 1920x1080 layer from
three shaders, and that single frame blows the budget outright. At 6 fps against a 200 ms move, one
collides with about 1.2 of them — so roughly **one frame in ten was dropped on every focus move**,
which is exactly the stepping the eye reads as a low frame rate on something moving. `moveRing` now
sets `ambient.paused` for the length of the move and releases it after; while paused the tick
re-arms without invalidating. **11.58% janky at 90th-percentile 21 ms became 0.98% at 11 ms**, and
46 of the 49 janky frames disappeared with it.

Note this is the reason a *travelling* focus ring turned out to cost nothing — it was never the
animation, it was the background underneath it. Worth remembering before blaming the next animation
for dropping frames on this panel.

**The hold is not travel scaffolding, and it did not leave with travel.** It guards the *focus
move*, and the tile's own 1.04 scale runs for `MOVE_MS` on every press whether the ring travels or
snaps — so the collision window is identical either way. Measured on the shipping build, snap and
all, with only this block removed: **12.42% janky at a 90th percentile of 22 ms**, against 2-5% at
9-13 ms with it. Do not delete it on the grounds that travel is gone.

Three Skia shaders, no per-pixel work: the base ramp, the glow, and a grain tile. Position and the
radius pulse ride on the glow's local matrix, so they cost nothing; the only thing that forces a
shader rebuild is the colour crossing an 8-bit step, roughly twice a second.

**Why 6 fps is enough**, which is the non-obvious part. The frame rate is set by how much any single
pixel may change between frames — keep it under about one 8-bit level and the stepping is invisible.
Recolouring dominates that budget because it moves *every* pixel at once, while sliding a 918 px
falloff sideways barely moves any of them: the spatial gradient is that shallow. Measured over a
full colour cycle at `SPEED = 24`, the worst case is ~0.35 levels from colour against ~0.20 from
position. There is no moving edge that needs temporal resolution, so the frame rate can collapse.
Dropping 30 fps → 6 fps took the background from 17.1% of a core to **2.9%**, with no visible change.

Focus animations are unaffected — they drive their own invalidations at display rate.

Other numbers that are load-bearing:

- **`RANGE = 0.30`.** With a single glow, letting it wander as far as the five-bloom version did
  (0.62) leaves the screen flat whenever it drifts off the panel. Raise it and you must raise the
  periods with it, or you get speed you did not ask for — velocity is amplitude over period.
- **Three octaves of sine per axis**, amplitudes 0.72 / 0.18 / 0.06 against periods 1 / 2.63 / 5.71.
  One sine per axis traces a clean Lissajous figure the eye starts to anticipate. The amplitudes
  fall off faster than the periods so the added detail changes the *shape* of the path, not the pace.
- **Every period is prime** (in simulated seconds), so no two motions share a factor and there is no
  loop point to notice.
- **The grain is not decoration.** The base ramp spans about six 8-bit levels across the whole
  screen, which bands visibly on a panel this size, and a band that drifts this slowly crawls. A
  repeating 64×64 tile of 0–3/255 white breaks up the quantisation.

**Re-arm the repaint from `draw()`, not from `setVisible()`.** `View.onVisibilityAggregated` only
calls `setVisible` when the value *changes*, and `Drawable` starts out visible, so an override there
never fires and the animation silently never starts — it took a frame-by-frame diff to spot that the
only repaints were the clock ticking over each minute. Driving it from `draw` also stops the loop
for free: once the view stops drawing, `invalidateSelf` has nothing to invalidate.

Two designs that were built, measured and rejected:

- **Five blooms summed into a 128×72 buffer and upscaled** (the bilinear filter on the way up was
  the blur). It worked and never dropped a frame, but cost 28.5% of a core. Worth knowing how that
  split: the per-pixel Kotlin was only ~12 points of it, and the other ~17 was simply the cost of
  repainting a full-screen background at 30 fps. Rendering technique was not the lever; frame rate was.
- **Tinting the background from the focused tile's dominant colour.** Appealing at the time, because
  `dominantColor()` was then sitting unused in `Tiles.kt` — it has since been deleted, see "Code" —
  but focus then reset to Netflix on every return to Home, so you would have arrived to the same red
  wash forever. That particular objection has since expired — focus survives a return to Home now
  (see "Focus") — so if this is ever revisited, it needs to fall on the argument below rather than
  that one: it drags eight companies' brand colours into a background they were never designed to
  share.

To tune it: `PERIOD_C` calms the whole thing down far more than slowing the movement does, because
colour is most of what you see. `SPEED` is a plain multiplier on time — raising it for a few minutes
is the easiest way to watch a full cycle, but re-check the frame-rate budget above before leaving it
raised.

### The clock

Top right, 24-hour, pinned to `Europe/Amsterdam` rather than trusting the set's own clock. It is a
framework `TextClock`, which ticks itself off `ACTION_TIME_TICK` while attached — there is no handler
to own and nothing to tear down. (This once read "nothing to tear down across the `onResume`
rebuild"; there is no such rebuild any more — the view tree is built once in `onCreate`.)

`format12Hour = null` is what forces 24-hour. `TextClock` picks between the two format strings based
on the system 12/24 setting, and nulling the 12-hour one leaves it no choice.

The face is **`/system/fonts/AndroidClock.ttf`**, Android's retired lockscreen clock font, which this
firmware still ships. Two consequences worth knowing before you touch it:

- It has no family name in `fonts.xml`, so it cannot be reached with `Typeface.create("…")` and has
  to be loaded by absolute path with `Typeface.createFromFile`.
- **Its character set is `0`–`9`, `:` and space, and nothing else.** `HH:mm` is safe forever, but the
  format string can never grow a date or a weekday without also changing the typeface.

Set at 48sp with `letterSpacing = 0.05f`. On this panel 1 sp = 2 px, so that is a 96 px em box on
the 1080p framebuffer — the hairline strokes need the size to register from the sofa.

The typeface is held in a lazy `clockFace` on the activity rather than created at each use, because
the eight shortcut digits share it. That is also why the charset limit above is not the constraint
it looks like: both things that use this font draw nothing but digits.

To survey what else is available before changing it:

```sh
ssh rpi 'adb -s 192.168.1.141:5555 shell ls /system/fonts'
ssh rpi 'adb -s 192.168.1.141:5555 shell "grep -o \"alias name=\\\"[a-z-]*\\\"\" /system/etc/fonts.xml | sort -u"'
```

The set carries the full Roboto family (thin through black, plus condensed), Noto Serif, Droid Sans
Mono and Cutive Mono, so `sans-serif-thin`, `sans-serif-light`, `sans-serif-condensed-light`, `serif`
and `monospace` all resolve. Roboto's digits are uniform width, so nothing shifts at the minute
rollover whichever face you choose.

### Changing the apps

Edit `APPS` in `Tiles.kt`. The list is in grid order, left to right then down. Find package names with:

```sh
ssh rpi 'adb -s 192.168.1.141:5555 shell "for p in \$(pm list packages -e | sed s/package://); do \
  r=\$(cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER \$p 2>/dev/null | tail -1); \
  case \$r in */*) echo \"\$r\";; esac; done"'
```

(`cmd package query-activities` does not exist on SDK 26 — hence the per-package loop.)

---

## Not done yet

- **HDMI inputs.** Built, verified working on the hardware, then removed from the layout for now.
  `hdmiTiles()` in `Tiles.kt` is intact and dormant: it enumerates `TvInputManager` HDMI inputs
  (`HW5`–`HW8` = HDMI 1–4), reads live connection state per input so dead ones can dim, and launches
  via `TvContract.buildChannelUriForPassthroughInput`. It needs no special permission. Re-enabling
  means restoring the left-hand rail in `MainActivity`. Watch out: Philips labels HDMI 1
  "HDMI 1 / MHL", which clipped in an 84 dp rail.
- **User-editable app list**, and a settings button where an app drawer would live.
- **A 3x3 grid.** Nine apps would put every number key to work. Worth knowing before committing:
  at four columns a tile is ~416 px wide and the 320x180 banners upscale 1.3x; at three columns
  spanning the same width they would be ~560 px and upscale 1.75x, which will read as visibly
  softer on a 1080p panel. The fix is to not span the full width — keep tiles near 416 px and let
  the block sit centred with wider side margins. Three rows of tiles fit the height comfortably.
