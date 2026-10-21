#!/usr/bin/env bash
# Builds Jot.apk without Gradle: aapt2 -> javac -> d8 -> zip -> apksigner.
# Needs: JDK 17+, and in $TOOLS: android.jar (API 34), aapt2, d8.jar (any jar containing com.android.tools.r8.D8), apksigner.jar
set -euo pipefail
cd "$(dirname "$0")"
TOOLS="${TOOLS:-../tools}"
# shellcheck disable=SC1091
source ./version.txt   # VERSION_CODE / VERSION_NAME — bump these to release an update
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes,dex}

echo "[1/6] compiling resources"
"$TOOLS/aapt2" compile --dir res -o "$OUT/res/compiled.zip"

echo "[2/6] linking resources"
"$TOOLS/aapt2" link -I "$TOOLS/android.jar" --manifest AndroidManifest.xml \
  --java "$OUT/gen" --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o "$OUT/unsigned.apk" "$OUT/res/compiled.zip"

echo "[3/6] compiling java"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 \
  -classpath "$TOOLS/android.jar" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] dexing"
java -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$TOOLS/android.jar" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[5/6] packaging"
cp "$OUT/unsigned.apk" "$OUT/app.apk"
(cd "$OUT/dex" && zip -q -j ../app.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$OUT/app.apk" "$OUT/aligned.apk" 2>/dev/null || cp "$OUT/app.apk" "$OUT/aligned.apk"

echo "[6/6] signing"
# The signing key is never committed. Point JOT_KEYSTORE at it and put the password in JOT_KEY_PASS.
: "${JOT_KEYSTORE:=jot-release.jks}"
: "${JOT_KEY_PASS:?set JOT_KEY_PASS to the keystore password}"
java -jar "$TOOLS/apksigner.jar" sign --ks "$JOT_KEYSTORE" --ks-pass env:JOT_KEY_PASS \
  --ks-key-alias "${JOT_KEY_ALIAS:-jot}" --key-pass env:JOT_KEY_PASS --out Jot.apk "$OUT/aligned.apk"
java -jar "$TOOLS/apksigner.jar" verify --verbose Jot.apk | head -5
ls -la Jot.apk
