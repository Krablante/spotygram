#!/usr/bin/env python3
"""Sign and archive a desktop application image for Spotygram's update channel."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import stat
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def openssl():
    if platform.system() == "Darwin":
        # Apple's LibreSSL has no pkeyutl -rawin; Homebrew supplies OpenSSL 3.
        for prefix in ("/opt/homebrew", "/usr/local"):
            executable = Path(prefix) / "opt/openssl@3/bin/openssl"
            if executable.is_file():
                return str(executable)
    return "openssl"


def signing_key(state):
    directory = state / "desktop" / "signing"
    directory.mkdir(parents=True, exist_ok=True)
    private = directory / "release-private.pem"
    if not private.exists():
        subprocess.run([openssl(), "genpkey", "-algorithm", "ED25519", "-out", str(private)], check=True)
        private.chmod(0o600)
    return private


def public_key(private):
    return subprocess.check_output([openssl(), "pkey", "-in", str(private), "-pubout"]).decode()


def public_der(text):
    return base64.b64decode("".join(line.strip() for line in text.splitlines()
        if not line.startswith("-----")), validate=True)


def checksum(file):
    value = hashlib.sha256()
    with file.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def package(image, target, version, state, destination):
    private = signing_key(state)
    expected_public = (ROOT / "desktop/src/main/resources/update-key.pub").read_text()
    if public_der(public_key(private)) != public_der(expected_public):
        raise RuntimeError("Desktop release key differs from the application's trusted key")
    image = image.resolve()
    if image.name not in ("Spotygram", "Spotygram.app") or not image.is_dir():
        raise RuntimeError("Expected a complete Spotygram application image")
    entries = []
    for file in sorted(image.rglob("*")):
        relative = file.relative_to(image).as_posix()
        if file.is_dir() and not file.is_symlink():
            continue
        if file.is_symlink():
            resolved = file.resolve()
            if not resolved.is_relative_to(image):
                raise RuntimeError(f"External application symlink: {relative}")
            entries.append({"path": relative, "link": os.readlink(file)})
        else:
            entries.append({"path": relative, "sha256": checksum(file),
                            "executable": bool(file.stat().st_mode & stat.S_IXUSR)})
    metadata = state / "desktop" / "manifests" / target / version
    metadata.mkdir(parents=True, exist_ok=True)
    manifest = metadata / ".spotygram-bundle.json"
    manifest.write_text(json.dumps({"format": 1, "application": "app.spotygram", "version": version,
        "target": target, "root": image.name, "files": entries}, ensure_ascii=False, separators=(",", ":")))
    signature = metadata / ".spotygram-bundle.sig"
    subprocess.run([openssl(), "pkeyutl", "-sign", "-rawin", "-inkey", str(private),
                    "-in", str(manifest), "-out", str(signature)], check=True)
    destination.mkdir(parents=True, exist_ok=True)
    name = f"spotygram-{version}-{target}"
    if target.startswith("linux"):
        artifact = destination / f"{name}.tar.gz"
        with tarfile.open(artifact, "w:gz", compresslevel=6) as archive:
            archive.add(image, arcname=image.name)
            archive.add(manifest, arcname=manifest.name)
            archive.add(signature, arcname=signature.name)
    else:
        artifact = destination / f"{name}.zip"
        with zipfile.ZipFile(artifact, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for file in sorted(image.rglob("*")):
                relative = f"{image.name}/{file.relative_to(image).as_posix()}"
                if file.is_symlink():
                    item = zipfile.ZipInfo(relative)
                    item.create_system = 3
                    item.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(item, os.readlink(file).encode())
                elif file.is_file():
                    archive.write(file, relative)
            archive.write(manifest, manifest.name)
            archive.write(signature, signature.name)
    print(artifact)
    print(f"SHA256 {checksum(artifact)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, default=Path(os.environ.get("SPOTYGRAM_STATE",
        Path.home() / ".local/state/spotygram")))
    parser.add_argument("--init-key", action="store_true")
    parser.add_argument("--image", type=Path)
    parser.add_argument("--target", choices=("linux-x64", "windows-x64", "macos-x64", "macos-arm64"))
    parser.add_argument("--version")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.init_key:
        print(public_key(signing_key(args.state.resolve())), end="")
    else:
        if not all((args.image, args.target, args.version, args.output)):
            parser.error("--image, --target, --version and --output are required")
        package(args.image, args.target, args.version, args.state.resolve(), args.output.resolve())
