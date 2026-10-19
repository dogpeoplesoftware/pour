#!/usr/bin/env bash
#
# build-native-from-source.sh — Build sherpa-onnx native libraries from source
# for Android arm64-v8a. This replaces the prebuilt binaries in
# native/sherpa-prebuilt/ with a reproducible from-source build, as required
# for F-Droid inclusion (their policy: binaries must come from source
# compilation, not prebuilt drops).
#
# Proven working 2026-10-19 with:
#   - sherpa-onnx v1.13.5 (Apache 2.0, https://github.com/k2-fsa/sherpa-onnx)
#   - Android NDK r27c
#   - onnxruntime 1.27.1 (downloaded automatically by sherpa-onnx's script
#     from csukuangfj/onnxruntime-libs GitHub releases)
#   - cmake, make, wget, unzip, JDK 17
#
# Produces:
#   - libsherpa-onnx-jni.so  (all 133 com.k2fsa.sherpa.onnx JNI entry points;
#                             symbol-identical to the v1.13.5 prebuilts)
#   - libsherpa-onnx-c-api.so
#   - libonnxruntime.so      (byte-identical to the prebuilt; downloaded,
#                             not compiled — same upstream release)
#
# Usage:
#   ./build-native-from-source.sh [output-dir]
#
#   output-dir defaults to ./native/sherpa-from-source/arm64-v8a
#
# Requirements (env or defaults):
#   ANDROID_NDK  — path to Android NDK (default: ~/tooling/android-sdk/ndk/android-ndk-r27c)
#   JAVA_HOME    — JDK 17+ (default: ~/tooling/jdk-17)
#
set -e

SHERPA_VERSION="v1.13.5"
ABI="arm64-v8a"

OUT_DIR="${1:-$PWD/native/sherpa-from-source/$ABI}"

if [ -z "${ANDROID_NDK:-}" ]; then
  ANDROID_NDK="$HOME/tooling/android-sdk/ndk/android-ndk-r27c"
fi
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$HOME/tooling/jdk-17"
fi

for tool in cmake make wget unzip git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FATAL: $tool not found" >&2; exit 1; }
done
[ -d "$ANDROID_NDK" ] || { echo "FATAL: ANDROID_NDK not found: $ANDROID_NDK" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "=== Cloning sherpa-onnx $SHERPA_VERSION ==="
git clone --depth 1 --branch "$SHERPA_VERSION" \
  https://github.com/k2-fsa/sherpa-onnx.git "$WORK/sherpa-onnx"

echo "=== Building for $ABI (this takes ~10 minutes) ==="
cd "$WORK/sherpa-onnx"
export ANDROID_NDK JAVA_HOME
export SHERPA_ONNX_ENABLE_C_API=ON
# BUILD_SHARED_LIBS defaults to ON: produces the .so set our app expects.
./build-android-arm64-v8a.sh

SRC="$WORK/sherpa-onnx/build-android-arm64-v8a/install/lib"
for f in libsherpa-onnx-jni.so libsherpa-onnx-c-api.so libonnxruntime.so; do
  [ -f "$SRC/$f" ] || { echo "FATAL: expected output missing: $f" >&2; exit 1; }
done

echo "=== Verifying JNI symbols ==="
# The app calls into com.k2fsa.sherpa.onnx.*; all entry points must exist.
count=$(nm -D --defined-only "$SRC/libsherpa-onnx-jni.so" 2>/dev/null \
  | grep -c "Java_com_k2fsa" || true)
[ "$count" -ge 130 ] || { echo "FATAL: only $count JNI symbols (expected ~133)" >&2; exit 1; }
echo "JNI symbols OK: $count"

echo "=== Installing to $OUT_DIR ==="
mkdir -p "$OUT_DIR"
cp -f "$SRC/libsherpa-onnx-jni.so" "$SRC/libsherpa-onnx-c-api.so" \
      "$SRC/libonnxruntime.so" "$OUT_DIR/"
ls -lh "$OUT_DIR/"

echo "=== DONE ==="
echo "Point build-release-apk.sh at $OUT_DIR (or copy over native/sherpa-prebuilt/$ABI)."
