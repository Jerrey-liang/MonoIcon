# Offline real-icon replay

`replay.py` compiles the current `GeneratedMaskProcessor.kt` and
`GeneratedIconBorder.kt` directly with cached Kotlin compiler jars. It invokes no
Gradle tasks, emulator, ADB, or APK installation and downloads nothing.

Requirements: JDK (Android Studio's bundled JBR works), cached Kotlin compiler
dependencies, Python, Pillow. On this workstation `python` already has Pillow.

```powershell
python tools/icon_regression/replay.py external_resources/icon-regression-20261115/manifest.json
```

For fixed historical results, `--source-dir <folder>` uses snapshots named
`GeneratedMaskProcessor.kt` and `GeneratedIconBorder.kt` instead of current
production files. Their actual hashes are recorded in the report.

`summarize_capture.py <capture-directory>` validates a device `cases.json` and all
PNG exports, creates an actual-device original/before0/50/100 contact sheet, and
generates `refine-manifest.json` from valid inputs. It only requests an exact
device comparison when a matching `refined_preclip.png` reference exists. Missing
or failed captures remain visible in its validation report.

`fit_capture_zoom.py <capture-directory>` estimates observed zoom directly from
actual before PNGs using centered bilinear image registration. It additionally
requires NumPy. Its results are explicitly estimates; use captured alpha masks
with `replay.py` for exact production Kotlin geometry coefficients.

The manifest paths are relative to its directory. Example:

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

- `extract` precisely replays the pixel operation called by
  `LabMonochromeExtractor.extractFromBitmap`. Supply its resolved inversion flag;
  the driver does not guess the original drawable's background polarity.
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
- Tonal previews use the center 2/3 AOSP viewport, resize to
  `aosp_output_size` (default `[192, 192]`), and sample the documented AOSP default
  squircle. This is approximate Pillow geometry, not the phone's Skia renderer or
  a proof that its configured framework mask matches the documented fallback.
- Optional manifest `preview_canvas_size` centers the requested first geometry
  inside a larger transparent preview canvas. For the captured 192-pixel before
  icons, a 176-pixel plate centered in `[192, 192]` retains their 8-pixel inset.
- Per-case `preview_mode: "alpha"` with `preview_mask` keeps native or single-tone
  masks on the existing tint path while still exercising the tonal pixel function.
- `border_mask` optionally provides the coverage used for border geometry.
  Adaptive icons with a smooth opaque gradient and a nonempty foreground use
  foreground coverage and tone, allowing the outer theme plate to remain visible.
  Structured background artwork retains the combined layers. Without a captured
  mask, geometry uses the processor output; `report.json` records this distinction.
  This replay does not reconstruct Android's adaptive layer selection.
- Alpha PNG inputs default to their alpha channel. Use `baseline_channel`,
  `border_mask_channel`, or `compare_mask_channel` set to `"luminance"` for
  grayscale intensity masks.
- `crop: [left, top, right, bottom]` can select source pixels. A cropped
  screenshot is useful for visual investigation but is not equivalent to the
  original uncomposited drawable RGBA.
- Optional `compare_mask` records changed pixels and alpha error against a
  reference. Add `require_exact_comparison: true` to fail on any difference.
- Optional per-case `probes` express reviewed expectations using pixel `x`, `y`,
  `min_alpha`, `max_alpha`, and an optional explanatory label.

Outputs include exact `.alpha` bytes and alpha PNGs, actual Kotlin border scales,
ratio-invariance checks, bounds/coverage metrics, input/source SHA-256 hashes, and
`contact-sheet.png`. A failed probe, changed source mask across ratios, or required
exact-reference mismatch exits with status 1.

At 100%, generated artwork's maximum extent about the source center is normalized
to `0.7 * sqrt(375/576)` of the plate's shorter side, using the AOSP legacy square
baseline, capped by the complete artwork's containment limit. Scales can be below
or above 1. At 0%, all nonzero source-alpha pixel cells fit inside the selected
rounded plate, using one uniform scale about the authored center. Intermediate
ratios interpolate toward this fit endpoint; source alpha remains unchanged.

The contact sheet and `*-preview.png` files are review aids composed in Pillow.
They preserve the fixed outer plate and use the exact Kotlin enlargement scale,
but Pillow filtering and antialiasing differ from Android Skia. They must not be
described as pixel-identical Android renderings. Final Android drawables, resource
rasterization, native layer loading, hooks, and device appearance still require a
single final on-device verification.

`review_tonal.py <fixtures> <tonal-replay-output>` produces all-icon and focus
contact sheets comparing captured APK originals/before PNGs with new light/dark
0/50/100 previews. APK resources and launcher's themed `getIcon0` results can have
different artwork and cropping. This offline evidence does not establish that
Hook 7 selects the correct raw APK source or explain every desktop size issue.
