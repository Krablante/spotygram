#!/usr/bin/env python3
"""Build the isolated Rust preview. All generated files stay in SPOTYGRAM_STATE."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import plistlib
import shutil
import struct
import subprocess
import sys
import tarfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = tomllib.loads((ROOT / "Cargo.toml").read_text())["package"]["version"]
MAKEPAD_REV = "44ecb822b48106fa76bde4dfd309e5a8a7ff1932"
WIDGET_FONTS = ("RobotoFlex.ttf", "IBMPlexSans-Text.ttf", "IBMPlexSans-SemiBold.ttf", "IBMPlexSans-Italic.ttf",
                "IBMPlexSans-BoldItalic.ttf", "fa-solid-900.ttf", "LXGWWenKaiRegular.ttf",
                "LXGWWenKaiBold.ttf", "NotoColorEmoji.ttf")
sys.dont_write_bytecode = True


def run(args, *, env=None, cwd=ROOT, capture=False):
    return subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None).stdout


def context():
    if hasattr(os, "sched_getaffinity"):
        allowed = sorted(os.sched_getaffinity(0))
        os.sched_setaffinity(0, set(allowed[:2]))
    configured = os.environ.get("SPOTYGRAM_STATE")
    if not configured:
        raise RuntimeError("Set SPOTYGRAM_STATE to a private build directory outside the source tree")
    state = Path(configured).resolve()
    if state == ROOT or ROOT in state.parents:
        raise RuntimeError("SPOTYGRAM_STATE must be outside source")
    output = state / "rust"
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update(SPOTYGRAM_STATE=str(state), CARGO_TARGET_DIR=str(output / "target"),
               CARGO_BUILD_JOBS="2", MAKEPAD_BUNDLE_NAME="spotygram rust",
               MAKEPAD_BUNDLE_IDENTIFIER="app.spotygram.rust")
    return state, output, env


def makepad_source(env):
    metadata = json.loads(run(["cargo", "metadata", "--locked", "--format-version=1"], env=env, capture=True))
    package = next(p for p in metadata["packages"] if p["name"] == "makepad-widgets")
    if MAKEPAD_REV not in package["source"]:
        raise RuntimeError("Makepad does not match the pinned revision")
    return Path(package["manifest_path"]).parent.parent


def copy_tree(source, destination, *, ignore=None):
    shutil.copytree(source, destination, dirs_exist_ok=True, symlinks=True, ignore=ignore)


def resources(destination, upstream):
    # Match Makepad's crate-resource layout. Fonts are lazy, including non-Latin song titles.
    copy_tree(ROOT / "resources", destination / "spotygram_rust/resources", ignore=shutil.ignore_patterns("android"))
    widget_resources = destination / "makepad_widgets/resources"
    copy_tree(upstream / "widgets/resources", widget_resources,
              ignore=shutil.ignore_patterns("*.ttf", "*.otf"))
    # The pinned font_policy.rs International manifest; fallback faces stay lazy.
    for name in WIDGET_FONTS:
        shutil.copy2(upstream / "widgets/resources" / name, widget_resources / name)
    # The default theme's optional code font uses a legacy resource name.
    # Supply the unmodified OFL JetBrains font there as well.
    shutil.copy2(upstream / "widgets/resources/jetbrains_mono_variable.ttf", widget_resources / "LiberationMono-Regular.ttf")


def notices(destination, upstream, env):
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "LICENSE", destination / "Spotygram-LICENSE")
    copy_tree(ROOT / "resources/licenses", destination / "artwork")
    metadata = json.loads(run(["cargo", "metadata", "--locked", "--format-version=1"], env=env, capture=True))
    summary = []
    for package in metadata["packages"]:
        summary.append(f'{package["name"]} {package["version"]}: {package.get("license") or "see upstream notice"}\n{package.get("repository") or package.get("source") or "Spotygram source"}\n')
        source = Path(package["manifest_path"]).parent
        target = destination / "rust" / f'{package["name"]}-{package["version"]}'
        for file in source.iterdir():
            if file.is_file() and file.name.upper().startswith(("LICENSE", "NOTICE", "COPYING", "UNLICENSE")):
                target.mkdir(parents=True, exist_ok=True)
                shutil.copy2(file, target / file.name)
    (destination / "rust-dependencies.txt").write_text("\n".join(summary), encoding="utf-8")
    shutil.copy2(upstream / "LICENSE", destination / "Makepad-LICENSE")
    for file in (upstream / "widgets/resources").glob("*-OFL.txt"):
        shutil.copy2(file, destination / file.name)
    # Keep the exact copyright/license metadata of the fonts supplied by Makepad.
    fonts = []
    for source in (upstream / "widgets/resources",):
        for file in sorted(source.glob("*")):
            if file.suffix.lower() not in (".ttf", ".otf"):
                continue
            if file.name not in WIDGET_FONTS + ("jetbrains_mono_variable.ttf",):
                continue
            data = file.read_bytes()
            count = struct.unpack_from(">H", data, 4)[0]
            for index in range(count):
                tag, _, offset, _ = struct.unpack_from(">4sIII", data, 12 + index * 16)
                if tag != b"name":
                    continue
                _, records, strings = struct.unpack_from(">HHH", data, offset)
                texts = set()
                for record in range(records):
                    platform_id, _, _, name_id, length, start = struct.unpack_from(">HHHHHH", data, offset + 6 + record * 12)
                    if name_id in (0, 13, 14):
                        raw = data[offset + strings + start:offset + strings + start + length]
                        texts.add(raw.decode("utf-16-be" if platform_id in (0, 3) else "mac_roman", errors="replace"))
                fonts.append(file.name + "\n" + "\n".join(sorted(texts)))
    (destination / "font-notices.txt").write_text("\n\n".join(fonts), encoding="utf-8")


def reset_owned(path, owner):
    path = path.resolve()
    if not path.is_relative_to(owner.resolve()) or path == owner.resolve():
        raise RuntimeError(f"Refusing to replace a directory outside build state: {path}")
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)


def native_dependencies(target, state):
    spec = importlib.util.spec_from_file_location("spotygram_native", ROOT / "tools/desktop-dependencies.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    source = module.prepare(target, state)
    if not target.startswith("linux"):
        return source
    # Mesa and the window system must share the host ABI. The audio bundle's
    # older C++ runtime blocks Radeon drivers; its old Xlib also breaks EGL/X11.
    destination = state / "rust/native" / target
    identity = (source / ".complete").read_text() + "\nhost-graphics-runtime-v2"
    marker = destination / ".complete"
    if not marker.is_file() or marker.read_text() != identity:
        reset_owned(destination, state / "rust")
        copy_tree(source, destination, ignore=shutil.ignore_patterns(
            "libstdc++.so*", "libgcc_s.so*", "libX11*", "libXau*", "libXdmcp*",
            "libXext*", "libXfixes*", "libxcb*", "libwayland*", "libxkbcommon*"))
        marker.write_text(identity)
    return destination


def desktop(state, output, env, image_only):
    system = {"Linux": "linux", "Windows": "windows", "Darwin": "macos"}[platform.system()]
    arch = "arm64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"
    target = f"{system}-{arch}"
    native = native_dependencies(target, state)
    upstream = makepad_source(env)
    env["MAKEPAD_PACKAGE_DIR"] = "../Resources/makepad" if system == "macos" else "makepad"
    if system == "windows":
        from PIL import Image
        icons = output / "icons"
        icons.mkdir(exist_ok=True)
        icon = icons / "spotygram.ico"
        Image.open(ROOT / "resources/spotygram_launcher.png").save(icon, format="ICO", sizes=[(32,32),(48,48),(64,64),(128,128),(256,256)])
        env["SPOTYGRAM_WINDOWS_ICON"] = str(icon)
    run(["cargo", "build", "--release", "--locked", "-j", "2", "--bin", "spotygram-rust"], env=env)
    image = output / "images" / target
    reset_owned(image, output)
    executable = "spotygram-rust.exe" if system == "windows" else "spotygram-rust"
    if system == "macos":
        app = image / "spotygram rust.app" / "Contents"
        binary = app / "MacOS"
        binary.mkdir(parents=True)
        bundle_resources = app / "Resources"
        bundle_resources.mkdir()
        plist = dict(CFBundleName="spotygram rust", CFBundleDisplayName="spotygram rust",
                     CFBundleIdentifier="app.spotygram.rust", CFBundleExecutable=executable,
                     CFBundleVersion=VERSION, CFBundleShortVersionString=VERSION,
                     CFBundlePackageType="APPL", LSMinimumSystemVersion="14.0",
                     NSHighResolutionCapable=True, CFBundleIconFile="spotygram.icns")
        with (app / "Info.plist").open("wb") as stream:
            plistlib.dump(plist, stream)
        from PIL import Image
        Image.open(ROOT / "resources/spotygram_launcher.png").save(bundle_resources / "spotygram.icns", format="ICNS")
    else:
        binary = image
    shutil.copy2(output / "target/release" / executable, binary / executable)
    resource_root = bundle_resources if system == "macos" else binary
    if system == "macos":
        copy_tree(native, app / "Frameworks", ignore=shutil.ignore_patterns("licenses", ".complete"))
    else:
        copy_tree(native, binary / "native")
    resources(resource_root / "makepad", upstream)
    licenses = resource_root / "licenses"
    licenses.mkdir(exist_ok=True)
    if system == "macos" and (native / "licenses").is_dir():
        copy_tree(native / "licenses", licenses / "native")
    shutil.copy2(ROOT / "LICENSE", licenses / "Spotygram-LICENSE")
    for name in ("LICENSE", "LICENSE-MIT", "LICENSE-APACHE"):
        if (upstream / name).is_file():
            shutil.copy2(upstream / name, licenses / f"Makepad-{name}")
    notices(licenses, upstream, env)
    if system == "linux":
        real_binary = binary / "bin"
        real_binary.mkdir()
        (binary / executable).rename(real_binary / executable)
        # Resources and native libraries stay next to the executable for relocatable launch.
        (binary / "native").rename(real_binary / "native")
        (binary / "makepad").rename(real_binary / "makepad")
        launcher = binary / executable
        launcher.write_text('#!/bin/sh\nset -eu\nHERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nexport LD_LIBRARY_PATH="$HERE/bin/native${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"\nexec "$HERE/bin/spotygram-rust" "$@"\n')
        launcher.chmod(0o755)
        (binary / "spotygram-rust.desktop").write_text('[Desktop Entry]\nType=Application\nName=spotygram rust\nComment=Rust preview of the Telegram music player\nExec=spotygram-rust\nIcon=app.spotygram.rust\nCategories=AudioVideo;Audio;Player;\nStartupWMClass=app.spotygram.rust\n')
        shutil.copy2(ROOT / "resources/spotygram_launcher.png", binary / "app.spotygram.rust.png")
    if system == "macos":
        run(["codesign", "--force", "--deep", "--sign", "-", image / "spotygram rust.app"])
        run(["codesign", "--verify", "--deep", "--strict", image / "spotygram rust.app"])
    print(image)
    if image_only:
        return
    releases = output / "releases" / VERSION
    releases.mkdir(parents=True, exist_ok=True)
    name = f"spotygram-rust-{VERSION}-{target}"
    if system == "linux":
        artifact = releases / f"{name}.tar.gz"
        with tarfile.open(artifact, "w:gz", compresslevel=6) as archive:
            archive.add(image, arcname="spotygram-rust")
    else:
        artifact = releases / f"{name}.zip"
        if system == "macos":
            run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", image / "spotygram rust.app", artifact])
        else:
            with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED, compresslevel=6, strict_timestamps=False) as archive:
                for file in sorted(image.rglob("*")):
                    if file.is_file():
                        archive.write(file, Path("spotygram-rust") / file.relative_to(image))
    checksum(artifact)
    print(artifact)


def checksum(artifact):
    digest = hashlib.sha256()
    with artifact.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    artifact.with_name(artifact.name + ".sha256").write_text(f"{digest.hexdigest()}  {artifact.name}\n")


def android(state, output, env, abis):
    sdk = Path(env["ANDROID_HOME"]).resolve()
    ndk = sdk / "ndk/28.2.13676358"
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    if not tools.is_dir():
        raise RuntimeError("Android packaging currently requires the documented Linux build host and NDK r28c")
    upstream = makepad_source(env)
    generated = output / "android/generated"
    generated.mkdir(parents=True, exist_ok=True)
    reset_owned(generated / "java", output)
    reset_owned(generated / "res", output)
    copy_tree(upstream / "tools/cargo_makepad/src/android/java", generated / "java")
    # API 26 has physical keyboards but no InputDevice.isEnabled (added in 27).
    # Keep the pinned checkout intact; compatibility/lifetime hooks belong to
    # our staged Java adapter.
    activity = generated / "java/dev/makepad/android/MakepadActivity.java"
    code = activity.read_text()
    old = "&& device.isEnabled()"
    if code.count(old) != 1:
        raise RuntimeError("Review the Makepad API-26 keyboard compatibility guard")
    code = code.replace(old, "&& (Build.VERSION.SDK_INT < 27 || device.isEnabled())")
    anchor = "    @Override\n    protected void onDestroy() {"
    destroy = "if (!isRecreating) {\n            MakepadNative.activityOnDestroy();"
    if code.count(anchor) != 1 or code.count(destroy) != 1:
        raise RuntimeError("Review the Makepad activity/service lifetime hook")
    code = code.replace(anchor,
        "    protected boolean shouldShutdownNativeRuntime() { return true; }\n\n" + anchor)
    code = code.replace(destroy,
        "if (!isRecreating && shouldShutdownNativeRuntime()) {\n            MakepadNative.activityOnDestroy();")
    activity.write_text(code)
    copy_tree(upstream / "tools/cargo_makepad/src/android/res", generated / "res")
    reset_owned(generated / "assets/makepad", output)
    resources(generated / "assets/makepad", upstream)
    reset_owned(generated / "assets/licenses", output)
    notices(generated / "assets/licenses", upstream, env)
    targets = {"arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android", "aarch64"),
               "x86_64": ("x86_64-linux-android", "x86_64-linux-android", "x86_64")}
    for abi in abis:
        target, clang, arch = targets[abi]
        native_build = state / "native" / f"td-{abi}"
        if not (native_build / "CMakeCache.txt").is_file():
            native_env = dict(env, SPOTYGRAM_ABIS=abi)
            run(["bash", ROOT / "tools/build-native.sh"], env=native_env)
        # Reuse compiled TDLib objects, exposing its C API directly to Rust.
        run(["cmake", "--build", native_build, "--target", "tdjson", "-j", "2"], env=env)
        destination = generated / "jniLibs" / abi
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(native_build / "td/libtdjson.so", destination / "libtdjson.so")
        run([tools / "llvm-strip", "--strip-debug", "--strip-unneeded", destination / "libtdjson.so"])
        target_env = env.copy()
        target_env.update({f"CARGO_TARGET_{target.upper().replace('-', '_')}_LINKER": str(tools / f"{clang}26-clang"),
                           f"CC_{target}": str(tools / f"{clang}26-clang"),
                           f"CXX_{target}": str(tools / f"{clang}26-clang++"),
                           f"AR_{target}": str(tools / "llvm-ar"),
                           "ANDROID_NDK_ROOT": str(ndk), "ANDROID_API_LEVEL": "26",
                           "MAKEPAD_PACKAGE_DIR": "makepad",
                           "RUSTFLAGS": f'--cfg android_target="{arch}" -C link-arg=-Wl,-z,max-page-size=16384'})
        run(["cargo", "rustc", "--release", "--locked", "--lib", "--crate-type=cdylib", "--target", target, "-j", "2"], env=target_env)
        shutil.copy2(output / "target" / target / "release/libspotygram_rust.so", destination / "libmakepad.so")
    shutil.copy2(state / "tdlib-src/LICENSE_1_0.txt", generated / "assets/licenses/TDLib-LICENSE")
    shutil.copy2(state / "native/openssl/LICENSE.txt", generated / "assets/licenses/OpenSSL-LICENSE")
    run([ROOT / "gradlew", "-p", ROOT / "android", "--project-cache-dir", output / "android/gradle",
         "--no-daemon", "--max-workers=2", ":assembleRelease", ":lintRelease"], env=env)
    release = output / "releases" / VERSION
    release.mkdir(parents=True, exist_ok=True)
    for abi, suffix in [("arm64-v8a", "arm64"), ("x86_64", "x86_64")]:
        if abi not in abis:
            continue
        candidates = list((output / "android/build/outputs/apk/release").glob(f"*-{abi}-release.apk"))
        if len(candidates) != 1:
            raise RuntimeError(f"Cannot resolve the release APK for {abi}")
        artifact = release / f"spotygram-rust-{VERSION}-{suffix}.apk"
        shutil.copy2(candidates[0], artifact)
        checksum(artifact)
        print(artifact)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", action="store_true")
    parser.add_argument("--image-only", action="store_true")
    parser.add_argument("--native-only", action="store_true")
    parser.add_argument("--abi", action="append", choices=["arm64-v8a", "x86_64"])
    args = parser.parse_args()
    state, output, env = context()
    if args.native_only:
        system = {"Linux": "linux", "Windows": "windows", "Darwin": "macos"}[platform.system()]
        arch = "arm64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"
        print(native_dependencies(f"{system}-{arch}", state))
    elif args.android:
        android(state, output, env, args.abi or ["arm64-v8a", "x86_64"])
    else:
        desktop(state, output, env, args.image_only)


if __name__ == "__main__":
    main()
