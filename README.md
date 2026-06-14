Disclaimer: fully vibe-clauded.

# Teeter Port

Android port of the HTC Teeter accelerometer maze game, rebuilt from
decompiled original (`com.htc.android.teeter`).

## 16:9 render

The entire game runs in a fixed 1280×720 logical framebuffer the exact coordinate
space the original level XMLs and background art were copied in (the original
`config_hd` profile). Each frame is rendered into that fixed bitmap and then
uniformly scaled and letterboxed/centered, into whatever the device's
real resolution is (`GameView.computeDst`). Black bars fill any leftover area.

And so:
- physics behave the same as on original hw
- No per-density assets and no level rescaling — one set of 1280×720 art + levels.

## Architecture

| Class | Role | Ported from |
|-------|------|-------------|
| `MainActivity` | Sensor/vibrator/wake-lock host, save/resume, pause + score overlays | `CTeeterActivity`, `CCoverActivity` |
| `GameView` | SurfaceView render loop, letterbox blit, state machine, animations | `GameView`, `CGamePage`, `CDispMgr`, `CGameModel` |
| `Ball` | Accelerometer physics, wall/corner/edge collisions, holes, facet tilt | `CBall` |
| `Level` | Level XML parser, facet acceleration-zone solver | `CL`, `CBGLoadingThread.fnParseLevelFile` |
| `Background` | Bakes maze/facet + holes + end + wall shadows into one bitmap | `CBGLoadingThread.fnCreateBG` |
| `Config` | Tunables + fixed-point helpers | `CU` |
| `Score` | Per-level / total time + attempts | `CS`, `CTime` |
| `Sound` | SoundPool SFX | `CDispMgr.EffectHandler` |
| `Vec`, `Geometry` | Integer vector math, 3D facet geometry | `Vector`, `Vector3D`, `Point3D`, `Triangle` |

Physics is a faithful port: integer fixed-point positions (`<< 10`), the original
gravity/friction/bounce constants, and the same wall/corner/edge collision passes
(`wallContactFix` → `screenContactFix` → `*Control`).

Two things differ from the original's threading model. The original split physics
onto a `Handler` thread and rendered from the UI thread's `onDraw`; the port runs
one loop thread that steps then renders. To keep that single thread smooth:

- **Fixed-timestep integration.** Real elapsed time is accumulated and drained in
  fixed 20 ms steps (the original simulation step), capped at 5 steps of catch-up.
  Motion is frame-rate independent, and a delayed frame (GC, scheduler) runs a
  couple of normal steps to catch up instead of one oversized step that lurches
  the ball.
- **Off-thread haptics.** `Vibrator.vibrate()` is a synchronous binder call into
  `system_server`; wall scrapes can fire it every physics tick. Vibrations are
  posted to a dedicated `HandlerThread` so the loop never blocks on the IPC.

## Assets

- `assets/config.xml` — game parameters (sizes, physics, haptics) from the
  original `config_hd` profile.
- `assets/arrays.xml` — 11 surface-normal samples (`a`..`k`) for facet (tilted)
  levels.
- `assets/levels/level001..032.xml` — all 32 levels (xhdpi / 1280×720 coords).
- `res/drawable-nodpi/` — the splash (`splash_bg`, `splash_0001..0060`), the game
  art (`maze`, `facet`, `ball`, `end`, `hole`, `end_anim`, `hole_anim_001..020`),
  and the reconstructed `teeter_bar_shadow.9.png`.
- `res/raw/` — `hole`, `level_complete`, `game_complete` (ogg).

### HTC JPEG → PNG alpha conversion

The original art ships as HTC's proprietary JPEG: a standard JPEG with the alpha
channel smuggled in an `APP1` marker (a GIF89a grayscale mask), decoded only by
HTC's custom `SkJPEGImageDecoder`. A stock `BitmapFactory` ignores that marker and
renders the image fully opaque. Every alpha-bearing source was therefore converted,
which lifts the embedded GIF mask back into a real alpha channel and writes a normal
PNG. The three fully-opaque backgrounds (`maze`, `facet`, `splash_bg`) carry no
mask and stay JPG.

### Wall-shadow nine-patch

The wall-shadow nine-patch was reconstructed: the decompiled APK only retained the
compiled `npTc` chunk, so the 1px guide border (stretch region `[11,12]`, padding
`L1 T1`) was repainted to make it a valid source `.9.png`. The shadow is stamped
with the original's xhdpi padding (`7/7/8/7`, left/right/top/bottom) and composited
over itself once.