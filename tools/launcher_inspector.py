"""Offline Launcher ELF intake. Binary identity is not a Hook ABI verdict.

Only Python's standard library is required. Supports ELF64 little-endian
AArch64, including stripped images without a section table. APK entries are
read by exact name and written to fixed destinations, never extractall().
"""

import argparse
import hashlib
import json
import lzma
from pathlib import Path
import struct
import sys
import zipfile
import zlib


LIBRARIES = ("libapp.so", "libapp_launcher.so")
ELF_DIRECTORY = Path("lib/arm64-v8a")
PT_LOAD, PT_NOTE, PF_X = 1, 4, 1
SCHEMA_VERSION = 1
ABI_NOTICE = (
    "Binary identity only: matching GNU Build ID, Android Note or executable "
    "segment hashes cannot independently prove Hook ABI compatibility. "
    "No new Launcher support or runtime validation is enabled by this report."
)


class InspectorError(ValueError):
    """An invalid or unsupported input with a user-readable explanation."""


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _range(data, offset, size, label):
    if offset < 0 or size < 0 or offset + size > len(data):
        raise InspectorError(f"{label}: range {offset:#x}+{size:#x} exceeds file size {len(data):#x}")


def _android_note(description):
    # .note.android.ident: uint32 API, optional char[64] NDK version/build.
    # Future/unknown extensions are retained in the raw note, not guessed.
    if len(description) < 4:
        return None

    def text_field(start):
        if len(description) < start + 64:
            return None
        try:
            return description[start:start + 64].split(b"\0", 1)[0].decode("ascii")
        except UnicodeDecodeError:
            return None

    return {"abi_api": struct.unpack_from("<I", description)[0],
            "ndk_version": text_field(4), "ndk_build": text_field(68)}


def _parse_notes(data, offset, size, origin, alignment=4):
    # GNU property notes can use 8-byte alignment on AArch64. ELF note
    # alignment includes the 12-byte header, not just the owner name.
    alignment = 4 if alignment in (0, 1) else alignment
    if alignment not in (4, 8):
        raise InspectorError(f"{origin}: unsupported Note alignment {alignment}")
    end = offset + size
    cursor = offset
    result = []
    while cursor < end:
        if end - cursor < 12:
            # A segment may end with alignment padding, but not partial data.
            if any(data[cursor:end]):
                raise InspectorError(f"{origin}: truncated Note header at {cursor:#x}")
            break
        namesz, descsz, note_type = struct.unpack_from("<III", data, cursor)
        name_start = cursor + 12
        desc_start = cursor + ((12 + namesz + alignment - 1) & ~(alignment - 1))
        next_note = desc_start + ((descsz + alignment - 1) & ~(alignment - 1))
        if next_note > end:
            raise InspectorError(f"{origin}: truncated Note name/description at {cursor:#x}")
        name = data[name_start:name_start + namesz]
        description = data[desc_start:desc_start + descsz]
        note = {"offset": cursor, "origin": origin, "alignment": alignment,
                "name": name.rstrip(b"\0").decode("utf-8", errors="backslashreplace"),
                "name_hex": name.hex(), "type": note_type,
                "description_hex": description.hex()}
        if name == b"GNU\0" and note_type == 3 and description:
            note["gnu_build_id"] = description.hex()
        elif name == b"Android\0" and note_type == 1:
            note["android_note"] = _android_note(description)
        result.append(note)
        cursor = next_note
    return result


