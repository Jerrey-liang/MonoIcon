#!/usr/bin/env python3
"""Measure observed zoom in real before PNGs while exact mask exports are pending.

This image-registration result is an estimate, not the exact Kotlin coefficient.
Only unchanged opaque outer plate pixels enter the fit.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


def fit(reference: Image.Image, actual: Image.Image) -> dict:
    source = np.asarray(reference, dtype=np.float64)
    target = np.asarray(actual, dtype=np.float64)
    h, w = target.shape[:2]
    yy, xx = np.mgrid[:h, :w]
    # Android drawBitmap uses pixel centers; the glyph rect is centered at W/2,H/2.
    xx = xx + 0.5
    yy = yy + 0.5
    inside = target[:, :, 3] >= 254

    def score(scale: float) -> float:
        sx = (xx - w / 2) / scale + w / 2 - 0.5
        sy = (yy - h / 2) / scale + h / 2 - 0.5
        x0 = np.floor(sx).astype(np.int64).clip(0, w - 2)
        y0 = np.floor(sy).astype(np.int64).clip(0, h - 2)
        ax = (sx - x0)[:, :, None]
        ay = (sy - y0)[:, :, None]
        prediction = ((source[y0, x0] * (1 - ax) + source[y0, x0 + 1] * ax) * (1 - ay)
                      + (source[y0 + 1, x0] * (1 - ax) + source[y0 + 1, x0 + 1] * ax) * ay)
        valid = inside & (prediction[:, :, 3] >= 254)
        return float(np.mean((prediction[:, :, :3][valid] - target[:, :, :3][valid]) ** 2))

    best = min((score(float(s)), float(s)) for s in np.linspace(1, 8, 141))
    for step, half_span in ((0.005, 0.06), (0.0005, 0.006)):
        best = min((score(float(s)), float(s)) for s in np.arange(max(1, best[1] - half_span),
                                                                  best[1] + half_span + step / 2, step))
    return {"estimated_scale": round(best[1], 5), "rgb_mse": best[0],
            "rgb_rmse": best[0] ** 0.5}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    folder = args.capture.resolve()
    cases = json.loads((folder / "cases.json").read_text(encoding="utf-8-sig"))
    report = {"precision": "estimated image registration of actual captured before PNGs; not exact Kotlin scale",
              "model": "centered uniform scaling, pixel-center bilinear RGB sampling; opaque plate interior only",
              "cases": []}
    for case in cases:
        if case.get("status") != "ok":
            report["cases"].append({"package": case["package"], "status": "capture_failed",
                                     "error": case.get("error")})
            continue
        files = folder / case["package"]
        reference = Image.open(files / "before_100.png").convert("RGBA")
        row = {"package": case["package"], "status": "measured", "scale_100": 1}
        for percent in (0, 50):
            row[f"fit_{percent}"] = fit(reference, Image.open(files / f"before_{percent}.png").convert("RGBA"))
        row["half_ratio_linearity_error"] = row["fit_50"]["estimated_scale"] - (row["fit_0"]["estimated_scale"] + 1) / 2
        report["cases"].append(row)
        print(case["package"], row["fit_0"], row["fit_50"], flush=True)
    output = (args.out or folder.parent / "capture-review/captured-zoom-fit.json").resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
