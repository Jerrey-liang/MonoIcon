#!/usr/bin/env python3
"""Validate one-time Android captures and build actual-device before sheets."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from replay import digest, thumbnail


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    folder = args.capture.resolve()
    out = (args.out or folder.parent / "capture-review").resolve()
    out.mkdir(parents=True, exist_ok=True)
    cases = json.loads((folder / "cases.json").read_text(encoding="utf-8-sig"))
    columns = ["original", "before_0", "before_50", "before_100"]
    records, review_cases, replay_cases = [], [], []
    for case in cases:
        pkg = case["package"]
        record = {"package": pkg, "capture_status": case.get("status", "failed"),
                  "capture_error": case.get("error"), "files": {}}
        if case.get("status") != "ok":
            records.append(record)
            continue
        for path in sorted((folder / pkg).glob("*.png")):
            try:
                with Image.open(path) as image:
                    image.load()
                    record["files"][path.stem] = {"valid": True, "size": list(image.size),
                                                   "mode": image.mode, "sha256": digest(path)}
            except Exception as error:
                record["files"][path.stem] = {"valid": False, "bytes": path.stat().st_size,
                                               "error": str(error)}
        record["before_review_valid"] = all(record["files"].get(name, {}).get("valid") for name in columns)
        record["refine_inputs_valid"] = all(record["files"].get(name, {}).get("valid")
                                             for name in ("foreground", "baseline"))
        record["captured_mask_valid"] = record["files"].get("strategy_mask", {}).get("valid", False)
        if record["before_review_valid"]:
            alphas = {name: Image.open(folder / pkg / f"{name}.png").convert("RGBA").getchannel("A").tobytes()
                      for name in columns[1:]}
            reference = alphas["before_100"]
            record["before_outer_alpha_changed_pixels"] = {
                name: sum(a != b for a, b in zip(data, reference)) for name, data in alphas.items()}
            record["before_outer_alpha_equal"] = all(
                data == reference for data in alphas.values())
            review_cases.append(case)
        if record["refine_inputs_valid"] and record["captured_mask_valid"]:
            replay_case = {"name": pkg, "source": str(folder / pkg / "foreground.png"),
                           "mode": "refine", "baseline": str(folder / pkg / "baseline.png"),
                           "border_mask": str(folder / pkg / "strategy_mask.png")}
            reference = record["files"].get("refined_preclip", {})
            if reference.get("valid") and reference.get("size") == record["files"]["foreground"]["size"]:
                replay_case.update(compare_mask=str(folder / pkg / "refined_preclip.png"),
                                   require_exact_comparison=True)
            record["refine_exact_reference_available"] = "compare_mask" in replay_case
            replay_cases.append(replay_case)
        records.append(record)
    cell_w, row_h, image_size = 208, 230, 192
    sheet = Image.new("RGB", (len(columns) * cell_w, 40 + len(review_cases) * row_h), "white")
    draw = ImageDraw.Draw(sheet)
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 14) if font_path.exists() else ImageFont.load_default()
    for col, name in enumerate(columns):
        draw.text((col * cell_w + 8, 8), name.replace("_", " "), fill="black", font=font)
    for row, case in enumerate(review_cases):
        y = 40 + row * row_h
        draw.text((8, y), case["package"], fill="black", font=font)
        for col, name in enumerate(columns):
            with Image.open(folder / case["package"] / f"{name}.png") as image:
                sheet.paste(thumbnail(image, image_size), (col * cell_w + 8, y + 28))
    sheet.save(out / "actual-device-before-contact-sheet.png")
    report = {"capture": str(folder), "metadata_sha256": digest(folder / "cases.json"),
              "total_cases": len(cases), "capture_ok": len(review_cases),
              "valid_refine_cases": len(replay_cases),
              "sheet_precision": "actual captured device PNGs; only display resizing/checkerboard added",
              "cases": records}
    (out / "capture-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "refine-manifest.json").write_text(json.dumps({"ratios": [0, 0.25, 0.5, 0.75, 1],
        "geometries": [{"width": 192, "height": 192, "radius": 48}], "cases": replay_cases},
        ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"review_cases": len(review_cases), "valid_refine_cases": len(replay_cases),
                      "contact_sheet": str(out / "actual-device-before-contact-sheet.png"),
                      "report": str(out / "capture-validation.json")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
