"""Offline intake tests using generated ELF64 samples, never Launcher binaries.

Run from the repository root:
    python -B -m unittest discover -s tools/tests -v
"""

from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import hashlib
import importlib.util
import io
import json
import lzma
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile


TOOL_PATH = Path(__file__).resolve().parents[1] / "launcher_inspector.py"
SPEC = importlib.util.spec_from_file_location("launcher_inspector", TOOL_PATH)
inspector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inspector)

BUILD_ID = bytes.fromhex("00112233445566778899aabbccddeeff01234567")
CODE = b"\x1f\x20\x03\xd5" * 4  # Four AArch64 NOP instructions.
DATA = b"test data bytes!"
CODE_OFFSET, DATA_OFFSET, NOTE_OFFSET = 0x200, 0x240, 0x280


def note(name, note_type, description, alignment=4):
    """Construct an ELF note with padding after the name and description."""
    return (struct.pack("<III", len(name), len(description), note_type)
            + name + b"\0" * (-(12 + len(name)) % alignment)
            + description + b"\0" * (-len(description) % alignment))


def android_description(api=35):
    return struct.pack("<I", api) + b"r27d".ljust(64, b"\0") + b"13750724".ljust(64, b"\0")


def make_elf(code=CODE, data=DATA, build_id=BUILD_ID, extra_notes=b"", trailer=b"",
             notes_blob=None, note_alignment=4):
    """Stripped ET_DYN with RX/RW PT_LOADs and a separate PT_NOTE."""
    notes = note(b"Android\0", 1, android_description()) + extra_notes
    if build_id is not None:
        notes = note(b"GNU\0", 3, build_id) + notes
    if notes_blob is not None:
        notes = notes_blob
    image = bytearray(NOTE_OFFSET + len(notes))
    image[:16] = b"\x7fELF\x02\x01\x01" + b"\0" * 9
    struct.pack_into("<HHIQQQIHHHHHH", image, 16,
                     3, 183, 1, 0x1000, 64, 0, 0, 64, 56, 3, 0, 0, 0)
    headers = (
        (1, 5, CODE_OFFSET, 0x1000, 0x1000, len(code), len(code) + 16, 0x100),
        (1, 6, DATA_OFFSET, 0x2040, 0x2040, len(data), len(data) + 32, 0x100),
        (4, 4, NOTE_OFFSET, NOTE_OFFSET, NOTE_OFFSET, len(notes), len(notes), note_alignment),
    )
    for index, header in enumerate(headers):
        struct.pack_into("<IIQQQQQQ", image, 64 + index * 56, *header)
    image[CODE_OFFSET:CODE_OFFSET + len(code)] = code
    image[DATA_OFFSET:DATA_OFFSET + len(data)] = data
    image[NOTE_OFFSET:] = notes
    return bytes(image) + trailer


def add_note_section(binary, alignment=4, section_only=False):
    """Add SHT_NULL and SHT_NOTE pointing to the existing note bytes."""
    image = bytearray(binary)
    note_size = struct.unpack_from("<Q", image, 64 + 2 * 56 + 32)[0]
    image.extend(b"\0" * (-len(image) % 8))
    section_offset = len(image)
    image.extend(b"\0" * 128)
    struct.pack_into("<Q", image, 40, section_offset)
    struct.pack_into("<HHH", image, 58, 64, 2, 0)
    if section_only:
        struct.pack_into("<H", image, 56, 2)
    struct.pack_into("<IIQQQQIIQQ", image, section_offset + 64,
                     0, 7, 0, 0, NOTE_OFFSET, note_size, 0, 0, alignment, 0)
    return bytes(image)


def manifest(app=None, launcher=None):
    return {
        "schema_version": 1,
        "libraries": {
            "libapp.so": inspector.parse_elf(make_elf() if app is None else app),
            "libapp_launcher.so": inspector.parse_elf(
                make_elf(build_id=None) if launcher is None else launcher),
        },
    }


