"""Checks the bundle and writes the two texts the Release workflow publishes.

  python .github/scripts/release_ci.py check --version 0.23.2
  python .github/scripts/release_ci.py notes --version 0.23.2 --repository owner/repo
        --commit <sha> --run-url <url> --out notes.md
  python .github/scripts/release_ci.py index --version 0.23.2 --repository owner/repo
        --created 2026-10-12T05:00:00Z --commit <sha> --out patches-bundle.json

check   Holds patches/build/libs/patches-<version>.mpp to what scripts/check_release.py asks of the
        bundle itself: both dex halves, the manifest's version and pinned timestamp, and the catalog
        evidence :patches:checkPatchCatalog wrote for exactly these bytes. Prints the SHA-256.
notes   The GitHub release notes: what changed, and a "What was checked" list that says what CI checked
        and nothing more. What changed is the version's dated CHANGELOG section, as
        scripts/release/release.ps1 publishes it, else the Unreleased section, else the commits since the
        previous tag. A release never waits on the CHANGELOG: the version in gradle.properties decides.
index   patches-bundle.json for the release, which Morphe Manager reads to find the newest bundle,
        so download_url points at this repository's release.

The runner has no Messenger APKs, no release key and no private fixtures, so none of this claims the
patches were applied to a build, that the fixture replays passed, or that the checksums are signed.
README.md and scripts/check_release.py stay as upstream has them: the Release workflow only writes
patches-bundle.json. That file is the one thing here that no longer passes check_release.py's
release mode, which pins the upstream download URL.
"""

import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSION = re.compile(r"\d+\.\d+\.\d+")
BUNDLE_ENTRIES = {"classes.dex", "extensions/messenger.mpe"}
DASHES = ("—", "–")


def fail(message: str) -> None:
    print(f"[release] {message}", file=sys.stderr)
    sys.exit(1)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    # LF and no BOM, as git stores these files.
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def properties(root: Path) -> dict:
    return dict(re.findall(r"^([A-Za-z][A-Za-z0-9.]*)=(.+?)\s*$", read(root / "gradle.properties"), re.MULTILINE))


def version_key(version: str) -> tuple:
    return tuple(int(part) for part in version.split("."))


def check_arguments(args) -> None:
    if not VERSION.fullmatch(args.version):
        fail(f"{args.version} isn't X.Y.Z")
    if hasattr(args, "repository") and not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repository):
        fail(f"{args.repository} isn't owner/repo")
    if hasattr(args, "commit") and not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        fail(f"{args.commit} isn't a full commit hash")


def changelog(root: Path, version: str) -> str:
    """The body of `## <version> (date)` in CHANGELOG.md, else of `## Unreleased`, else "".

    Upstream keeps working under Unreleased and only dates a section when it cuts its own release, so
    a version synced from upstream mid-development usually has no section of its own yet.
    """
    path = root / "CHANGELOG.md"
    text = read(path).replace("\r\n", "\n") if path.is_file() else ""
    for heading in (rf"{re.escape(version)} \(\d{{4}}-\d{{2}}-\d{{2}}\)", "Unreleased"):
        match = re.search(rf"^## {heading}[ \t]*\n(.*?)(?=^## |\Z)", text, re.MULTILINE | re.DOTALL)
        if match and match.group(1).strip():
            return match.group(1).strip()
    return ""


def commits_since_previous_tag(root: Path) -> tuple:
    """The previous release tag and the subjects of the commits after it, merges and bot commits left out."""
    def git(*arguments):
        result = subprocess.run(["git", "-C", str(root), *arguments], capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else ""

    previous = git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "HEAD")
    span = f"{previous}..HEAD" if previous else "HEAD"
    subjects = git("log", "--no-merges", "--format=%an%x09%s", "--max-count=200", span).splitlines()
    return previous, [subject for author, _, subject in (line.partition("\t") for line in subjects)
                      if not author.endswith("[bot]")]


def changes(root: Path, version: str) -> str:
    body = changelog(root, version)
    if body:
        return body
    previous, subjects = commits_since_previous_tag(root)
    if not subjects:
        return "No changes are listed for this version."
    since = f" since {previous}" if previous else ""
    return f"CHANGELOG.md has no entry for {version}, so these are the commits{since}.\n\n" + \
        "\n".join(f"- {subject}" for subject in subjects)


def plain(text: str) -> str:
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    return re.sub(r"[*`]", "", text).strip()


def intro(body: str) -> str:
    """The paragraph that opens a CHANGELOG section, when it opens with one rather than a list."""
    lines = []
    for line in body.splitlines():
        if line.startswith(("#", "-", "*")):
            break
        if not line.strip():
            if lines:
                break
            continue
        lines.append(line.strip())
    return plain(" ".join(lines))


def catalog(root: Path) -> tuple:
    """The patch count and every Messenger build the catalog declares, newest version first."""
    document = json.loads(read(root / "patches-list.json"))
    builds = {}
    for entry in document["patches"]:
        for package in entry.get("compatiblePackages") or []:
            for target in package.get("targets") or []:
                codes = target.get("versionCodes") or re.findall(r"\b\d{9}\b", target.get("description") or "")
                builds.setdefault(target["version"], set()).update(str(code) for code in codes)
    return len(document["patches"]), sorted(builds.items(), key=lambda item: version_key(item[0].split("-")[0]), reverse=True)


