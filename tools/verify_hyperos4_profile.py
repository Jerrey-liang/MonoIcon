"""Read-only comparison of a MonoIcon Profile with an APK or extracted ELF files.

This checks binary evidence; the C++ parser remains the schema authority. It does
not discover safe hook points or generate instructions for an unknown launcher.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile
import zlib

MAX_PROFILE_BYTES = 2 * 1024 * 1024
ELF_HEADER = struct.Struct("<16sHHIQQQIHHHHHH")
PROGRAM_HEADER = struct.Struct("<IIQQQQQQ")


class VerificationError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for name, value in pairs:
        require(name not in result, f"duplicate JSON key: {name}")
        result[name] = value
    return result


def integer(value: object) -> int:
    require(type(value) in (int, str), f"integer or integer string required: {value!r}")
    if isinstance(value, str):
        require(bool(re.fullmatch(r"(?:0x[0-9a-fA-F]+|[0-9]+)", value)),
                f"invalid unsigned integer: {value!r}")
        value = int(value[2:], 16) if value.startswith("0x") else int(value)
    require(0 <= value <= 0xFFFFFFFFFFFFFFFF, "integer outside uint64 range")
    return value


def hex_bytes(value: object, label: str) -> bytes:
    require(isinstance(value, str) and bool(re.fullmatch(r"(?:[0-9a-f]{2})+", value)),
            f"{label}: nonempty lowercase byte hex required")
    return bytes.fromhex(value)


def read_profile(path: Path) -> dict:
    require(0 < path.stat().st_size <= MAX_PROFILE_BYTES, "Profile must be 1 byte..2 MiB")
    raw = path.read_bytes()
    require(len(raw) <= MAX_PROFILE_BYTES, "Profile grew beyond 2 MiB while reading")
    profile = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
    require(isinstance(profile, dict) and type(profile.get("schema")) is int and profile["schema"] == 1,
            "only Profile schema 1 is supported")
    require(isinstance(profile.get("images"), list) and 1 <= len(profile["images"]) <= 8,
            "images must contain 1..8 records")
    require(isinstance(profile.get("functions"), list) and 1 <= len(profile["functions"]) <= 256,
            "functions must contain 1..256 records")
    require(isinstance(profile.get("patches"), list) and 1 <= len(profile["patches"]) <= 256,
            "patches must contain 1..256 records")
    return profile


def load_library(image: dict, apk: zipfile.ZipFile | None, library_dir: Path | None) -> bytes:
    library = image["library"]
    require(isinstance(library, str) and bool(re.fullmatch(r"lib[A-Za-z0-9_.-]+\.so", library)),
            "invalid library basename")
    size = integer(image["size"])
    require(64 <= size <= 1024 * 1024 * 1024, "invalid ELF size")
    if apk is not None:
        name = f"lib/arm64-v8a/{library}"
        entries = [entry for entry in apk.infolist() if entry.filename == name]
        require(len(entries) == 1, f"{name}: missing or duplicate APK entry")
        entry = entries[0]
        require(entry.file_size == size, f"{library}: size mismatch ({entry.file_size} != {size})")
        require(not entry.flag_bits & 1, f"{library}: encrypted APK entry")
        # A compressed entry is readable for offline inspection. The runtime only
        # accepts an uncompressed mmap backing or an extracted native library.
        data = apk.read(entry)
    else:
        path = library_dir / library
        require(path.is_file() and path.stat().st_size == size, f"{library}: file missing or size mismatch")
        data = path.read_bytes()
    require(len(data) == size, f"{library}: size changed while reading")
    return data


def elf_segments(data: bytes) -> list[dict[str, int]]:
    require(len(data) >= ELF_HEADER.size, "truncated ELF header")
    header = ELF_HEADER.unpack_from(data)
    ident, elf_type, machine, version = header[:4]
    require(ident[:7] == b"\x7fELF\x02\x01\x01" and elf_type == 3 and machine == 183 and version == 1,
            "expected little-endian AArch64 ET_DYN ELF")
    phoff, ehsize, phentsize, phnum = header[5], header[8], header[9], header[10]
    require(ehsize == ELF_HEADER.size and phentsize == PROGRAM_HEADER.size and 1 <= phnum <= 128,
            "invalid ELF program-header geometry")
    require(ELF_HEADER.size <= phoff <= 65536 and phoff + phnum * phentsize <= len(data),
            "program headers outside ELF file")
    result = []
    for index in range(phnum):
        kind, flags, offset, address, _, file_size, memory_size, alignment = PROGRAM_HEADER.unpack_from(
            data, phoff + index * phentsize)
        if kind != 1:
            continue
        require(offset <= len(data) and file_size <= len(data) - offset and memory_size >= file_size,
                "PT_LOAD outside ELF file")
        result.append(dict(offset=offset, address=address, fileSize=file_size,
                           memorySize=memory_size, flags=flags, alignment=alignment))
    return result


def bytes_at(data: bytes, segments: list[dict], address: int, length: int, flags: int) -> bytes:
    for segment in segments:
        start, size = segment["address"], segment["fileSize"]
        if segment["flags"] & flags == flags and address >= start and address - start <= size:
            delta = address - start
            if length <= size - delta:
                offset = segment["offset"] + delta
                return data[offset:offset + length]
    raise VerificationError(f"address 0x{address:x}, length {length}: outside required PT_LOAD segment")


def verify(profile: dict, apk: zipfile.ZipFile | None = None, library_dir: Path | None = None) -> list[str]:
    images = {}
    reports = []
    for image in profile["images"]:
        image_id = image["id"]
        require(image_id not in images, f"duplicate image id: {image_id}")
        data = load_library(image, apk, library_dir)
        digest = hashlib.sha256(data).hexdigest()
        require(digest == image["sha256"], f"{image['library']}: SHA-256 mismatch (actual {digest})")
        segments = elf_segments(data)
        expected = [{field: integer(segment[field]) for field in
                     ("offset", "address", "fileSize", "memorySize", "flags", "alignment")}
                    for segment in image["segments"]]
        require(segments == expected, f"{image['library']}: PT_LOAD layout mismatch")
        for identity in image["identity"]:
            expected_bytes = hex_bytes(identity["bytes"], "identity")
            address = integer(identity["address"])
            require(bytes_at(data, segments, address, len(expected_bytes), 4) == expected_bytes,
                    f"{image['library']}: identity bytes mismatch at 0x{address:x}")
        images[image_id] = data, segments
        reports.append(f"{image['library']}: SHA-256, ELF layout and identity bytes match")
    functions = {}
    checked = 0
    for function in profile["functions"]:
        name = function["id"]
        require(name not in functions, f"duplicate function id: {name}")
        require(function["image"] in images, f"{name}: unknown image reference")
        data, segments = images[function["image"]]
        address = integer(function["address"])
        expected = hex_bytes(function["bytes"], f"function {name}")
        require(address % 4 == 0 and len(expected) % 4 == 0, f"{name}: unaligned function")
        require(bytes_at(data, segments, address, len(expected), 5) == expected,
                f"{name}: full function snapshot mismatch")
        functions[name] = function, expected
        checked += len(expected)
    addresses = set()
    for patch in profile["patches"]:
        name = patch["id"]
        require(patch["function"] in functions, f"{name}: unknown function reference")
        function, snapshot = functions[patch["function"]]
        offset = integer(patch["offset"])
        require(offset % 4 == 0 and offset + 4 <= len(snapshot), f"{name}: patch outside function")
        expected = integer(patch["expected"])
        require(expected <= 0xFFFFFFFF and struct.unpack_from("<I", snapshot, offset)[0] == expected,
                f"{name}: expected instruction differs from snapshot")
        key = function["image"], integer(function["address"]) + offset
        require(key not in addresses, f"{name}: duplicate patch address")
        addresses.add(key)
    reports.append(f"{len(functions)} complete functions ({checked} bytes) and {len(addresses)} patch instructions match")
    return reports


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path, help="launcher APK to inspect")
    source.add_argument("--lib-dir", type=Path, help="directory containing extracted arm64 .so files")
    args = parser.parse_args()
    try:
        profile = read_profile(args.profile)
        if args.apk:
            with zipfile.ZipFile(args.apk) as archive:
                reports = verify(profile, apk=archive)
        else:
            reports = verify(profile, library_dir=args.lib_dir)
        print(f"Profile {profile['id']}: binary evidence verified")
        print("\n".join(reports))
        print("This does not verify runtime behavior. Import still runs MonoIcon's native schema validator.")
        return 0
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, struct.error,
            zipfile.BadZipFile, zipfile.LargeZipFile, zlib.error) as error:
        print(f"Verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