class ElfParsingTests(unittest.TestCase):
    def test_basic_elf_information(self):
        binary = make_elf()
        facts = inspector.parse_elf(binary)
        self.assertEqual(facts["size"], len(binary))
        for key, value in {"class": "ELF64", "endianness": "little",
                           "machine": 183, "machine_name": "AArch64",
                           "type": 3, "type_name": "ET_DYN",
                           "program_header_count": 3,
                           "section_header_count": 0}.items():
            self.assertEqual(facts["elf"][key], value, key)
        self.assertEqual(len(facts["program_headers"]), 3)
        self.assertEqual(len(facts["load_segments"]), 2)

    def test_gnu_build_id(self):
        facts = inspector.parse_elf(make_elf())
        self.assertEqual(facts["gnu_build_id"], BUILD_ID.hex())
        raw = next(item for item in facts["notes"] if item["name"] == "GNU")
        self.assertEqual(raw["name_hex"], b"GNU\0".hex())
        self.assertEqual(raw["type"], 3)
        self.assertEqual(raw["description_hex"], BUILD_ID.hex())

    def test_android_note(self):
        facts = inspector.parse_elf(make_elf(build_id=None))
        self.assertIsNone(facts["gnu_build_id"])
        self.assertEqual(facts["android_note"], {
            "abi_api": 35, "ndk_version": "r27d", "ndk_build": "13750724"})
        raw = next(item for item in facts["notes"] if item["name"] == "Android")
        self.assertEqual(raw["description_hex"], android_description().hex())
        self.assertEqual(raw["type"], 1)

    def test_unknown_note_preserves_name_type_and_description(self):
        name, description = b"vendor\0\xff", b"\x00\xffabc"
        facts = inspector.parse_elf(make_elf(extra_notes=note(name, 0xCAFE, description)))
        raw = next(item for item in facts["notes"] if item["type"] == 0xCAFE)
        self.assertEqual(raw["name_hex"], name.hex())
        self.assertEqual(raw["description_hex"], description.hex())
        self.assertIn("name", raw)

    def test_unknown_gnu_note_type_is_not_a_build_id(self):
        extra = note(b"GNU\0", 9, b"not a build id")
        facts = inspector.parse_elf(make_elf(build_id=None, extra_notes=extra))
        self.assertIsNone(facts["gnu_build_id"])
        self.assertTrue(any(item["type"] == 9 for item in facts["notes"]))

    def test_conflicting_build_ids_remain_unknown(self):
        other = note(b"GNU\0", 3, b"different build id")
        facts = inspector.parse_elf(make_elf(extra_notes=other))
        self.assertIsNone(facts["gnu_build_id"])
        self.assertEqual(sum(item["type"] == 3 for item in facts["notes"]), 2)

    def test_pt_note_eight_byte_alignment_preserves_unknown_note(self):
        # A five-byte owner makes the eight-byte name padding observable.
        raw = note(b"Other", 99, b"abc", alignment=8)
        facts = inspector.parse_elf(make_elf(notes_blob=raw, note_alignment=8))
        self.assertEqual(len(facts["notes"]), 1)
        parsed = facts["notes"][0]
        self.assertEqual(parsed["name_hex"], b"Other".hex())
        self.assertEqual(parsed["description_hex"], b"abc".hex())
        self.assertEqual(parsed["alignment"], 8)

    def test_sht_note_eight_byte_alignment_without_pt_note(self):
        # GNU_PROPERTY_AARCH64_FEATURE_1_AND, retaining its raw descriptor.
        description = struct.pack("<III", 0xC0000000, 4, 3) + b"\0" * 4
        raw = note(b"GNU\0", 5, description, alignment=8)
        binary = make_elf(notes_blob=raw, note_alignment=8)
        facts = inspector.parse_elf(add_note_section(binary, alignment=8, section_only=True))
        self.assertEqual(facts["elf"]["program_header_count"], 2)
        self.assertEqual(facts["elf"]["section_header_count"], 2)
        self.assertIsNone(facts["gnu_build_id"])
        self.assertEqual(len(facts["notes"]), 1)
        self.assertEqual(facts["notes"][0]["description_hex"], description.hex())
        self.assertEqual(facts["notes"][0]["alignment"], 8)

    def test_note_shared_by_segment_and_section_is_not_duplicated(self):
        facts = inspector.parse_elf(add_note_section(make_elf()))
        self.assertEqual(len(facts["notes"]), 2)
        self.assertEqual(facts["gnu_build_id"], BUILD_ID.hex())

    def test_sha256_and_executable_segment_hash(self):
        binary = make_elf()
        facts = inspector.parse_elf(binary)
        self.assertEqual(facts["sha256"], hashlib.sha256(binary).hexdigest())
        executable, writable = facts["load_segments"]
        self.assertEqual(executable["flags"] & inspector.PF_X, 1)
        self.assertEqual(executable["offset"], CODE_OFFSET)
        self.assertEqual(executable["filesz"], len(CODE))
        self.assertGreater(executable["memsz"], executable["filesz"])
        self.assertEqual(executable["sha256"], hashlib.sha256(CODE).hexdigest())
        self.assertIsNone(writable["sha256"])

    def test_sha256_file_multiple_chunks(self):
        binary = b"abc" * 400000
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hash fixture.bin"
            path.write_bytes(binary)
            self.assertEqual(inspector.sha256_file(path), hashlib.sha256(binary).hexdigest())

    def test_non_elf_and_truncated_inputs(self):
        for binary in (b"", b"not ELF", b"\x7fELF", make_elf()[:32], make_elf()[:-1]):
            with self.subTest(length=len(binary)):
                with self.assertRaises(inspector.InspectorError):
                    inspector.parse_elf(binary)

    def test_unsupported_elf_formats(self):
        mutations = ((4, b"\x01"), (5, b"\x02"), (6, b"\x02"),
                     (18, struct.pack("<H", 62)))
        for offset, value in mutations:
            with self.subTest(offset=offset):
                binary = bytearray(make_elf())
                binary[offset:offset + len(value)] = value
                with self.assertRaisesRegex(inspector.InspectorError, "unsupported"):
                    inspector.parse_elf(binary)

    def test_invalid_program_header_boundaries_and_loads(self):
        mutations = (
            (32, "Q", 0xFFFFFFFF),  # e_phoff outside the file.
            (54, "H", 55),  # e_phentsize is not Elf64_Phdr.
            (64 + 8, "Q", 0xFFFFFFFF),  # PT_LOAD p_offset outside file.
            (64 + 40, "Q", 1),  # PT_LOAD p_memsz < p_filesz.
            (64 + 48, "Q", 3),  # Alignment is not a power of two.
        )
        for offset, fmt, value in mutations:
            with self.subTest(offset=offset):
                binary = bytearray(make_elf())
                struct.pack_into("<" + fmt, binary, offset, value)
                with self.assertRaises(inspector.InspectorError):
                    inspector.parse_elf(binary)

    def test_truncated_note_description_is_rejected(self):
        binary = bytearray(make_elf())
        struct.pack_into("<I", binary, NOTE_OFFSET + 4, 0xFFFFFFFF)
        with self.assertRaisesRegex(inspector.InspectorError, "truncated Note"):
            inspector.parse_elf(binary)


class IntakeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.binaries = {"libapp.so": make_elf(), "libapp_launcher.so": make_elf(build_id=None)}

    def write_apk(self, entries=None, extra_entries=()):
        path = self.root / "Launcher fixture.apk"
        entries = self.binaries if entries is None else entries
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, binary in entries.items():
                archive.writestr("lib/arm64-v8a/" + name, binary)
            for name, binary in extra_entries:
                archive.writestr(name, binary)
        return path

    def test_apk_extraction_manifest_and_report(self):
        apk = self.write_apk(extra_entries=[("classes.dex", b"unrelated APK member")])
        original = apk.read_bytes()
        output = self.root / "intake with spaces"
        result = inspector.inspect(apk, output)
        saved = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(saved, result)
        self.assertEqual(result["apk"]["sha256"], hashlib.sha256(original).hexdigest())
        self.assertEqual(result["apk"]["size"], len(original))
        self.assertEqual(result["compatibility"], "NOT_ASSESSED")
        self.assertEqual(result["environment"], {"device": None, "os": None, "android": None})
        self.assertIsNone(result["launcher"]["version_name"])
        for name, binary in self.binaries.items():
            self.assertEqual((output / "lib" / "arm64-v8a" / name).read_bytes(), binary)
        self.assertFalse((output / "classes.dex").exists())
        self.assertEqual(apk.read_bytes(), original)
        self.assertIn("cannot independently prove Hook ABI compatibility",
                      (output / "report.md").read_text(encoding="utf-8"))

    def test_apk_paths_are_never_extracted_from_untrusted_names(self):
        extras = [("../escape.txt", b"escape"), ("/absolute.txt", b"escape"),
                  ("lib/arm64-v8a/../../../escape2.txt", b"escape"),
                  ("lib\\arm64-v8a\\..\\escape3.txt", b"escape")]
        apk = self.write_apk(extra_entries=extras)
        output = self.root / "safe-output"
        inspector.inspect(apk, output)
        emitted = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()}
        self.assertEqual(emitted, {"manifest.json", "report.md", "lib/arm64-v8a/libapp.so",
                                   "lib/arm64-v8a/libapp_launcher.so"})
        self.assertFalse((self.root / "escape.txt").exists())
        self.assertFalse((self.root / "escape2.txt").exists())

    def test_missing_apk_library(self):
        apk = self.write_apk(entries={"libapp.so": self.binaries["libapp.so"]})
        output = self.root / "missing-output"
        with self.assertRaisesRegex(inspector.InspectorError, "missing target APK entry.*libapp_launcher"):
            inspector.inspect(apk, output)
        self.assertFalse(output.exists())

    def test_duplicate_target_apk_entry(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            apk = self.write_apk(extra_entries=[("lib/arm64-v8a/libapp.so", make_elf())])
        with self.assertRaisesRegex(inspector.InspectorError, "duplicate target APK entry"):
            inspector.inspect(apk, self.root / "duplicate-output")

    def test_extracted_directory_layouts(self):
        for index, suffix in enumerate(("", "arm64-v8a", "lib/arm64-v8a")):
            with self.subTest(layout=suffix):
                source = self.root / f"source-{index}"
                libraries = source / suffix
                libraries.mkdir(parents=True)
                for name, binary in self.binaries.items():
                    (libraries / name).write_bytes(binary)
                result = inspector.inspect(source, self.root / f"output-{index}")
                self.assertEqual(result["input"]["kind"], "directory")
                self.assertIsNone(result["apk"]["sha256"])
                self.assertIsNone(result["apk"]["size"])
                for name, binary in self.binaries.items():
                    self.assertEqual((libraries / name).read_bytes(), binary)

    def test_missing_directory_library(self):
        source = self.root / "incomplete"
        source.mkdir()
        (source / "libapp.so").write_bytes(make_elf())
        with self.assertRaisesRegex(inspector.InspectorError, "missing target ELF"):
            inspector.inspect(source, self.root / "output")

    def test_corrupt_apk_and_corrupt_elf_are_rejected(self):
        apk = self.root / "broken.apk"
        apk.write_bytes(b"not a ZIP")
        with self.assertRaisesRegex(inspector.InspectorError, "invalid or unsupported APK/ZIP"):
            inspector.inspect(apk, self.root / "output")
        apk = self.write_apk(entries={"libapp.so": b"bad ELF", "libapp_launcher.so": make_elf()})
        with self.assertRaisesRegex(inspector.InspectorError, "not an ELF"):
            inspector.inspect(apk, self.root / "output")
        self.assertFalse((self.root / "output").exists())

    def test_cli_rejects_corrupt_zip_lzma_stream_without_traceback(self):
        apk = self.root / "broken lzma.apk"
        entry = "lib/arm64-v8a/libapp.so"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_LZMA) as archive:
            for name, binary in self.binaries.items():
                archive.writestr("lib/arm64-v8a/" + name, binary)
        with zipfile.ZipFile(apk) as archive:
            header_offset = archive.getinfo(entry).header_offset
        damaged = bytearray(apk.read_bytes())
        name_size, extra_size = struct.unpack_from("<HH", damaged, header_offset + 26)
        compressed_offset = header_offset + 30 + name_size + extra_size
        # Skip ZIP's nine-byte LZMA properties prefix; corrupt the stream itself.
        damaged[compressed_offset + 9] ^= 0xFF
        apk.write_bytes(damaged)
        with zipfile.ZipFile(apk) as archive:
            with self.assertRaises(lzma.LZMAError):
                archive.read(entry)
        errors = io.StringIO()
        output = self.root / "lzma-output"
        with redirect_stderr(errors):
            result = inspector.main(["inspect", str(apk), "--out-dir", str(output)])
        self.assertEqual(result, 1)
        self.assertIn("error:", errors.getvalue())
        self.assertIn("invalid or unsupported APK/ZIP", errors.getvalue())
        self.assertNotIn("Traceback", errors.getvalue())
        self.assertFalse(output.exists())
        self.assertEqual(apk.read_bytes(), bytes(damaged))

    def test_missing_input(self):
        with self.assertRaisesRegex(inspector.InspectorError, "does not exist"):
            inspector.inspect(self.root / "missing.apk", self.root / "output")

    def test_existing_output_is_preserved(self):
        apk = self.write_apk()
        output = self.root / "output"
        inspector.inspect(apk, output)
        saved = (output / "manifest.json").read_bytes()
        with self.assertRaisesRegex(inspector.InspectorError, "output already exists"):
            inspector.inspect(apk, output)
        self.assertEqual((output / "manifest.json").read_bytes(), saved)

    def test_cli_inspect_and_compare_paths_with_spaces(self):
        apk = self.write_apk()
        output = self.root / "output with spaces"
        with redirect_stdout(io.StringIO()):
            self.assertEqual(inspector.main(["inspect", str(apk), "--out-dir", str(output)]), 0)
            path = output / "manifest.json"
            report = self.root / "comparison report.md"
            self.assertEqual(inspector.main(["compare", str(path), str(path), "--out", str(report)]), 0)
        text = report.read_text(encoding="utf-8")
        self.assertIn("EXACT_BINARY_MATCH", text)
        self.assertIn("cannot independently prove Hook ABI compatibility", text)

    def test_cli_rejects_invalid_json_without_traceback(self):
        path = self.root / "broken.json"
        path.write_text("{bad json", encoding="utf-8")
        errors = io.StringIO()
        with redirect_stderr(errors):
            result = inspector.main(["compare", str(path), str(path), "--out", str(self.root / "report.md")])
        self.assertEqual(result, 1)
        self.assertIn("invalid manifest JSON", errors.getvalue())
        self.assertNotIn("Traceback", errors.getvalue())

    def test_cli_refuses_overwriting_input_manifest(self):
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest()), encoding="utf-8")
        original = path.read_bytes()
        with redirect_stderr(io.StringIO()):
            self.assertEqual(inspector.main(["compare", str(path), str(path), "--out", str(path)]), 1)
        self.assertEqual(path.read_bytes(), original)


