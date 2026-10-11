#!/usr/bin/env python3
"""Fetch pinned TDLib/libmpv runtimes. Native code is supplied as verified prebuilts."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import tarfile
import urllib.request
import zipfile

TD_VERSION = "0.1008067.0"
MPV_RELEASE = "20260906162952"
TARGETS = {
    "linux-x64": (
        "linux-x64-glibc",
        "sha512-ILSNFbjP/gRWNc0bTtyTOX1RW854vaUcSpc6GtXJQp7Hgwr/+m1GbzhU4hry4yeRk02EfsmqDaBQVdXSjBXgZg==",
        "libmpv-0.41.0-linux-x86_64-lgpl-20260906162952.tar.gz",
        "202b6c16e272f47cfc261023b23551d70db34598519978b8621ead33bde9bb17",
    ),
    "windows-x64": (
        "win32-x64",
        "sha512-t5zVRgaQYXzoDXdUnLn0j/+MeHCagci3J5Bo2gpHlrqy0EFeNkXXewcyZ/4HduZxVvLSC41dHFt7gAcoV0DBjQ==",
        "libmpv-0.41.0-windows-x86_64-lgpl-20260906162952.zip",
        "bff9608485b17c1520f96d8b759bcae23b4afb9891c70a7dfdf3486567d9e9c7",
    ),
    "macos-x64": (
        "darwin-x64",
        "sha512-tqSe7xBg1/5RzzTc7PlzYP45KTC0cwV3ZKvUqZvHHODrDzzWnwqltteQ/+3Fvk386NcwFRI4y5jHot6XvCH/3A==",
        "libmpv-0.41.0-macos-x86_64-lgpl-20260906162952.tar.gz",
        "82656156deb11593302d7e0fe794cf6e0ab2794911bd111b634ef1552163a8c2",
    ),
    "macos-arm64": (
        "darwin-arm64",
        "sha512-dFefumR1NX2SSYf9p6fXyeLxw1+EL00Qe86ECqbtllWdP476gseScqU34hSzaR4RYsIuQZJDjaYGt1v2YiI7HA==",
        "libmpv-0.41.0-macos-arm64-lgpl-20260906162952.tar.gz",
        "e315991fce615c531f9bc1e7e44b0c14112051fc3c754a798d20b149838e7c88",
    ),
}


def current_target():
    system = {"Linux": "linux", "Windows": "windows", "Darwin": "macos"}[platform.system()]
    arch = "arm64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"
    return f"{system}-{arch}"


def digest(path, algorithm):
    value = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.digest()


def download(url, destination, algorithm, expected):
    if destination.is_file() and digest(destination, algorithm) == expected:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "Spotygram-build"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
        if digest(partial, algorithm) != expected:
            raise RuntimeError(f"Checksum mismatch: {destination.name}")
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)


def unpack(archive, directory):
    directory.mkdir(parents=True, exist_ok=True)
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as source:
            for item in source.infolist():
                target = (directory / item.filename).resolve()
                if not target.is_relative_to(directory.resolve()):
                    raise RuntimeError("Invalid archive path")
            source.extractall(directory)
    else:
        with tarfile.open(archive) as source:
            source.extractall(directory, filter="data")


def prepare(target, state):
    package, td_integrity, mpv_name, mpv_sha = TARGETS[target]
    destination = state / "desktop" / "deps" / target
    identity = json.dumps([TD_VERSION, td_integrity, mpv_name, mpv_sha])
    marker = destination / ".complete"
    if marker.is_file() and marker.read_text() == identity:
        print(destination)
        return destination
    cache = state / "desktop" / "downloads"
    metadata_url = f"https://registry.npmjs.org/@prebuilt-tdlib%2f{package}/{TD_VERSION}"
    with urllib.request.urlopen(metadata_url, timeout=30) as response:
        metadata = json.load(response)
    if metadata["dist"]["integrity"] != td_integrity:
        raise RuntimeError("TDLib release integrity differs from the pinned value")
    td_archive = cache / f"tdlib-{target}-{TD_VERSION}.tar.gz"
    download(metadata["dist"]["tarball"], td_archive, "sha512",
             base64.b64decode(td_integrity.removeprefix("sha512-")))
    mpv_archive = cache / mpv_name
    download(f"https://github.com/jason-yau/mpv-prebuilt/releases/download/{MPV_RELEASE}/{mpv_name}",
             mpv_archive, "sha256", bytes.fromhex(mpv_sha))
    staging = state / "desktop" / "unpack" / target
    if staging.exists():
        shutil.rmtree(staging)
    unpack(td_archive, staging / "tdlib")
    unpack(mpv_archive, staging / "mpv")
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    td_names = list((staging / "tdlib").rglob("*tdjson*"))
    td_binary = next(path for path in td_names if path.is_file() and
                     (path.name.endswith(".dll") or ".so" in path.name or path.name.endswith(".dylib")))
    td_output = "tdjson.dll" if target.startswith("windows") else (
        "libtdjson.dylib" if target.startswith("macos") else "libtdjson.so")
    shutil.copy2(td_binary, destination / td_output)
    for path in (staging / "mpv").rglob("*"):
        if path.is_file() and (".so" in path.name or path.name.endswith((".dll", ".dylib"))):
            output = destination / path.name
            if path.is_symlink():
                output.symlink_to(os.readlink(path))
            else:
                shutil.copy2(path, output)
        elif path.is_dir() and path.name == "licenses":
            shutil.copytree(path, destination / "licenses", dirs_exist_ok=True)
    for name in ("LICENSE", "LICENSE.md", "LICENSE.txt"):
        license_file = staging / "tdlib" / "package" / name
        if license_file.is_file():
            (destination / "licenses").mkdir(exist_ok=True)
            shutil.copy2(license_file, destination / "licenses" / "TDLib-license.txt")
    if target.startswith("macos") and not (destination / "libmpv.dylib").exists():
        library = next(destination.glob("libmpv.*.dylib"))
        (destination / "libmpv.dylib").symlink_to(library.name)
    marker.write_text(identity)
    shutil.rmtree(staging)
    print(destination)
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=TARGETS, default=current_target())
    parser.add_argument("--state", type=Path, default=Path(os.environ.get("SPOTYGRAM_STATE",
        Path.home() / ".local" / "state" / "spotygram")))
    arguments = parser.parse_args()
    prepare(arguments.target, arguments.state.resolve())
