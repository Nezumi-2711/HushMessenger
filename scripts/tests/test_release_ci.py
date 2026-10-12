import hashlib
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from types import SimpleNamespace

SCRIPT = Path(__file__).resolve().parents[2] / ".github/scripts/release_ci.py"
SPEC = importlib.util.spec_from_file_location("release_ci", SCRIPT)
release_ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_ci)

COMMIT = "a" * 40
CODES = ["346415686", "346415687"]


class ReleaseCi(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalog = {
            "version": "1.2.3",
            "patches": [
                {
                    "name": f"Control {n}",
                    "compatiblePackages": [{"targets": [{
                        "version": "582.0.0.61.92",
                        "versionCodes": None,
                        "description": f"Arm64 builds {CODES[0]} and {CODES[1]}; checked again during patching",
                    }]}],
                }
                for n in range(3)
            ],
        }
        self.write("gradle.properties", "version=1.2.3\nbundleTimestampMillis=1790552148000\n")
        self.write("patches-list.json", json.dumps(self.catalog))
        self.write("patches-bundle.json", json.dumps({
            "created_at": "2026-10-11T19:54:40",
            "description": "old",
            "download_url": "https://github.com/SysAdminDoc/HushMessenger/releases/download/v1.0.0/patches-1.0.0.mpp",
            "signature_download_url": "",
            "version": "1.0.0",
        }))
        self.write("CHANGELOG.md",
                   "# Changelog\n\n## Unreleased\n\n- Next.\n\n"
                   "## 1.2.3 (2026-10-11)\n\nThis release does a thing.\nIt does it on [Messenger](https://x.test) **now**.\n\n"
                   "### New\n\n- A switch.\n\n## 1.0.0 (2026-10-01)\n\n- Old.\n")
        self.name = "patches-1.2.3.mpp"
        self.build(timestamp="1790552148000")

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def build(self, timestamp, entries=("classes.dex", "extensions/messenger.mpe"), dex_validated=True):
        bundle = self.root / "patches/build/libs" / self.name
        bundle.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(bundle, "w") as archive:
            for entry in entries:
                archive.writestr(entry, b"x")
            archive.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\r\nVersion: 1.2.3\r\nTimestamp: {timestamp}\r\n\r\n")
        self.write("patches/build/reports/catalog-evidence.json", json.dumps({
            "bundle": self.name,
            "sha256": hashlib.sha256(bundle.read_bytes()).hexdigest(),
            "dexValidated": dex_validated,
            "catalog": self.catalog,
        }))

    def run_command(self, function, **arguments):
        arguments.setdefault("version", "1.2.3")
        arguments["root"] = str(self.root)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            try:
                function(SimpleNamespace(**arguments))
            except SystemExit as stopped:
                return stopped.code, out.getvalue(), err.getvalue()
        return 0, out.getvalue(), err.getvalue()

    def test_a_matching_bundle_passes_and_prints_its_checksum(self):
        code, out, _ = self.run_command(release_ci.check)
        digest = hashlib.sha256((self.root / "patches/build/libs" / self.name).read_bytes()).hexdigest()
        self.assertEqual((code, out.strip()), (0, digest))

    def test_a_bundle_with_a_different_timestamp_is_refused(self):
        self.build(timestamp="1")
        code, _, err = self.run_command(release_ci.check)
        self.assertEqual(code, 1)
        self.assertIn("timestamp", err)

    def test_a_bundle_without_both_dex_halves_is_refused(self):
        self.build(timestamp="1790552148000", entries=("classes.dex",))
        code, _, err = self.run_command(release_ci.check)
        self.assertEqual(code, 1)
        self.assertIn("extensions/messenger.mpe", err)

    def test_evidence_for_other_bytes_is_refused(self):
        evidence = self.root / "patches/build/reports/catalog-evidence.json"
        data = json.loads(evidence.read_text())
        data["sha256"] = "0" * 64
        evidence.write_text(json.dumps(data))
        code, _, err = self.run_command(release_ci.check)
        self.assertEqual(code, 1)
        self.assertIn("another bundle", err)

    def test_a_stale_patch_list_is_refused(self):
        self.catalog["patches"].pop()
        self.write("patches-list.json", json.dumps({**self.catalog, "patches": self.catalog["patches"][:1]}))
        code, _, err = self.run_command(release_ci.check)
        self.assertEqual(code, 1)
        self.assertIn("patches-list.json", err)

    def test_a_version_other_than_gradles_is_refused(self):
        code, _, err = self.run_command(release_ci.check, version="1.2.4")
        self.assertEqual(code, 1)
        self.assertIn("gradle.properties", err)

    def test_notes_are_the_changelog_section_and_what_ci_checked(self):
        out = self.root / "notes.md"
        code, _, _ = self.run_command(release_ci.notes, repository="owner/repo", commit=COMMIT,
                                      run_url="https://run.test/1", out=str(out))
        text = out.read_text(encoding="utf-8")
        self.assertEqual(code, 0)
        self.assertTrue(text.startswith("This release does a thing."))
        self.assertNotIn("## 1.0.0", text)
        self.assertNotIn("- Next.", text)
        self.assertIn("### What was checked", text)
        self.assertIn("gh attestation verify patches-1.2.3.mpp -R owner/repo", text)
        self.assertIn("aaaaaaaaaaaa", text)

    def test_the_index_points_at_this_repositorys_release(self):
        out = self.root / "index.json"
        code, _, _ = self.run_command(release_ci.index, repository="owner/repo", commit=COMMIT,
                                      created="2026-10-12T05:00:00Z", out=str(out))
        index = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(code, 0)
        self.assertEqual(index["version"], "1.2.3")
        self.assertEqual(index["created_at"], "2026-10-12T05:00:00")
        self.assertEqual(index["download_url"], "https://github.com/owner/repo/releases/download/v1.2.3/patches-1.2.3.mpp")
        self.assertEqual(index["signature_download_url"], "")
        self.assertTrue(index["description"].startswith(
            "HushMessenger v1.2.3 for Messenger 582.0.0.61.92, all 2 arm64 builds. 3 patches. "
            "This release does a thing. It does it on Messenger now."))
        self.assertIn("aaaaaaaaaaaa", index["description"])
        self.assertTrue(out.read_text(encoding="utf-8").endswith("}\n"))

    def test_a_section_that_opens_with_a_list_adds_no_summary(self):
        self.write("CHANGELOG.md", "# Changelog\n\n## 1.2.3 (2026-10-11)\n\n### New\n\n- A switch.\n")
        self.assertEqual(release_ci.intro(release_ci.changelog(self.root, "1.2.3")), "")

    def notes_for(self, version):
        out = self.root / "notes.md"
        code, _, err = self.run_command(release_ci.notes, version=version, repository="owner/repo",
                                        commit=COMMIT, run_url="u", out=str(out))
        self.assertEqual(code, 0, err)
        return out.read_text(encoding="utf-8")

    def test_a_version_without_its_own_section_uses_unreleased(self):
        text = self.notes_for("1.2.4")
        self.assertTrue(text.startswith("- Next."))
        self.assertNotIn("This release does a thing.", text)

    def test_a_version_without_any_entry_lists_the_commits_since_the_previous_tag(self):
        self.write("CHANGELOG.md", "# Changelog\n\n## Unreleased\n\n## 1.2.3 (2026-10-11)\n\n- Old.\n")

        def git(*arguments, author="Dev"):
            subprocess.run(["git", "-C", str(self.root), "-c", f"user.name={author}", "-c", "user.email=d@x.test",
                            "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", *arguments],
                           check=True, capture_output=True)

        git("init", "-q")
        git("commit", "-q", "--allow-empty", "-m", "before the release")
        git("tag", "-a", "v1.2.3", "-m", "v1.2.3")
        git("commit", "-q", "--allow-empty", "-m", "fix: keep the log through rotation")
        git("commit", "-q", "--allow-empty", "-m", "chore(release): point Manager", author="github-actions[bot]")
        text = self.notes_for("1.2.4")
        self.assertTrue(text.startswith("CHANGELOG.md has no entry for 1.2.4, so these are the commits since v1.2.3."))
        self.assertIn("- fix: keep the log through rotation", text)
        self.assertNotIn("before the release", text)
        self.assertNotIn("point Manager", text)
        self.assertIn("### What was checked", text)

    def test_no_changelog_and_no_history_still_gives_notes(self):
        (self.root / "CHANGELOG.md").unlink()
        text = self.notes_for("1.2.4")
        self.assertTrue(text.startswith("No changes are listed for this version."))

    def test_the_index_needs_no_changelog_entry(self):
        (self.root / "CHANGELOG.md").unlink()
        out = self.root / "index.json"
        code, _, err = self.run_command(release_ci.index, repository="owner/repo", commit=COMMIT,
                                        created="2026-10-12T05:00:00Z", out=str(out))
        self.assertEqual(code, 0, err)
        self.assertTrue(json.loads(out.read_text())["description"].startswith(
            "HushMessenger v1.2.3 for Messenger 582.0.0.61.92, all 2 arm64 builds. 3 patches. Built by GitHub Actions"))

    def test_the_newest_version_is_named_first(self):
        count, targets = release_ci.catalog(self.root)
        self.assertEqual(count, 3)
        self.assertEqual([version for version, _ in targets], ["582.0.0.61.92"])
        self.catalog["patches"][0]["compatiblePackages"][0]["targets"].append(
            {"version": "583.0.0.1.91", "versionCodes": [347000001], "description": None})
        self.write("patches-list.json", json.dumps(self.catalog))
        _, targets = release_ci.catalog(self.root)
        self.assertEqual([version for version, _ in targets], ["583.0.0.1.91", "582.0.0.61.92"])
        self.assertEqual(targets[0][1], {"347000001"})


if __name__ == "__main__":
    unittest.main()
