"""Verify an installable release APK before publishing it. No signing secrets needed."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
CERTIFICATE_SHA256 = "d82b422fb10342a91bd8589a2659e354797b375b0c48383ee05aa29900b2a32e"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--check-version", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "release")
    args = parser.parse_args()
    version = re.search(r'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"',
                        (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")).group(1)
    if args.tag != f"v{version}":
        raise SystemExit("Release tag does not match the app version")
    notes = ROOT / "release-notes" / f"{args.tag}.md"
    if not notes.is_file() or not notes.read_text(encoding="utf-8").strip():
        raise SystemExit("User-facing English release notes are required")
    if args.check_version:
        print(f"Version and notes ready: {args.tag}")
        return
    if args.apk is None or not args.apk.is_file():
        raise SystemExit("A signed APK is required")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise SystemExit("ANDROID_HOME is required for APK verification")
    build_tools = Path(sdk) / "build-tools/35.0.0"
    windows = os.name == "nt"
    apksigner = build_tools / ("apksigner.bat" if windows else "apksigner")
    aapt = build_tools / ("aapt.exe" if windows else "aapt")
    signature = subprocess.check_output([str(apksigner), "verify", "--print-certs", str(args.apk)], text=True)
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    if digests != [CERTIFICATE_SHA256]:
        raise SystemExit("APK signer differs from the existing app: upgrade would fail")
    manifest = subprocess.check_output([str(aapt), "dump", "badging", str(args.apk)], text=True, encoding="utf-8")
    if "application-debuggable" in manifest:
        raise SystemExit("Debug APKs must not be published as release builds")
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", manifest)
    expected_code = re.search(r"versionCode\s*=\s*(\d+)", (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")).group(1)
    if not package or package.groups() != ("space.megaworld.claudeusage", expected_code, version):
        raise SystemExit("APK identity or version differs from this release")
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output / f"tinyGlyph-{version}.apk"
    shutil.copy2(args.apk, destination)
    print(f"Verified installable release: {destination.name}")


if __name__ == "__main__":
    main()
