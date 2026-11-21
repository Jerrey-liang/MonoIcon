#!/usr/bin/env python3
"""Replay real RGBA captures through current pure Kotlin mask/border code offline.

Only PNG decoding and review compositions use Pillow. Exact processor bytes and
border geometry come from compiling the repository Kotlin sources unchanged.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCES = (
    ROOT / "app/src/main/java/com/jerrey/monoicon/theme/mask/GeneratedMaskProcessor.kt",
    ROOT / "app/src/main/java/com/jerrey/monoicon/theme/render/GeneratedIconBorder.kt",
    Path(__file__).with_name("ReplayMain.kt"),
)
MAIN = "com.jerrey.monoicon.tools.iconregression.ReplayMainKt"
TONE_PALETTE_SOURCE = ROOT / "app/src/main/java/com/jerrey/monoicon/theme/render/GeneratedTonePalette.kt"
PALETTES = {
    "light": ((44, 32, 49), (246, 218, 253)),
    "dark": ((236, 213, 242), (53, 38, 60)),
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def find_jar(cache: Path, group: str, artifact: str, version: str | None = None) -> Path:
    base = cache / group / artifact
    versions = [base / version] if version else sorted(base.glob("*"), reverse=True)
    for version_dir in versions:
        matches = sorted(version_dir.glob(f"*/{artifact}-*.jar"))
        if matches:
            return matches[0]
    raise FileNotFoundError(f"No cached {group}:{artifact}:{version or '*'} jar in {cache}")


def compiler(out: Path, java_arg: str | None, cache_arg: str | None,
             sources: tuple[Path, ...]) -> tuple[Path, str, str]:
    cache = Path(cache_arg) if cache_arg else Path.home() / ".gradle/caches/modules-2/files-2.1"
    java_candidates = [
        java_arg,
        str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if "JAVA_HOME" in os.environ else None,
        shutil.which("java"),
        r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe",
    ]
    java = next((Path(p) for p in java_candidates if p and Path(p).is_file()), None)
    if java is None:
        raise FileNotFoundError("Pass --java pointing to a JDK java executable")
    compiler_jar = find_jar(cache, "org.jetbrains.kotlin", "kotlin-compiler-embeddable")
    version = compiler_jar.parents[1].name
    stdlib = find_jar(cache, "org.jetbrains.kotlin", "kotlin-stdlib", version)
    annotations = find_jar(cache, "org.jetbrains", "annotations")
    jars = [compiler_jar, stdlib, annotations]
    for group, artifact, dep_version in [
        ("org.jetbrains.kotlin", "kotlin-script-runtime", version),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    ]:
        try:
            jars.append(find_jar(cache, group, artifact, dep_version))
        except FileNotFoundError:
            pass
    fingerprint = hashlib.sha256((version + "".join(digest(p) for p in sources)).encode()).hexdigest()
    classes = out / "jvm" / fingerprint
    classes.mkdir(parents=True, exist_ok=True)
    runtime_cp = os.pathsep.join(map(str, [classes, stdlib, annotations]))
    if not (classes / "compiled.ok").exists():
        command = [str(java), "-Xmx512m", "-cp", os.pathsep.join(map(str, jars)),
                   "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                   "-jvm-target", "17", "-classpath", os.pathsep.join(map(str, [stdlib, annotations])),
                   "-d", str(classes), *map(str, sources)]
        subprocess.run(command, check=True, cwd=ROOT)
        (classes / "compiled.ok").write_text(version, encoding="utf-8")
    return java, runtime_cp, version


def alpha_png(path: Path, channel: str = "alpha") -> Image.Image:
    image = Image.open(path)
    if channel == "luminance":
        return image.convert("L")
    if channel != "alpha":
        raise ValueError(f"Invalid alpha channel {channel!r}; use alpha or luminance")
    if "A" not in image.getbands():
        raise ValueError(f"{path} has no alpha; set baseline_channel or mask_channel to luminance")
    return image.getchannel("A")


def bounds(data: bytes, width: int, threshold: int) -> list[int] | None:
    points = [i for i, a in enumerate(data) if a >= threshold]
    if not points:
        return None
    return [min(i % width for i in points), min(i // width for i in points),
            max(i % width for i in points) + 1, max(i // width for i in points) + 1]


def compose(mask: Image.Image, size: tuple[int, int], radius: float, scale: float,
            palette: str) -> Image.Image:
    """Review preview of SRC_ATOP. Pillow AA is not Android Skia AA."""
    foreground, background = PALETTES[palette]
    w, h = size
    zoom = min(w / mask.width, h / mask.height) * scale
    scaled = mask.resize((max(1, round(mask.width * zoom)), max(1, round(mask.height * zoom))),
                         Image.Resampling.BILINEAR)
    glyph = Image.new("L", size)
    glyph.paste(scaled, ((w - scaled.width) // 2, (h - scaled.height) // 2))
    preview = Image.composite(Image.new("RGB", size, foreground),
                              Image.new("RGB", size, background), glyph).convert("RGBA")
    supersample = 4
    plate = Image.new("L", (w * supersample, h * supersample))
    ImageDraw.Draw(plate).rounded_rectangle((0, 0, w * supersample - 1, h * supersample - 1),
                                            min(radius, min(w, h) / 2) * supersample, fill=255)
    preview.putalpha(plate.resize(size, Image.Resampling.LANCZOS))
    return preview


def read_argb(path: Path, size: tuple[int, int]) -> Image.Image:
    rgba = bytearray()
    for (pixel,) in struct.iter_unpack(">I", path.read_bytes()):
        rgba.extend(((pixel >> 16) & 255, (pixel >> 8) & 255, pixel & 255, pixel >> 24))
    return Image.frombytes("RGBA", size, bytes(rgba))


def framework_clip(size: tuple[int, int]) -> Image.Image:
    """Documented AOSP default cubic squircle, sampled in Pillow (approximate)."""
    w, h = size
    multiplier = 4
    curves = [((50, 0), (10, 0), (0, 10), (0, 50)),
              ((0, 50), (0, 90), (10, 100), (50, 100)),
              ((50, 100), (90, 100), (100, 90), (100, 50)),
              ((100, 50), (100, 10), (90, 0), (50, 0))]
    points = []
    for curve in curves:
        for step in range(101):
            t = step / 100
            coefficients = ((1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3)
            points.append((sum(c * point[0] for c, point in zip(coefficients, curve)) * w * multiplier / 100,
                           sum(c * point[1] for c, point in zip(coefficients, curve)) * h * multiplier / 100))
    image = Image.new("L", (w * multiplier, h * multiplier))
    ImageDraw.Draw(image).polygon(points, fill=255)
    return image.resize(size, Image.Resampling.LANCZOS)


def aosp_geometry(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Center 2/3 viewport plus default framework clip; Pillow/Skia AA differs."""
    image = image.transform(size, Image.Transform.EXTENT,
                            (image.width / 6, image.height / 6, image.width * 5 / 6, image.height * 5 / 6),
                            Image.Resampling.BILINEAR)
    alpha = bytes((a * c + 127) // 255 for a, c in zip(image.getchannel("A").tobytes(),
                                                     framework_clip(size).tobytes()))
    image.putalpha(Image.frombytes("L", size, alpha))
    return image


