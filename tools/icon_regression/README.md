# Offline real-icon replay

`replay.py` runs captured icon images through MonoIcon's Kotlin mask and border
code. It compiles the production files directly from cached Kotlin compiler
jars and runs locally without Gradle, ADB, or an emulator.

## Run a replay

Use Python 3.10 or later with Pillow 9.1 or later, a JDK that can run Java 17 class files,
and cached Kotlin compiler dependencies. Android Studio's bundled JBR works.
Pass `--java <java-executable>` or `--gradle-cache <modules-2/files-2.1>` if you
keep either dependency outside its default location.

From the repository root, create `captures/icon-replay/manifest.json` with the
following contents. It uses the foreground PNG included in the instrumented
test fixtures. Image paths are resolved relative to the manifest.

```json
{
  "cases": [
    {
      "name": "Bilibili foreground alpha",
      "source": "../../app/src/androidTest/assets/generated-border-bilibili/foreground.png",
      "mode": "mask"
    }
  ]
}
```

```powershell
python tools/icon_regression/replay.py captures/icon-replay/manifest.json
```

Open `replay-output/report.json` and `replay-output/contact-sheet.png` beside the
manifest. Use `--out <directory>` to place the results elsewhere. With
`--source-dir <directory>`, the runner uses snapshots named
`GeneratedMaskProcessor.kt` and `GeneratedIconBorder.kt` from that directory.
Tonal cases also compile the checkout's `GeneratedTonePalette.kt`. The report
records the Kotlin input hashes.

## Configure replay cases

This manifest shows how to supply your own PNGs, plate geometries and white
border ratios from `0` (no border) to `1` (maximum border).

```json
{
  "ratios": [0, 0.25, 0.5, 0.75, 1],
  "geometries": [
    {"width": 200, "height": 200, "radius": 50},
    {"width": 320, "height": 160, "radius": 40}
  ],
  "cases": [
    {
      "name": "Real generic icon",
      "source": "captures/example-rgba.png",
      "mode": "extract",
      "inverted": false,
      "probes": [{"x": 32, "y": 32, "min_alpha": 224}]
    },
    {
      "name": "Real adaptive foreground",
      "source": "captures/example-foreground.png",
      "mode": "refine",
      "baseline": "captures/example-preclip-baseline.png",
      "border_mask": "captures/example-final-mask.png"
    },
    {
      "name": "Captured final alpha geometry",
      "source": "captures/example-final-mask.png",
      "mode": "mask"
    }
  ]
}
```

- `extract` replays the pixel operation called by
  `LabMonochromeExtractor.extractFromBitmap`. Supply the resolved `inverted`
  flag for the original drawable's background polarity.
- `refine` requires the artwork RGBA and AOSP luminance baseline at the same
  intermediate dimensions, before `rasterizeClipped`. It replays that finishing
  step precisely. It does not reconstruct Android drawable layers from APK XML.
- `mask` uses input alpha unchanged, for captured final masks and geometry checks.
  For grayscale mask PNGs without alpha use `mask_channel: "luminance"`.
- `tonal` calls production `GeneratedMaskProcessor.tonal` and
  `GeneratedTonePalette.map`. It writes exact preclip grayscale RGBA and light/
  dark mapped RGBA, verifies unchanged source alpha, neutral grayscale channels,
  monotonic source luminance/tone order, and palette alpha preservation. Genuine
  transparent holes stay transparent independently of white RGB.
- Tonal previews use the center two-thirds of the AOSP viewport, resize to
  `aosp_output_size` (default `[192, 192]`), and approximate the AOSP default
  squircle in Pillow. The device may use a different framework mask.
- Optional manifest `preview_canvas_size` centers the requested first geometry
  inside a larger transparent preview canvas. For the captured 192-pixel before
  icons, a 176-pixel plate centered in `[192, 192]` retains their 8-pixel inset.
- Per-case `preview_mode: "alpha"` with `preview_mask` keeps native or single-tone
  masks on the existing tint path while still exercising the tonal pixel function.
- `border_mask` optionally provides the coverage used for border geometry.
  Adaptive icons with a smooth opaque gradient and a nonempty foreground use
  foreground coverage and tone, allowing the outer theme plate to remain visible.
  Structured background artwork retains the combined layers. Without a captured
  mask, geometry uses the processor output. `report.json` records which mask it
  used. The PNG inputs reflect whichever adaptive layers were captured.
- Alpha PNG inputs default to their alpha channel. Use `baseline_channel`,
  `border_mask_channel`, or `compare_mask_channel` set to `"luminance"` for
  grayscale intensity masks.
- `crop: [left, top, right, bottom]` selects source pixels. Use uncomposited
  drawable RGBA when testing the original artwork's alpha and tone.
- Optional `compare_mask` records changed pixels and alpha error against a
  reference. Add `require_exact_comparison: true` to fail on any difference.
- Optional per-case `probes` express reviewed expectations using pixel `x`, `y`,
  `min_alpha`, `max_alpha`, and an optional explanatory label.

## Read the results

Outputs include exact `.alpha` bytes and alpha PNGs, actual Kotlin border scales,
ratio-invariance checks, bounds/coverage metrics, input/source SHA-256 hashes, and
`contact-sheet.png`. A failed probe, changed source mask across ratios, or required
exact-reference mismatch exits with status 1.

At 100%, generated artwork's maximum extent about the source center is normalized
to `0.7 * sqrt(375/576)` of the plate's shorter side, using the AOSP legacy square
baseline, capped by the complete artwork's containment limit. Scales can be below
or above 1. At 0%, all nonzero source-alpha pixel cells fit inside the selected
rounded plate, using one uniform scale about the authored center. Intermediate
ratios interpolate toward this fit endpoint and preserve source alpha.

The contact sheet and `*-preview.png` files use Kotlin's computed scale and
Pillow's filtering and antialiasing. Verify drawable rasterization, native layer
loading, launcher hooks and final appearance on the target Android device.

## Review device captures

- `summarize_capture.py <capture-directory>` reads `cases.json` and the per-app
  PNGs, writes a contact sheet with `original`, `before_0`, `before_50` and
  `before_100`, and creates `refine-manifest.json` from valid inputs. It adds an
  exact comparison when a matching `refined_preclip.png` is available. Read
  `capture-validation.json` for missing or failed captures.
- `fit_capture_zoom.py <capture-directory>` uses NumPy and centered bilinear
  image registration to estimate zoom from the `before` PNGs. Use captured
  alpha masks with `replay.py` to calculate the production Kotlin scales.
- `review_tonal.py <fixtures> <tonal-replay-output>` compares captures with
  light and dark replay previews. Use package names for the case names and
  include the 0%, 50% and 100% ratios in the replay. Check raw source selection
  and desktop size on the launcher itself, where the themed `getIcon0` result
  can use different artwork or cropping.