def parse_elf(data, source="<bytes>"):
    """Return JSON-ready ELF facts. Hashes cover file bytes, not BSS memory."""
    if data[:4] != b"\x7fELF":
        raise InspectorError(f"{source}: not an ELF file")
    if len(data) < 16:
        raise InspectorError(f"{source}: truncated ELF identification")
    if data[4] != 2 or data[5] != 1:
        raise InspectorError(f"{source}: unsupported ELF format (Class={data[4]}, Data={data[5]}); expected ELF64 little-endian")
    if data[6] != 1:
        raise InspectorError(f"{source}: unsupported ELF identification version {data[6]}")
    _range(data, 0, 64, f"{source}: ELF header")
    fields = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    (elf_type, machine, version, entry, phoff, shoff, flags, ehsize,
     phentsize, phnum, shentsize, shnum, shstrndx) = fields
    if machine != 183:
        raise InspectorError(f"{source}: unsupported ELF machine {machine}; expected AArch64 (183)")
    if version != 1 or ehsize != 64:
        raise InspectorError(f"{source}: invalid ELF header version/size ({version}/{ehsize})")
    if phnum == 0xffff or shstrndx == 0xffff or (shoff and not shnum):
        raise InspectorError(f"{source}: unsupported extended ELF table numbering")
    if phnum and (phentsize != 56 or phoff < ehsize):
        raise InspectorError(f"{source}: invalid program header table offset/entry size")
    if shnum and (shentsize != 64 or shoff < ehsize or shstrndx >= shnum):
        raise InspectorError(f"{source}: invalid section header table offset/entry size/string index")
    if not shnum and shstrndx:
        raise InspectorError(f"{source}: section string index without a section table")
    _range(data, phoff, phnum * phentsize, f"{source}: program header table")
    _range(data, shoff, shnum * shentsize, f"{source}: section header table")
    elf = {"class": "ELF64", "endianness": "little", "machine": machine,
           "machine_name": "AArch64", "type": elf_type,
           "type_name": {0: "ET_NONE", 1: "ET_REL", 2: "ET_EXEC", 3: "ET_DYN", 4: "ET_CORE"}.get(elf_type, "UNKNOWN"),
           "version": version, "os_abi": data[7], "abi_version": data[8],
           "entry": entry, "flags": flags, "header_size": ehsize,
           "program_header_offset": phoff, "program_header_entry_size": phentsize,
           "program_header_count": phnum, "section_header_offset": shoff,
           "section_header_entry_size": shentsize, "section_header_count": shnum,
           "section_name_table_index": shstrndx}
    headers, loads, notes = [], [], []
    for index in range(phnum):
        values = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phentsize)
        ptype, pflags, offset, vaddr, paddr, filesz, memsz, align = values
        label = f"{source}: program header {index}"
        _range(data, offset, filesz, label)
        header = dict(zip(("type", "flags", "offset", "vaddr", "paddr", "filesz", "memsz", "align"), values))
        headers.append(header)
        if ptype == PT_LOAD:
            if filesz > memsz:
                raise InspectorError(f"{label}: PT_LOAD filesz exceeds memsz")
            if align not in (0, 1) and (align & (align - 1) or offset % align != vaddr % align):
                raise InspectorError(f"{label}: invalid PT_LOAD alignment")
            segment = {key: value for key, value in header.items() if key != "type"}
            segment["sha256"] = hashlib.sha256(memoryview(data)[offset:offset + filesz]).hexdigest() if pflags & PF_X else None
            loads.append(segment)
        elif ptype == PT_NOTE:
            notes.extend(_parse_notes(data, offset, filesz, label, align))

    sections = []
    for index in range(shnum):
        values = struct.unpack_from("<IIQQQQIIQQ", data, shoff + index * shentsize)
        section = dict(zip(("name_offset", "type", "flags", "addr", "offset", "size", "link", "info", "align", "entry_size"), values))
        if section["type"] not in (0, 8):  # SHT_NULL / SHT_NOBITS have no file contents.
            _range(data, section["offset"], section["size"], f"{source}: section {index}")
        if section["type"] == 7:  # SHT_NOTE, also covers notes absent from PT_NOTE.
            notes.extend(_parse_notes(data, section["offset"], section["size"],
                                      f"{source}: section {index}", section["align"]))
        sections.append(section)
    if shstrndx:
        strings = sections[shstrndx]
        if strings["type"] != 3:
            raise InspectorError(f"{source}: section name table is not SHT_STRTAB")
        names = data[strings["offset"]:strings["offset"] + strings["size"]]
        for section in sections:
            start = section["name_offset"]
            stop = names.find(b"\0", start)
            if start >= len(names) or stop < 0:
                raise InspectorError(f"{source}: invalid section name offset/string")
            section["name"] = names[start:stop].decode("utf-8", errors="backslashreplace")
    # PT_NOTE and SHT_NOTE commonly describe the same bytes. Keep one raw record.
    notes = list({note["offset"]: note for note in notes}.values())
    notes.sort(key=lambda note: note["offset"])
    build_ids = sorted({note["gnu_build_id"] for note in notes if "gnu_build_id" in note})
    android = [note["android_note"] for note in notes if note.get("android_note") is not None]
    return {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data), "elf": elf,
            "gnu_build_id": build_ids[0] if len(build_ids) == 1 else None,
            "android_note": android[0] if android and all(item == android[0] for item in android) else None,
            "notes": notes, "program_headers": headers, "load_segments": loads,
            "section_headers": sections}