def compose_tonal(image: Image.Image, size: tuple[int, int], radius: float, scale: float,
                  palette: str) -> Image.Image:
    """Compose exact production-mapped RGBA using approximate Pillow geometry."""
    w, h = size
    zoom = min(w / image.width, h / image.height) * scale
    scaled = image.resize((max(1, round(image.width * zoom)), max(1, round(image.height * zoom))),
                          Image.Resampling.BILINEAR)
    glyph = Image.new("RGBA", size)
    glyph.paste(scaled, ((w - scaled.width) // 2, (h - scaled.height) // 2))
    plate = Image.new("RGBA", size, (*PALETTES[palette][1], 255))
    preview = Image.alpha_composite(plate, glyph)
    preview.putalpha(compose(Image.new("L", size), size, radius, 1, palette).getchannel("A"))
    return preview


def thumbnail(image: Image.Image, size: int = 112) -> Image.Image:
    output = Image.new("RGB", (size, size), (220, 220, 220))
    draw = ImageDraw.Draw(output)
    for y in range(0, size, 8):
        for x in range(0, size, 8):
            if ((x + y) // 8) % 2:
                draw.rectangle((x, y, x + 7, y + 7), fill=(245, 245, 245))
    image = image.convert("RGBA")
    fit = min((size - 8) / image.width, (size - 8) / image.height)
    image = image.resize((max(1, round(image.width * fit)), max(1, round(image.height * fit))),
                         Image.Resampling.LANCZOS)
    output.paste(image, ((size - image.width) // 2, (size - image.height) // 2), image)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--java")
    parser.add_argument("--gradle-cache")
    parser.add_argument("--source-dir", type=Path,
                        help="Snapshot folder containing GeneratedMaskProcessor.kt and GeneratedIconBorder.kt")
    args = parser.parse_args()
    manifest_path = args.manifest.resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    cases = manifest["cases"]
    if not cases:
        raise ValueError("Manifest has no cases")
    out = (args.out or manifest_path.parent / "replay-output").resolve()
    out.mkdir(parents=True, exist_ok=True)
    sources = SOURCES if args.source_dir is None else (
        args.source_dir.resolve() / "GeneratedMaskProcessor.kt",
        args.source_dir.resolve() / "GeneratedIconBorder.kt", SOURCES[2])
    if any(case.get("mode") == "tonal" for case in cases):
        sources = (*sources, TONE_PALETTE_SOURCE)
    ratios = manifest.get("ratios", [0, 0.25, 0.5, 0.75, 1])
    geometries = manifest.get("geometries", [{"width": 200, "height": 200, "radius": 50}])
    input_dir = out / "inputs"
    input_dir.mkdir(exist_ok=True)
    requests = []
    prepared = []
    for i, case in enumerate(cases):
        id_ = f"{i:03d}"
        source_path = (manifest_path.parent / case["source"]).resolve()
        source = Image.open(source_path).convert("RGBA")
        if "crop" in case:
            source = source.crop(tuple(case["crop"]))
        w, h = source.size
        source.save(input_dir / f"{id_}-source.png")
        pixel_path = input_dir / f"{id_}.argb"
        with pixel_path.open("wb") as output:
            for r, g, b, a in struct.iter_unpack("BBBB", source.tobytes()):
                output.write(struct.pack(">I", (a << 24) | (r << 16) | (g << 8) | b))
        mode = case.get("mode", "extract")
        baseline_path = "-"
        hashes = {"source": digest(source_path)}
        if mode == "refine":
            original_baseline = (manifest_path.parent / case["baseline"]).resolve()
            baseline = alpha_png(original_baseline, case.get("baseline_channel", "alpha"))
            if baseline.size != source.size:
                raise ValueError(f"{case['name']}: baseline dimensions differ from source")
            baseline_path = str(input_dir / f"{id_}-baseline.alpha")
            Path(baseline_path).write_bytes(baseline.tobytes())
            hashes["baseline"] = digest(original_baseline)
        elif mode == "mask":
            source = alpha_png(source_path, case.get("mask_channel", "alpha"))
            if "crop" in case:
                source = source.crop(tuple(case["crop"]))
            with pixel_path.open("wb") as output:
                for a in source.tobytes():
                    output.write(struct.pack(">I", (a << 24) | 0xFFFFFF))
        border_path = "-"
        border_size = (w, h)
        border_image = None
        if "border_mask" in case:
            original_border = (manifest_path.parent / case["border_mask"]).resolve()
            border_image = alpha_png(original_border, case.get("border_mask_channel", "alpha"))
            border_size = border_image.size
            border_path = str(input_dir / f"{id_}-border.alpha")
            Path(border_path).write_bytes(border_image.tobytes())
            hashes["border_mask"] = digest(original_border)
        mask_path = out / f"{id_}-mask.alpha"
        requests.append([id_, w, h, mode, str(bool(case.get("inverted", False))).lower(), pixel_path,
                         baseline_path, mask_path, border_path, *border_size])
        prepared.append((case, id_, source_path, source, mask_path, border_image, hashes))
    request_path = input_dir / "requests.tsv"
    request_path.write_text("".join("\t".join(map(str, row)) + "\n" for row in requests), encoding="utf-8")
    geometry_path = input_dir / "geometries.tsv"
    geometry_path.write_text("".join(f"{g['width']}\t{g['height']}\t{g['radius']}\t{r}\n"
                                      for g in geometries for r in ratios), encoding="utf-8")
    java, runtime_cp, kotlin_version = compiler(out, args.java, args.gradle_cache, sources)
    scales_path = out / "scales.tsv"
    subprocess.run([str(java), "-Djava.awt.headless=true", "-cp", runtime_cp, MAIN,
                    str(request_path), str(geometry_path), str(scales_path)], check=True, cwd=ROOT)
    with scales_path.open(encoding="utf-8") as file:
        scales = list(csv.DictReader(file, delimiter="\t"))
    results = []
    failures = []
    sheet_columns = ["source", "exact mask", "light 0%", "light 50%", "light 100%",
                     "dark 0%", "dark 50%", "dark 100%"]
    cell_w, row_h = 128, 144
    sheet = Image.new("RGB", (len(sheet_columns) * cell_w, 40 + len(cases) * row_h), "white")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 11) if Path("C:/Windows/Fonts/msyh.ttc").exists() else ImageFont.load_default()
    for col, label in enumerate(sheet_columns):
        draw.text((col * cell_w + 4, 8), label, fill="black", font=font)
    for i, (case, id_, source_path, source, mask_path, border_image, hashes) in enumerate(prepared):
        data = mask_path.read_bytes()
        mask = Image.frombytes("L", source.size, data)
        rgba_mask = Image.new("RGBA", mask.size, "white")
        rgba_mask.putalpha(mask)
        rgba_mask.save(out / f"{id_}-mask.png")
        rows = [r for r in scales if r["case"] == id_]
        invariant = all(r["ratio_invariant"] == "true" for r in rows)
        probes = []
        for probe in case.get("probes", []):
            actual = mask.getpixel((probe["x"], probe["y"]))
            passed = probe.get("min_alpha", 0) <= actual <= probe.get("max_alpha", 255)
            probes.append({**probe, "actual_alpha": actual, "passed": passed})
            if not passed:
                failures.append(f"{case['name']}: alpha probe ({probe['x']},{probe['y']}) = {actual}")
        if not invariant:
            failures.append(f"{case['name']}: source mask changes with border ratio")
        metrics = {
            "name": case["name"], "id": id_, "mode": case.get("mode", "extract"),
            "size": list(mask.size), "input_sha256": hashes, "mask_sha256": digest(mask_path),
            "nonzero_pixels": sum(a != 0 for a in data),
            "visible_pixels": sum(a >= 32 for a in data),
            "opaque_pixels": sum(a >= 224 for a in data),
            "alpha_mass": sum(data) / 255, "bounds_alpha32": bounds(data, mask.width, 32),
            "ratio_invariant": invariant, "probes": probes,
            "border_input": "captured_final_mask" if border_image else "processor_output",
            "geometry": [{**r, "scale": float(r["scale"])} for r in rows],
        }
        tonal_mapped = {}
        if case.get("mode") == "tonal":
            tonal = read_argb(mask_path.with_suffix(".argb"), source.size)
            tonal.save(out / f"{id_}-tonal-preclip-rgba.png")
            source_alpha = source.getchannel("A").tobytes()
            alpha_errors = [abs(a - b) for a, b in zip(source_alpha, data)]
            pixel_tuples = list(struct.iter_unpack("BBBB", tonal.tobytes()))
            source_pixels = list(struct.iter_unpack("BBBB", source.tobytes()))
            neutral_errors = sum(a > 0 and not (r == g == b) for r, g, b, a in pixel_tuples)
            tone_pairs = sorted(((r * 2126 + g * 7152 + b * 722 + 5000) // 10000, tone[0])
                                for (r, g, b, a), tone in zip(source_pixels, pixel_tuples) if a > 0)
            inversions = sum(after[1] < before[1] for before, after in zip(tone_pairs, tone_pairs[1:]))
            metrics["tone_coverage_separation"] = {
                "source_alpha_changed_pixels": sum(e != 0 for e in alpha_errors),
                "max_alpha_error": max(alpha_errors), "nonneutral_rgb_pixels": neutral_errors,
                "tone_order_inversions": inversions,
                "tone_range_visible": [min((p[0] for p in pixel_tuples if p[3] > 0), default=0),
                                       max((p[0] for p in pixel_tuples if p[3] > 0), default=0)],
                "pure_white_source_tones": sorted({tone[0] for src, tone in zip(source_pixels, pixel_tuples)
                                                    if src[:3] == (255, 255, 255) and src[3] > 0}),
            }
            if any(alpha_errors) or neutral_errors or inversions:
                failures.append(f"{case['name']}: tonal alpha/neutrality/order invariant failed")
            geometry_size = tuple(case.get("aosp_output_size", [192, 192]))
            geometry_tonal = aosp_geometry(tonal, geometry_size)
            geometry_tonal.save(out / f"{id_}-tonal-aosp-preview-rgba.png")
            for palette in PALETTES:
                mapped = read_argb(mask_path.with_suffix(f".{palette}.argb"), source.size)
                mapped.save(out / f"{id_}-tonal-{palette}-preclip-rgba.png")
                if mapped.getchannel("A").tobytes() != source_alpha:
                    failures.append(f"{case['name']}: {palette} palette mapping changes source alpha")
                tonal_mapped[palette] = aosp_geometry(mapped, geometry_size)
                tonal_mapped[palette].save(out / f"{id_}-tonal-{palette}-aosp-preview-rgba.png")
        if "compare_mask" in case:
            expected_path = (manifest_path.parent / case["compare_mask"]).resolve()
            expected = alpha_png(expected_path, case.get("compare_mask_channel", "alpha"))
            if expected.size != mask.size:
                raise ValueError(f"{case['name']}: compare-mask dimensions differ")
            differences = [abs(a - b) for a, b in zip(data, expected.tobytes())]
            metrics["comparison"] = {"sha256": digest(expected_path),
                                     "changed_pixels": sum(d != 0 for d in differences),
                                     "max_alpha_error": max(differences),
                                     "mean_alpha_error": sum(differences) / len(differences)}
            if case.get("require_exact_comparison", False) and any(differences):
                failures.append(f"{case['name']}: output differs from captured/reference alpha")
        results.append(metrics)
        first_geometry = geometries[0]
        display_mask = border_image or mask
        if "preview_mask" in case:
            display_mask = alpha_png((manifest_path.parent / case["preview_mask"]).resolve(),
                                      case.get("preview_mask_channel", "alpha"))
        metrics["preview_mode"] = case.get("preview_mode", "tonal" if tonal_mapped else "alpha")
        images = [Image.open(input_dir / f"{id_}-source.png"), rgba_mask]
        for palette in ("light", "dark"):
            for ratio in (0, 0.5, 1):
                matching = [r for r in rows if float(r["width"]) == first_geometry["width"]
                            and float(r["height"]) == first_geometry["height"]
                            and float(r["radius"]) == first_geometry["radius"]
                            and abs(float(r["ratio"]) - ratio) < 0.00001]
                if matching:
                    use_tonal = bool(tonal_mapped) and case.get("preview_mode") != "alpha"
                    composer = compose_tonal if use_tonal else compose
                    image_input = tonal_mapped[palette] if use_tonal else display_mask
                    preview = composer(image_input, (first_geometry["width"], first_geometry["height"]),
                                       first_geometry["radius"], float(matching[0]["scale"]), palette)
                    if "preview_canvas_size" in manifest:
                        canvas_size = tuple(manifest["preview_canvas_size"])
                        canvas = Image.new("RGBA", canvas_size)
                        canvas.paste(preview, ((canvas.width - preview.width) // 2,
                                               (canvas.height - preview.height) // 2))
                        preview = canvas
                    preview.save(out / f"{id_}-{palette}-{round(ratio * 100):03d}-preview.png")
                    images.append(preview)
                else:
                    images.append(Image.new("RGBA", (112, 112)))
        y = 40 + i * row_h
        draw.text((4, y), f"{id_} {case['name']}", fill="black", font=font)
        for col, image in enumerate(images):
            sheet.paste(thumbnail(image), (col * cell_w + 4, y + 23))
    sheet.save(out / "contact-sheet.png")
    report = {
        "manifest": str(manifest_path), "manifest_sha256": digest(manifest_path),
        "kotlin_version": kotlin_version,
        "source_sha256": {str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else str(p): digest(p)
                          for p in sources},
        "precision": {"mask": "exact production Kotlin pixels",
                      "border_scale": "exact production Kotlin geometry for recorded border input",
                      "tonal_rgba_and_palette": "exact production Kotlin tonal/map pixels before geometry",
                      "preview": "approximate Pillow bilinear/antialias composition and documented AOSP squircle; not Android Skia"},
        "cases": results, "failures": failures,
    }
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(out), "cases": len(results), "failures": failures,
                      "contact_sheet": str(out / "contact-sheet.png")}, ensure_ascii=False))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
