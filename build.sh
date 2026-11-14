#!/bin/bash
# Compila o TV Aberta (Java puro, sem Gradle) e gera out/tvaberta.apk.
# Precisa do JDK 21 e do Android SDK com platforms;android-34, build-tools;34.0.0 e build-tools;35.0.0
# (o d8 do 34 quebra com classes do JDK 21, por isso o d8 vem do 35).
# Ajuste ANDROID_HOME e JDK_BIN se os seus estiverem em outro lugar.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/c/Tools/android-sdk}
JDK=${JDK_BIN:-"/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot/bin"}
BT=$SDK/build-tools/34.0.0; D8=$SDK/build-tools/35.0.0/d8; JAR=$SDK/platforms/android-34/android.jar
EXE=""; W() { echo "$1"; }
if command -v cygpath >/dev/null; then EXE=".exe"; W() { cygpath -w "$1"; }; D8="$D8.bat"; fi
BAT=${EXE:+.bat}; SEP=":"; [ -n "$EXE" ] && SEP=";"
export PATH="$JDK:$PATH"; export JAVA_HOME="$(dirname "$JDK")"

rm -rf out; mkdir -p out/classes out/dex
"$BT/aapt2$EXE" compile --dir res -o out/res.zip
"$BT/aapt2$EXE" link -o out/base.apk -I "$(W "$JAR")" --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 34 out/res.zip
"$JDK/javac$EXE" -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$(W "$JAR")$SEP$(W "$BT/core-lambda-stubs.jar")" -d out/classes $(find src -name '*.java')
(cd out/classes && "$JDK/jar$EXE" cf ../classes.jar .)
"$D8" --min-api 21 --release --lib "$(W "$JAR")" --output out/dex out/classes.jar
cp out/base.apk out/unsigned.apk; (cd out/dex && "$BT/aapt$EXE" add ../unsigned.apk classes.dex >/dev/null)
"$BT/zipalign$EXE" -f 4 out/unsigned.apk out/aligned.apk

# Chave de assinatura: criada no primeiro build. Guarde key.jks: atualizações precisam da mesma chave.
KS=${KEYSTORE:-key.jks}; PASS=${KEYSTORE_PASS:-tvhome123}
[ -f "$KS" ] || "$JDK/keytool$EXE" -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias app \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvaberta" >/dev/null 2>&1
"$BT/apksigner$BAT" sign --ks "$KS" --ks-pass "pass:$PASS" --out out/tvaberta.apk out/aligned.apk
echo "OK: out/tvaberta.apk"
