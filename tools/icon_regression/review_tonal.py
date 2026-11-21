#!/usr/bin/env python3
"""Put captured APK/before PNGs next to approximate tonal replay previews."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from replay import thumbnail


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixtures", type=Path)
    parser.add_argument("replay", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    fixture = args.fixtures.resolve()
    replay = args.replay.resolve()
    output = (args.out or replay.parent / "tonal-review").resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = json.loads((replay / "report.json").read_text(encoding="utf-8"))
    labels = ["APK original", "captured old 0%", "captured old 100%", "new light 0%", "new light 50%",
              "new light 100%", "new dark 0%", "new dark 50%", "new dark 100%"]
    cell_width, row_height, image_size = 136, 154, 128
    sheet = Image.new("RGB", (len(labels) * cell_width, 44 + row_height * len(report["cases"])), "white")
    draw = ImageDraw.Draw(sheet)
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 11) if font_path.exists() else ImageFont.load_default()
    for col, label in enumerate(labels):
        draw.text((col * cell_width + 4, 8), label, fill="black", font=font)
    for row, case in enumerate(report["cases"]):
        pkg, id_ = case["name"], case["id"]
        y = 44 + row * row_height
        note = " | captured native/silhouette alpha" if case.get("preview_mode") == "alpha" else ""
        draw.text((4, y), pkg + note, fill="black", font=font)
        paths = [fixture / pkg / f"{name}.png" for name in ("original", "before_0", "before_100")]
        paths += [replay / f"{id_}-{palette}-{ratio:03d}-preview.png"
                  for palette in ("light", "dark") for ratio in (0, 50, 100)]
        for col, path in enumerate(paths):
            with Image.open(path) as image:
                sheet.paste(thumbnail(image, image_size), (col * cell_width + 4, y + 22))
    sheet.save(output / "apk-before-vs-tonal-contact-sheet.png")
    focus = [case for case in report["cases"] if case["name"] in (
        "com.miui.gallery", "com.android.settings", "com.android.fileexplorer", "com.miui.weather2",
        "com.a7m3p9xv.t6qk2z8.app", "com.clggjv.xcjfmd.ffo")]
    focus_sheet = Image.new("RGB", (sheet.width, 44 + len(focus) * row_height), "white")
    focus_sheet.paste(sheet.crop((0, 0, sheet.width, 44)), (0, 0))
    for row, case in enumerate(focus):
        original_row = report["cases"].index(case)
        focus_sheet.paste(sheet.crop((0, 44 + original_row * row_height, sheet.width,
                                     44 + (original_row + 1) * row_height)), (0, 44 + row * row_height))
    focus_sheet.save(output / "focus-apk-before-vs-tonal.png")
    summary = {"cases": len(report["cases"]), "failures": report["failures"],
               "source_alpha_changed_pixels": sum(c["tone_coverage_separation"]["source_alpha_changed_pixels"]
                                                    for c in report["cases"]),
               "tone_order_inversions": sum(c["tone_coverage_separation"]["tone_order_inversions"]
                                             for c in report["cases"]),
               "precision": "tonal/map preclip RGBA is exact production Kotlin; new preview AOSP geometry, clipping, AA and composition are approximate Pillow",
               "scope": "APK resource captures. Desktop getIcon0 themed sources can differ; these previews do not validate Hook 7 raw-source selection or desktop size.",
               "report": str(replay / "report.json"), "sheet": str(output / "apk-before-vs-tonal-contact-sheet.png"),
               "focus_sheet": str(output / "focus-apk-before-vs-tonal.png")}
    (output / "review-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
