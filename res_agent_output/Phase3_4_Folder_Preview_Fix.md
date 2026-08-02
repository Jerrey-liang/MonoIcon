# MonoIcon Phase 3.5-3.7 — Folder Preview Investigation Report

**日期**: 2026-08-02
**状态**: Diagnostics completed. Phase 3.7 ready for testing after reboot.

---

## 1. Problem Summary

FolderPreviewIconView (closed folder): NATIVE/FOREGROUND → black rectangles. LUMINANCE → black silhouettes. ShortcutIcon → all work.

## 2. Investigation Timeline

### Phase 3.5 — Bitmap Diagnostics

**Commit**: `db93c14`

All sources produce identical Bitmap content:
- `config=ARGB_8888`
- `rgbNonZero=0` (all pixels R=G=B=0)
- `alphaMin=0, alphaMax=255`
- `center=0xFF000000, corner=0x00000000`

**Category A (RGB) ruled out.** Category C (alpha corruption) ruled out.

### Phase 3.6 — Drawable/ImageView Diagnostics

**Commit**: `46b3dc2`

Critical finding: `view=0x0` — FolderPreviewIconView not yet laid out when drawable set. `scaleType=FIT_CENTER`. Original drawable class: `LayerAdaptiveIconDrawable`.

All sources identical in Drawable bounds/intrinsic/alpha. **Category B (dimensions) ruled out.** **Category D confirmed.**

### Phase 3.7 — Drawable Lifecycle Diagnostics

**Commit**: `c6a6f4b`

Added `DiagnosticDrawable` wrapper that delegates to original and logs all lifecycle events: `setBounds`, `draw(Canvas)`, `getIntrinsicWidth/Height`, `getOpacity`.

Ready for runtime testing to compare LayerAdaptiveIconDrawable vs BitmapDrawable lifecycle during 0x0 ImageView state.

## 3. Root Cause Hypothesis

`LayerAdaptiveIconDrawable` handles `view=0x0 + scale=FIT_CENTER` gracefully (delayed render). Our `BitmapDrawable` renders black rectangle when bounds are 0x0 or canvas is empty.

## 4. Next Step (Phase 3.8)

After collecting lifecycle logs, if confirmed that BitmapDrawable draws before layout→ fix: defer drawable application until ImageView has valid dimensions, or create a delayed-render Drawable wrapper.

## 5. Git History

```
c6a6f4b Phase 3.7: investigate folder preview drawable lifecycle
46b3dc2 Phase 3.6: add folder preview drawable diagnostics
db93c14 Phase 3.5: add folder preview rendering diagnostics
b0b8659 Phase 3.4: fix folder preview monochrome rendering target
3603738 Phase 3.3: fix folder preview monochrome rendering
cc0a7f8 Phase 3.2: isolate cache source type
```