def _input_libraries(input_path):
    if input_path.is_dir():
        # Accept a flat directory, an ABI directory, or the inspect output root.
        for directory in (input_path, input_path / "arm64-v8a", input_path / ELF_DIRECTORY):
            if all((directory / name).is_file() for name in LIBRARIES):
                return {name: (directory / name).read_bytes() for name in LIBRARIES}, {
                    "kind": "directory", "path": str(input_path.resolve()),
                    "elf_directory": str(directory.resolve()), "sha256": None, "size": None}
        raise InspectorError(f"{input_path}: missing target ELF files; both libapp.so and libapp_launcher.so are required in the same directory")
    if not input_path.is_file():
        raise InspectorError(f"{input_path}: input does not exist or is not a file/directory")
    try:
        with zipfile.ZipFile(input_path) as archive:
            binaries = {}
            for name in LIBRARIES:
                entry = (ELF_DIRECTORY / name).as_posix()
                matches = [info for info in archive.infolist() if info.filename == entry]
                if not matches:
                    raise InspectorError(f"{input_path}: missing target APK entry {entry}")
                if len(matches) != 1:
                    raise InspectorError(f"{input_path}: duplicate target APK entry {entry}")
                binaries[name] = archive.read(matches[0])
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError,
            zlib.error, lzma.LZMAError, UnicodeError) as error:
        raise InspectorError(f"{input_path}: invalid or unsupported APK/ZIP: {error}") from error
    return binaries, {"kind": "apk", "path": str(input_path.resolve()),
                      "sha256": sha256_file(input_path), "size": input_path.stat().st_size}


def inspect(input_path, out_dir):
    input_path, out_dir = Path(input_path), Path(out_dir)
    binaries, source = _input_libraries(input_path)
    libraries = {name: parse_elf(data, name) for name, data in binaries.items()}
    manifest = {"schema_version": SCHEMA_VERSION, "purpose": "offline_analysis_only",
                "compatibility": "NOT_ASSESSED", "input": source,
                "apk": {"sha256": source["sha256"], "size": source["size"]},
                "environment": {"device": None, "os": None, "android": None},
                "launcher": {"package_name": None, "version_name": None, "version_code": None},
                "libraries": libraries, "evidence": {}}
    for name, facts in libraries.items():
        facts["path"] = (ELF_DIRECTORY / name).as_posix()
        manifest["evidence"][f"libraries.{name}"] = {
            "status": "verified_from_input", "source": source["path"],
            "entry": facts["path"] if source["kind"] == "apk" else str(Path(source["elf_directory"]) / name),
            "method": "SHA256 and ELF header/PT_NOTE/SHT_NOTE parsing"}
    for field in ("environment", "launcher"):
        manifest["evidence"][field] = {"status": "unknown", "source": None,
                                      "reason": "Not inferred from filenames or native ELF toolchain notes; supply independent evidence."}
    manifest["evidence"]["apk"] = {
        "status": "verified_from_input" if source["kind"] == "apk" else "unknown",
        "source": source["path"] if source["kind"] == "apk" else None,
        "method": "SHA256 and file size" if source["kind"] == "apk" else None}
    # Validate everything before writing. Refuse collisions, including the source
    # directory itself; intake must never overwrite an APK or existing evidence.
    destinations = [out_dir / ELF_DIRECTORY / name for name in LIBRARIES]
    destinations += [out_dir / "manifest.json", out_dir / "report.md"]
    for destination in destinations:
        if destination.exists() or destination.is_symlink():
            raise InspectorError(f"{destination}: output already exists; choose a new output directory")
    (out_dir / ELF_DIRECTORY).mkdir(parents=True, exist_ok=True)
    for name, data in binaries.items():
        (out_dir / ELF_DIRECTORY / name).write_bytes(data)
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (out_dir / "report.md").write_text(inspect_report(manifest), encoding="utf-8")
    return manifest


