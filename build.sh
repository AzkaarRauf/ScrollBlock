#!/usr/bin/env bash
# Builds a signed APK without Gradle, using only aapt, javac, dx, zipalign and apksigner.
# Debian/Ubuntu: apt install aapt dalvik-exchange zipalign apksigner openjdk-17-jdk
# Needs an android.jar (API 33): set ANDROID_JAR or keep the default path below.
set -euo pipefail
cd "$(dirname "$0")"

PKG=dev.scrollblock
VERSION_CODE=1
VERSION_NAME=1.0
ANDROID_JAR=${ANDROID_JAR:-$HOME/.android-sdk/platforms/android-33/android.jar}
KEYSTORE=${KEYSTORE:-signing/scrollblock.jks}
KS_PASS=${KS_PASS:?set KS_PASS to the keystore password}
DX=$(command -v d8 || command -v dalvik-exchange || command -v dx)
SRC=app/src/main
OUT=build-manual

rm -rf "$OUT" && mkdir -p "$OUT"/{gen,classes}

# The Gradle-style manifest has no package attribute; add it for aapt.
sed "s|<manifest |<manifest package=\"$PKG\" |" $SRC/AndroidManifest.xml > "$OUT/AndroidManifest.xml"

aapt package -f -M "$OUT/AndroidManifest.xml" -S $SRC/res -I "$ANDROID_JAR" -J "$OUT/gen" \
    --min-sdk-version 21 --target-sdk-version 33 \
    --version-code $VERSION_CODE --version-name $VERSION_NAME \
    -F "$OUT/unsigned.apk"

javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
    $(find $SRC/java "$OUT/gen" -name '*.java')

case "$DX" in
  *d8) "$DX" --release --min-api 21 --lib "$ANDROID_JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class') ;;
  *)   "$DX" --dex --min-sdk-version=21 --output="$OUT/classes.dex" "$OUT/classes" ;;
esac
(cd "$OUT" && aapt add -f unsigned.apk classes.dex >/dev/null)

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
      -alias scrollblock -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ScrollBlock" >/dev/null 2>&1
  echo "Created $KEYSTORE – keep it: updates must be signed with the same key."
fi

zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --v4-signing-enabled false --ks "$KEYSTORE" --ks-pass pass:"$KS_PASS" --out ScrollBlock.apk "$OUT/aligned.apk"
apksigner verify ScrollBlock.apk
ls -l ScrollBlock.apk
