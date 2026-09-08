#!/usr/bin/env bash
set -euo pipefail
ROOT=$(dirname "$(dirname "$(realpath "$0")")")
: "${SPOTYGRAM_STATE:?Set SPOTYGRAM_STATE to a directory outside the source tree}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
NDK="$ANDROID_HOME/ndk/28.2.13676358"
JOBS=${SPOTYGRAM_JOBS:-2}
TD_COMMIT=d1085f9cebc5a62379991ae1652673954f229c1f
mkdir -p "$SPOTYGRAM_STATE/native" "$SPOTYGRAM_STATE/jniLibs"
if [ ! -d "$SPOTYGRAM_STATE/tdlib-src/.git" ]; then
  git clone https://github.com/tdlib/td.git "$SPOTYGRAM_STATE/tdlib-src"
fi
git -C "$SPOTYGRAM_STATE/tdlib-src" checkout "$TD_COMMIT"
if [ ! -d "$SPOTYGRAM_STATE/native/openssl/.git" ]; then
  git clone --depth 1 --branch openssl-3.5.6 https://github.com/openssl/openssl.git "$SPOTYGRAM_STATE/native/openssl"
fi
export ANDROID_NDK_ROOT="$NDK"
export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
TD="$SPOTYGRAM_STATE/tdlib-src/example/android"
cmake -S "$TD" -B "$SPOTYGRAM_STATE/native/generate" -GNinja -DTD_ANDROID_JSON_JAVA=ON -DTD_GENERATE_SOURCE_FILES=ON
cmake --build "$SPOTYGRAM_STATE/native/generate" -j "$JOBS"
for ABI in ${SPOTYGRAM_ABIS:-arm64-v8a x86_64}; do
  case "$ABI" in
    arm64-v8a) SSL_TARGET=android-arm64 ;;
    x86_64) SSL_TARGET=android-x86_64 ;;
    *) exit 2 ;;
  esac
  PREFIX="$SPOTYGRAM_STATE/native/openssl-$ABI"
  if [ ! -f "$PREFIX/lib/libcrypto.a" ]; then
    mkdir -p "$SPOTYGRAM_STATE/native/ssl-build-$ABI"
    (cd "$SPOTYGRAM_STATE/native/ssl-build-$ABI"
      "$SPOTYGRAM_STATE/native/openssl/Configure" "$SSL_TARGET" no-shared no-tests -D__ANDROID_API__=26 --prefix="$PREFIX" --libdir=lib
      make -j"$JOBS"
      make install_sw)
  fi
  cmake -S "$TD" -B "$SPOTYGRAM_STATE/native/td-$ABI" -GNinja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DOPENSSL_ROOT_DIR="$PREFIX" -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DANDROID_ABI="$ABI" -DANDROID_STL=c++_static -DANDROID_PLATFORM=android-26 \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--thinlto-jobs=$JOBS -Wl,--threads=$JOBS" \
    -DTD_ANDROID_JSON_JAVA=ON
  cmake --build "$SPOTYGRAM_STATE/native/td-$ABI" --target tdjni -j "$JOBS"
  mkdir -p "$SPOTYGRAM_STATE/jniLibs/$ABI"
  cp "$SPOTYGRAM_STATE/native/td-$ABI/libtdjsonjava.so" "$SPOTYGRAM_STATE/jniLibs/$ABI/"
done
printf 'TDLib %s built. Libraries: %s/jniLibs\n' "$TD_COMMIT" "$SPOTYGRAM_STATE"
