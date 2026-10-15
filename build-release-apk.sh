#!/bin/bash
# Release APK build for Pour: aapt2 + javac + d8 + apksigner, signed with
# release.keystore. No Gradle.
#
# Native voice engine: whisper.cpp (MIT), built from source in
# native/whisper.cpp + native/whisper-jni. The prebuilt .so lives in
# native/whisper-jni/prebuilt/<abi>/; rebuild it with
# native/whisper-jni/build-native.sh when the native sources change.
# The whisper model (ggml-small.en-q5_1.bin) ships in assets/.
#
# Output: pour-<version>-release.apk (version comes from AndroidManifest.xml)
set -e
APP="$HOME/workspace/pour-kroko"
BT="$HOME/tooling/android-sdk/build-tools/35.0.1"
AJAR="$HOME/tooling/android-sdk/platforms/android-36/android.jar"
export JAVA_HOME="$HOME/tooling/jdk-17"
export PATH="$JAVA_HOME/bin:$BT:$PATH"
BUILD="$APP/build-release"
WHISPER_PREBUILT="$APP/native/whisper-jni/prebuilt"
MOONSHINE_PREBUILT="$APP/native/moonshine-prebuilt"
SHERPA_PREBUILT="$APP/native/sherpa-prebuilt"
KOTLIN_JAR="$APP/libs/kotlin-stdlib.jar"
SHERPA_JAR="$APP/libs/sherpa-classes.jar"
KEYSTORE="$APP/pour-pro-release.keystore"
CREDS="$APP/KEYSTORE_CREDENTIALS.txt"
STORE_PASS=$(grep '^Store password:' "$CREDS" | awk '{print $3}')
[ -n "$STORE_PASS" ] || { echo "FATAL: store password not found" >&2; exit 1; }
VER=$(grep -o 'android:versionName="[^"]*"' "$APP/AndroidManifest.xml" | cut -d'"' -f2)
ABISUFFIX=""
[ -n "${ABIS:-}" ] && [ "$ABIS" != "$(ls "$WHISPER_PREBUILT" 2>/dev/null | tr '\n' ' ')" ] && ABISUFFIX="-$ABIS"
OUT="$APP/pour-$VER$ABISUFFIX-release.apk"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen"

echo "--- 0. check sherpa prebuilt .so"
[ -f "$SHERPA_PREBUILT/arm64-v8a/libsherpa-onnx-jni.so" ] || { echo "FATAL: libsherpa-onnx-jni.so missing" >&2; exit 1; }
echo "sherpa .so: $(ls -la "$SHERPA_PREBUILT/arm64-v8a/libsherpa-onnx-jni.so" | awk '{print $5}') bytes"

echo "--- 1. aapt2 compile + link (assets include the whisper model)"
"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/compiled_res.zip"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" \
  --manifest "$APP/AndroidManifest.xml" \
  -I "$AJAR" \
  --java "$BUILD/gen" \
  --min-sdk-version 23 --target-sdk-version 36 \
  -A "$APP/assets" \
  "$BUILD/compiled_res.zip"

echo "--- 2. javac"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJAR" \
  -cp "$KOTLIN_JAR:$SHERPA_JAR" -d "$BUILD/classes" \
  $(find "$APP/src" "$BUILD/gen" -name '*.java')
echo "classes: $(find $BUILD/classes -name '*.class' | wc -l)"

echo "--- 3. d8"
d8 --min-api 23 --lib "$AJAR" --output "$BUILD/dex" \
  "$KOTLIN_JAR" \
  "$SHERPA_JAR" \
  $(find "$BUILD/classes" -name '*.class')

echo "--- 4. add classes.dex"
(cd "$BUILD/dex" && zip -q "$BUILD/app-unsigned.apk" classes.dex)

echo "--- 5. add native libs for ALL ABIs (uncompressed, page-aligned later)"
mkdir -p "$BUILD/stage"
ABIS="${ABIS:-$(ls "$SHERPA_PREBUILT" | tr '\n' ' ')}"
for abi in $ABIS; do
  mkdir -p "$BUILD/stage/lib/$abi"
  cp "$SHERPA_PREBUILT/$abi/"*.so "$BUILD/stage/lib/$abi/"
done
(cd "$BUILD/stage" && zip -q -0 -r "$BUILD/app-unsigned.apk" lib)
unzip -l "$BUILD/app-unsigned.apk" | grep "\.so" || true

echo "--- 6. zipalign + sign (release keystore)"
"$BT/zipalign" -f -p 4 "$BUILD/app-unsigned.apk" "$OUT"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$STORE_PASS" --out "$OUT" "$OUT"

echo "--- 7. verify"
"$BT/apksigner" verify --print-certs "$OUT" | head -3
"$BT/zipalign" -c -p 4 "$OUT" && echo "ZIPALIGN OK"
"$BT/aapt2" dump badging "$OUT" | head -3
echo "dex whisper: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/pour/dictation/pro/WhisperEngine;') (expect >=1)"
echo "dex no sherpa: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/k2fsa/sherpa/onnx/OnlineRecognizer;') (expect 0)"
echo "dex RecordButton: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/pour/dictation/pro/RecordButton;') (expect >=1)"
echo "dex RecordService: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/pour/dictation/pro/RecordService;') (expect >=1)"
echo "dex DraftStore: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/pour/dictation/pro/DraftStore;') (expect >=1)"
echo "dex ParaBreaks: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/pour/dictation/pro/ParaBreaks;') (expect >=1)"
echo "dex ProjectsActivity: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/pour/dictation/pro/ProjectsActivity;') (expect >=1)"
echo "dex ProjectActivity: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/pour/dictation/pro/ProjectActivity;') (expect >=1)"
echo "dex NoteDialogs: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/pour/dictation/pro/NoteDialogs;') (expect >=1)"
echo "dex Folder: $(strings "$BUILD/dex/classes.dex" | grep -c 'Lcom/pour/dictation/pro/Folder;') (expect >=1)"
echo "dex has Pour splash text: $(strings "$BUILD/dex/classes.dex" | grep -c 'Speak, and Pour writes it down') (expect >=1)"
echo "dex has no Spill splash text: $(strings "$BUILD/dex/classes.dex" | grep -c 'Talk, and Spill writes it down') (expect 0)"
ls -la "$OUT"