def spoken(items: list) -> str:
    return items[0] if len(items) == 1 else ", ".join(items[:-1]) + " and " + items[-1]


def check(args) -> None:
    check_arguments(args)
    root = Path(args.root)
    props = properties(root)
    if props.get("version") != args.version:
        fail(f"gradle.properties says {props.get('version')}, not {args.version}")
    name = f"patches-{args.version}.mpp"
    bundle = root / "patches/build/libs" / name
    if not bundle.is_file():
        fail(f"No bundle at {bundle}")
    digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
    with zipfile.ZipFile(bundle) as archive:
        if archive.testzip() is not None:
            fail("The bundle has a corrupt entry")
        missing = BUNDLE_ENTRIES - set(archive.namelist())
        if missing:
            fail(f"The bundle has no {', '.join(sorted(missing))}, so Manager would load no patches")
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n ", "").replace("\n ", "")
    attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
    if attributes.get("Version") != args.version:
        fail(f"The bundle's manifest says version {attributes.get('Version')}, not {args.version}")
    if attributes.get("Timestamp") != props.get("bundleTimestampMillis"):
        fail(f"The bundle's timestamp {attributes.get('Timestamp')} isn't the pinned {props.get('bundleTimestampMillis')}")

    evidence = json.loads(read(root / "patches/build/reports/catalog-evidence.json"))
    if evidence.get("bundle") != name or evidence.get("sha256") != digest:
        fail("The catalog evidence is for another bundle than the one built")
    if evidence.get("dexValidated") is not True:
        fail("The catalog evidence has no structural dex validation")
    published = json.loads(read(root / "patches-list.json"))
    if json.dumps(published, sort_keys=True) != json.dumps(evidence.get("catalog"), sort_keys=True):
        fail("patches-list.json differs from the bundle's own patch list. Run :patches:generatePatchCatalog")
    if published.get("version") != args.version:
        fail(f"patches-list.json says {published.get('version')}, not {args.version}")
    print(digest)


def notes(args) -> None:
    check_arguments(args)
    root = Path(args.root)
    bundle = f"patches-{args.version}.mpp"
    checked = "\n".join([
        f"* Built in [this workflow run]({args.run_url}) from a clean checkout of commit {args.commit[:12]}.",
        "* The bundle carries both dex halves, its manifest names this version with the timestamp gradle.properties pins, "
        "and :patches:checkPatchCatalog matched it against patches-list.json.",
        f"* Build provenance is attested. Check a download with `gh attestation verify {bundle} -R {args.repository}`.",
        "* No release receipt, no fixture replays and no signature: the runner has no Messenger APKs and no release key, "
        "so the patches weren't applied to a build here and SHA256SUMS.txt isn't signed.",
    ])
    write(Path(args.out), f"{changes(root, args.version)}\n\n### What was checked\n\n{checked}\n")
    print(f"[release] notes for {args.version} written")


def index(args) -> None:
    check_arguments(args)
    root = Path(args.root)
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z?", args.created):
        fail(f"{args.created} isn't a UTC time written YYYY-MM-DDTHH:MM:SS")
    count, targets = catalog(root)
    if not targets:
        fail("patches-list.json names no Messenger versions")
    named = [f"{version} ({len(codes)} arm64 builds)" if len(targets) > 1 else f"{version}, all {len(codes)} arm64 builds"
             for version, codes in targets]
    opening = f"HushMessenger v{args.version} for Messenger {spoken(named)}. {count} patches."
    summary = intro(changelog(root, args.version))
    description = " ".join(part for part in (
        opening,
        summary,
        f"Built by GitHub Actions from commit {args.commit[:12]}, with build provenance attested. "
        "The patches weren't applied to a Messenger APK in CI.",
        "The .mpp is a patch bundle, not a Messenger APK. Source updates don't change installed apps. "
        "Keep the manager's existing signing key when updating.",
    ) if part)
    if any(dash in description for dash in DASHES):
        fail("the description has an em or en dash")

    bundle = json.loads(read(root / "patches-bundle.json"))
    bundle.update({
        # The format the index has always used: UTC, to the second, no zone suffix.
        "created_at": args.created.rstrip("Z"),
        "description": description,
        "download_url": f"https://github.com/{args.repository}/releases/download/"
                        f"v{args.version}/patches-{args.version}.mpp",
        "version": args.version,
    })
    write(Path(args.out), json.dumps(bundle, indent=2, ensure_ascii=False) + "\n")
    print(f"[release] index points at {args.repository} v{args.version} ({count} patches)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    commands = (
        ("check", check, ()),
        ("notes", notes, ("--repository", "--commit", "--run-url", "--out")),
        ("index", index, ("--repository", "--commit", "--created", "--out")),
    )
    for name, function, required in commands:
        p = sub.add_parser(name)
        p.set_defaults(run=function)
        p.add_argument("--version", required=True)
        p.add_argument("--root", default=str(ROOT))
        for option in required:
            p.add_argument(option, required=True)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
