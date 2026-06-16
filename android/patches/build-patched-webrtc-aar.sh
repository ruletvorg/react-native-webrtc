#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION="137.0.1"
PATCH_NAME="stream-video-webrtc-android-${VERSION}-camble"
SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$SDK_DIR/platforms/android-36/android.jar"
ORIGINAL_AAR="${1:-}"

if [[ -z "$ORIGINAL_AAR" ]]; then
  ORIGINAL_AAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/io.getstream/stream-video-webrtc-android/$VERSION" -name "stream-video-webrtc-android-${VERSION}.aar" | head -1)"
fi

if [[ ! -f "$ORIGINAL_AAR" ]]; then
  echo "Original stream-video-webrtc-android AAR was not found." >&2
  exit 1
fi

if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "Android platform jar was not found at $ANDROID_JAR." >&2
  exit 1
fi

mkdir -p "$ANDROID_DIR/libs"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

unzip -q "$ORIGINAL_AAR" -d "$TMP_DIR/aar"

javac \
  -source 8 \
  -target 8 \
  -classpath "$TMP_DIR/aar/classes.jar:$ANDROID_JAR" \
  -d "$TMP_DIR/classes" \
  "$SCRIPT_DIR/webrtc/CameraEnumerationAndroid.java"

(
  cd "$TMP_DIR/classes"
  jar uf "$TMP_DIR/aar/classes.jar" org/webrtc/CameraEnumerationAndroid*.class
)

(
  cd "$TMP_DIR/aar"
  zip -qr "$ANDROID_DIR/libs/${PATCH_NAME}.aar" .
)

echo "$ANDROID_DIR/libs/${PATCH_NAME}.aar"