class ComparisonTests(unittest.TestCase):
    def test_exact_binary_match(self):
        baseline = manifest()
        result = inspector.compare(baseline, deepcopy(baseline))
        self.assertEqual(result["classification"], "EXACT_BINARY_MATCH")
        self.assertEqual(result["compatibility"], "NOT_ASSESSED")
        for comparison in result["libraries"].values():
            self.assertEqual(comparison["differences"], [])

    def test_same_build_id_but_nonexecutable_file_bytes_changed(self):
        result = inspector.compare(manifest(), manifest(app=make_elf(trailer=b"changed debug info")))
        self.assertEqual(result["classification"], "SAME_BUILD_ID_BUT_FILE_CHANGED")
        differences = result["libraries"]["libapp.so"]["differences"]
        self.assertTrue(any(item["field"] == "sha256" for item in differences))
        self.assertFalse(any(item["field"] == "load_segments" for item in differences))

    def test_executable_change_takes_priority_over_same_build_id(self):
        changed = make_elf(code=b"\x00" + CODE[1:])
        result = inspector.compare(manifest(), manifest(app=changed))
        self.assertEqual(result["classification"], "EXECUTABLE_SEGMENTS_CHANGED")
        self.assertEqual(result["libraries"]["libapp.so"]["classification"], "EXECUTABLE_SEGMENTS_CHANGED")

    def test_executable_layout_change_is_reported(self):
        changed = bytearray(make_elf())
        struct.pack_into("<Q", changed, 64 + 40, len(CODE) + 32)
        result = inspector.compare(manifest(), manifest(app=bytes(changed)))
        self.assertEqual(result["classification"], "EXECUTABLE_SEGMENTS_CHANGED")
        self.assertTrue(any(item["field"] == "program_headers"
                            for item in result["libraries"]["libapp.so"]["differences"]))

    def test_android_note_and_executable_hash_alone_do_not_establish_identity(self):
        changed = make_elf(build_id=None, trailer=b"changed non-code")
        result = inspector.compare(manifest(), manifest(launcher=changed))
        self.assertEqual(result["classification"], "UNKNOWN_OR_INCOMPLETE")
        self.assertEqual(result["compatibility"], "NOT_ASSESSED")

    def test_removed_executable_segment_or_pf_x_is_a_change(self):
        for field_offset, replacement in ((64, 0), (68, 4)):
            with self.subTest(field_offset=field_offset):
                changed = bytearray(make_elf())
                struct.pack_into("<I", changed, field_offset, replacement)
                result = inspector.compare(manifest(), manifest(app=bytes(changed)))
                self.assertEqual(result["classification"], "EXECUTABLE_SEGMENTS_CHANGED")

    def test_missing_hashes_are_not_an_exact_match(self):
        baseline = manifest()
        for facts in baseline["libraries"].values():
            facts["sha256"] = None
        result = inspector.compare(baseline, deepcopy(baseline))
        self.assertEqual(result["classification"], "UNKNOWN_OR_INCOMPLETE")

    def test_incomplete_executable_metadata_without_file_hashes_is_unknown(self):
        baseline = manifest()
        for facts in baseline["libraries"].values():
            facts["sha256"] = None
        for field in ("offset", "vaddr", "paddr", "filesz", "memsz", "flags", "align"):
            for value in (None, -1, "32", True):
                with self.subTest(field=field, value=value):
                    candidate = deepcopy(baseline)
                    candidate["libraries"]["libapp.so"]["load_segments"][0][field] = value
                    result = inspector.compare(baseline, candidate)
                    self.assertEqual(result["classification"], "UNKNOWN_OR_INCOMPLETE")
                    self.assertEqual(result["libraries"]["libapp.so"]["classification"],
                                     "UNKNOWN_OR_INCOMPLETE")

    def test_missing_library_is_incomplete(self):
        candidate = manifest()
        del candidate["libraries"]["libapp_launcher.so"]
        result = inspector.compare(manifest(), candidate)
        self.assertEqual(result["classification"], "UNKNOWN_OR_INCOMPLETE")

    def test_equal_hash_with_contradictory_architecture_is_incomplete(self):
        baseline = manifest()
        candidate = deepcopy(baseline)
        candidate["libraries"]["libapp.so"]["elf"]["machine"] = 62
        result = inspector.compare(baseline, candidate)
        self.assertEqual(result["classification"], "UNKNOWN_OR_INCOMPLETE")
        self.assertTrue(any(item["field"] == "elf"
                            for item in result["libraries"]["libapp.so"]["differences"]))

    def test_unknown_note_difference_is_reported(self):
        extra = note(b"Vendor\0", 99, b"evidence")
        result = inspector.compare(manifest(), manifest(app=make_elf(extra_notes=extra)))
        self.assertTrue(any(item["field"] == "notes"
                            for item in result["libraries"]["libapp.so"]["differences"]))

    def test_apk_metadata_difference_is_separate_from_elf_identity(self):
        baseline = manifest()
        candidate = deepcopy(baseline)
        baseline["apk"] = {"sha256": "a" * 64}
        candidate["apk"] = {"sha256": "b" * 64}
        result = inspector.compare(baseline, candidate)
        self.assertEqual(result["classification"], "EXACT_BINARY_MATCH")
        self.assertEqual(result["metadata_differences"][0]["field"], "apk")

    def test_invalid_manifest_structure(self):
        for invalid in (None, [], {}, {"schema_version": 2, "libraries": {}},
                        {"schema_version": 1, "libraries": []}):
            with self.subTest(value=invalid):
                with self.assertRaises(inspector.InspectorError):
                    inspector.compare(invalid, manifest())

    def test_null_or_missing_notes_do_not_conflict_with_known_binary_hash(self):
        for omit in (False, True):
            with self.subTest(omit=omit):
                candidate = manifest()
                if omit:
                    del candidate["libraries"]["libapp.so"]["notes"]
                else:
                    candidate["libraries"]["libapp.so"]["notes"] = None
                result = inspector.compare(manifest(), candidate)
                self.assertEqual(result["classification"], "EXACT_BINARY_MATCH")

    def test_invalid_note_record_type_is_rejected(self):
        for notes in ("raw notes", {"type": 3}, ["not a note record"]):
            with self.subTest(notes=notes):
                candidate = manifest()
                candidate["libraries"]["libapp.so"]["notes"] = notes
                with self.assertRaises(inspector.InspectorError):
                    inspector.compare(manifest(), candidate)

    def test_invalid_build_id_value_is_rejected(self):
        for build_id in ("", "a", "not-hex", 6144, {"id": "abcd"}):
            with self.subTest(build_id=build_id):
                candidate = manifest()
                candidate["libraries"]["libapp.so"]["gnu_build_id"] = build_id
                with self.assertRaises(inspector.InspectorError):
                    inspector.compare(manifest(), candidate)


if __name__ == "__main__":
    unittest.main()
