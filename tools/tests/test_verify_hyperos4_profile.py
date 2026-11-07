"""System metadata checks are independent of proprietary launcher binaries."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "verify_hyperos4_profile", Path(__file__).resolve().parents[1] / "verify_hyperos4_profile.py")
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class SystemMatchTests(unittest.TestCase):
    def test_missing_field_requires_manual_approval(self):
        self.assertIn("manual approval", verifier.system_match_report({"match": {}}, "OS4.0.0.3.XAACNXM"))

    def test_declared_version_requires_exact_match(self):
        profile = {"match": {"systemVersions": ["OS4.0.0.3.XAACNXM"]}}
        self.assertIn("matches", verifier.system_match_report(profile, "OS4.0.0.3.XAACNXM"))
        for version in ("OS4.0.0.4.XAACNXM", "OS4.0.0.3.XAACNXM-extra", "", "os4.0.0.3.XAACNXM"):
            with self.subTest(version=version), self.assertRaises(verifier.VerificationError):
                verifier.system_match_report(profile, version)

    def test_invalid_declarations_cannot_be_legacy(self):
        for versions in (None, [], "OS4.0", [""], [4], ["OS4", "OS4"], ["bad\nversion"]):
            with self.subTest(versions=versions), self.assertRaises(verifier.VerificationError):
                verifier.system_match_report({"match": {"systemVersions": versions}})


if __name__ == "__main__":
    unittest.main()