def inspect_report(manifest):
    lines = ["# Launcher inspection", "", ABI_NOTICE, "",
             f"Input: `{manifest['input']['path']}`", "",
             f"APK SHA256: `{manifest['apk']['sha256']}`", "",
             "Device, OS, Android and Launcher package/version metadata remain null until independently evidenced.", ""]
    for name, facts in manifest["libraries"].items():
        elf = facts["elf"]
        lines += [f"## {name}", "", f"- SHA256: `{facts['sha256']}`",
                  f"- Size: {facts['size']} bytes",
                  f"- ELF: {elf['class']} / {elf['endianness']} / {elf['machine_name']} ({elf['machine']}) / {elf['type_name']} ({elf['type']})",
                  f"- GNU Build ID: `{facts['gnu_build_id']}`",
                  f"- Android Note: `{json.dumps(facts['android_note'], ensure_ascii=False)}`", "",
                  "| PT_LOAD | Offset | Vaddr | Paddr | File size | Memory size | Flags | Alignment | Executable SHA256 |",
                  "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for index, segment in enumerate(facts["load_segments"]):
            values = [index, hex(segment["offset"]), hex(segment["vaddr"]), hex(segment["paddr"]),
                      segment["filesz"], segment["memsz"], segment["flags"], segment["align"], segment["sha256"]]
            lines.append("| " + " | ".join(str(value) for value in values) + " |")
        lines += ["", "Segment SHA256 covers p_filesz bytes at p_offset; PF_X=1. BSS/zero-fill is excluded.", "",
                  "### Raw Notes", ""]
        if not facts["notes"]:
            lines += ["No Notes found in PT_NOTE or SHT_NOTE.", ""]
        for note in facts["notes"]:
            lines += [f"- Offset `{note['offset']:#x}`, name `{note['name']}`, name_hex `{note['name_hex']}`, type `{note['type']}`, description_hex `{note['description_hex']}`"]
        lines += ["", "ELF header, all program/section headers and raw Notes are retained in manifest.json.", ""]
    return "\n".join(lines)


def _hash_present(value):
    return _hex_present(value) and len(value) == 64


def _hex_present(value):
    return (isinstance(value, str) and bool(value) and len(value) % 2 == 0
            and all(char in "0123456789abcdefABCDEF" for char in value))


def _validate_manifest(manifest, label):
    if not isinstance(manifest, dict) or manifest.get("schema_version") != SCHEMA_VERSION:
        raise InspectorError(f"{label}: expected Launcher manifest schema_version {SCHEMA_VERSION}")
    if not isinstance(manifest.get("libraries"), dict):
        raise InspectorError(f"{label}: manifest libraries must be an object")
    # Missing/null facts are allowed in evidence archives. Malformed facts are
    # errors, rather than candidates for an identity match or a Python traceback.
    for name in LIBRARIES:
        facts = manifest["libraries"].get(name)
        if facts is None:
            continue
        prefix = f"{label}: libraries.{name}"
        if not isinstance(facts, dict):
            raise InspectorError(f"{prefix}: expected an object or null")
        for field, valid in (("sha256", _hash_present), ("gnu_build_id", _hex_present)):
            if facts.get(field) is not None and not valid(facts[field]):
                raise InspectorError(f"{prefix}.{field}: invalid hexadecimal identity")
        for field, expected in (("elf", dict), ("android_note", dict), ("notes", list),
                                ("load_segments", list), ("program_headers", list), ("section_headers", list)):
            value = facts.get(field)
            if value is not None and not isinstance(value, expected):
                raise InspectorError(f"{prefix}.{field}: expected {expected.__name__} or null")
            if isinstance(value, list) and any(not isinstance(item, dict) for item in value):
                raise InspectorError(f"{prefix}.{field}: entries must be objects")


def _raw_notes(facts):
    notes = facts.get("notes")
    if notes is None:
        return None
    return [{key: note.get(key) for key in ("offset", "name_hex", "type", "description_hex")}
            for note in notes]


def _executable_segments(facts):
    segments = facts.get("load_segments")
    if not isinstance(segments, list):
        return None
    keys = ("offset", "vaddr", "paddr", "filesz", "memsz", "flags", "align", "sha256")
    executable = []
    for segment in segments:
        if not isinstance(segment, dict) or type(segment.get("flags")) is not int or segment["flags"] < 0:
            return None
        if segment["flags"] & PF_X:
            if (not all(type(segment.get(key)) is int and segment[key] >= 0 for key in keys[:-1])
                    or not _hash_present(segment.get("sha256"))):
                return None
            executable.append({key: segment[key] for key in keys})
            executable[-1]["sha256"] = segment["sha256"].lower()
    # An explicitly empty set is different from missing segment evidence: a
    # removed executable segment (or cleared PF_X) must still be reported.
    return executable


def _compare_library(baseline, candidate):
    if not isinstance(baseline, dict) or not isinstance(candidate, dict):
        return {"classification": "UNKNOWN_OR_INCOMPLETE", "reason": "A target library is missing.", "differences": [
            {"field": "library", "baseline": baseline, "candidate": candidate}]}
    fields = ("sha256", "size", "gnu_build_id", "android_note", "elf",
              "program_headers", "load_segments", "section_headers")
    differences = [{"field": field, "baseline": baseline.get(field), "candidate": candidate.get(field)}
                   for field in fields if baseline.get(field) != candidate.get(field)]
    # Unknown notes matter even when neither has a decoded Android/GNU identity.
    if _raw_notes(baseline) != _raw_notes(candidate):
        differences.append({"field": "notes", "baseline": _raw_notes(baseline), "candidate": _raw_notes(candidate)})
    left_hash, right_hash = baseline.get("sha256"), candidate.get("sha256")
    hashes_known = _hash_present(left_hash) and _hash_present(right_hash)
    build_ids = (baseline.get("gnu_build_id"), candidate.get("gnu_build_id"))
    same_build_id = all(_hex_present(value) for value in build_ids) and build_ids[0].lower() == build_ids[1].lower()
    left_exec, right_exec = _executable_segments(baseline), _executable_segments(candidate)
    if hashes_known and left_hash.lower() == right_hash.lower():
        # Equal hashes with contradictory parsed facts indicate invalid evidence.
        conflicting = any(item["baseline"] is not None and item["candidate"] is not None
                          for item in differences if item["field"] not in ("sha256", "gnu_build_id"))
        conflicting |= all(value is not None for value in build_ids) and not same_build_id
        classification = "UNKNOWN_OR_INCOMPLETE" if conflicting else "EXACT_BINARY_MATCH"
        reason = ("Equal file hashes conflict with recorded ELF facts." if conflicting
                  else "The full ELF SHA256 values match.")
    elif left_exec is not None and right_exec is not None and left_exec != right_exec:
        classification = "EXECUTABLE_SEGMENTS_CHANGED"
        reason = "Executable PT_LOAD bytes or mapping/flags differ. This takes precedence over a matching Build ID."
    elif hashes_known and same_build_id:
        classification = "SAME_BUILD_ID_BUT_FILE_CHANGED"
        reason = "Full ELF SHA256 differs despite a matching GNU Build ID; executable evidence may be incomplete."
    else:
        classification = "UNKNOWN_OR_INCOMPLETE"
        reason = "Available facts do not establish binary identity or a classified executable/Build ID change."
    return {"classification": classification, "reason": reason, "differences": differences}


def compare(baseline, candidate):
    """Compare manifest facts, never infer compatibility. Missing hashes != match."""
    for label, manifest in (("baseline", baseline), ("candidate", candidate)):
        _validate_manifest(manifest, label)
    libraries = {name: _compare_library(baseline["libraries"].get(name), candidate["libraries"].get(name))
                 for name in LIBRARIES}
    classes = {item["classification"] for item in libraries.values()}
    if classes == {"EXACT_BINARY_MATCH"}:
        classification = "EXACT_BINARY_MATCH"
    elif "EXECUTABLE_SEGMENTS_CHANGED" in classes:
        classification = "EXECUTABLE_SEGMENTS_CHANGED"
    elif "UNKNOWN_OR_INCOMPLETE" in classes:
        classification = "UNKNOWN_OR_INCOMPLETE"
    else:
        classification = "SAME_BUILD_ID_BUT_FILE_CHANGED"
    metadata_differences = [{"field": field, "baseline": baseline.get(field), "candidate": candidate.get(field)}
                            for field in ("apk", "environment", "launcher") if baseline.get(field) != candidate.get(field)]
    return {"classification": classification, "compatibility": "NOT_ASSESSED",
            "libraries": libraries, "metadata_differences": metadata_differences}


def compare_report(result, baseline_label="baseline", candidate_label="candidate"):
    lines = ["# Launcher comparison", "", f"Baseline: `{baseline_label}`",
             f"Candidate: `{candidate_label}`", "",
             f"Classification: **{result['classification']}**", "", ABI_NOTICE, "",
             "EXACT_BINARY_MATCH refers to both target ELF files; it does not assert the APKs or device environments are identical.", ""]
    for name, comparison in result["libraries"].items():
        lines += [f"## {name}", "", f"Classification: **{comparison['classification']}**", "",
                  comparison["reason"], ""]
        if not comparison["differences"]:
            lines += ["No differences in recorded ELF identity/structure fields.", ""]
        for difference in comparison["differences"]:
            lines += [f"### {difference['field']}", "", "```json",
                      json.dumps({"baseline": difference["baseline"], "candidate": difference["candidate"]}, indent=2, ensure_ascii=False), "```", ""]
    if result["metadata_differences"]:
        lines += ["## APK / environment / version metadata differences", "", "```json",
                  json.dumps(result["metadata_differences"], indent=2, ensure_ascii=False), "```", ""]
    return "\n".join(lines)


def _read_manifest(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (json.JSONDecodeError, UnicodeError) as error:
        raise InspectorError(f"{path}: invalid manifest JSON: {error}") from error


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    inspect_parser = commands.add_parser("inspect", help="inspect a Launcher APK or extracted native library directory")
    inspect_parser.add_argument("input", type=Path)
    inspect_parser.add_argument("--out-dir", type=Path, required=True)
    compare_parser = commands.add_parser("compare", help="compare two offline manifests; no ABI compatibility verdict")
    compare_parser.add_argument("baseline", type=Path)
    compare_parser.add_argument("candidate", type=Path)
    compare_parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "inspect":
            inspect(args.input, args.out_dir)
            print(f"Wrote {args.out_dir / 'manifest.json'} and {args.out_dir / 'report.md'}")
        else:
            result = compare(_read_manifest(args.baseline), _read_manifest(args.candidate))
            if args.out.resolve() in (args.baseline.resolve(), args.candidate.resolve()):
                raise InspectorError("comparison output must not overwrite an input manifest")
            args.out.parent.mkdir(parents=True, exist_ok=True)
            args.out.write_text(compare_report(result, args.baseline, args.candidate), encoding="utf-8")
            print(f"{result['classification']}: {args.out}")
    except (InspectorError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
