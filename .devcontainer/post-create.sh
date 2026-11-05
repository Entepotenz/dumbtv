#!/usr/bin/env bash
set -euo pipefail

sudo -n apt-get update
sudo -n apt-get install -y --no-install-recommends \
    aapt adb apksigner ca-certificates curl git ripgrep unzip zip zipalign

case $(dpkg --print-architecture) in
    amd64) jdk_arch=x64 ;;
    arm64) jdk_arch=aarch64 ;;
    *)
        echo 'Unsupported build architecture' >&2
        exit 1
        ;;
esac

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$JAVA_HOME" "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"

if [[ ! -x $JAVA_HOME/bin/javac ]]; then
    curl -fL --retry 3 \
        "https://api.adoptium.net/v3/binary/version/jdk-17.0.21%2B9/linux/${jdk_arch}/jdk/hotspot/normal/eclipse" \
        -o "$work/jdk.tar.gz"
    tar -xzf "$work/jdk.tar.gz" --strip-components=1 -C "$JAVA_HOME"
    rm "$work/jdk.tar.gz"
fi

if [[ ! -f $ANDROID_HOME/platforms/android-25/android.jar ]]; then
    curl -fL --retry 3 \
        https://dl.google.com/android/repository/platform-25_r03.zip \
        -o "$work/platform.zip"
    unzip -q "$work/platform.zip" -d "$work"
    mv "$work/android-7.1.1" "$ANDROID_HOME/platforms/android-25"
    rm "$work/platform.zip"
fi

if [[ ! -x $ANDROID_HOME/build-tools/34.0.0/d8 ]]; then
    curl -fL --retry 3 \
        https://dl.google.com/android/repository/build-tools_r34-linux.zip \
        -o "$work/build-tools.zip"
    unzip -q "$work/build-tools.zip" -d "$work"
    mv "$work/android-14" "$ANDROID_HOME/build-tools/34.0.0"
    rm "$work/build-tools.zip"
fi

ln -sf /usr/bin/aapt2 "$ANDROID_HOME/build-tools/34.0.0/aapt2"
ln -sf /usr/bin/zipalign "$ANDROID_HOME/build-tools/34.0.0/zipalign"

"$JAVA_HOME/bin/javac" -version
"$ANDROID_HOME/build-tools/34.0.0/aapt2" version
"$ANDROID_HOME/build-tools/34.0.0/d8" --version
"$ANDROID_HOME/build-tools/34.0.0/apksigner" version
