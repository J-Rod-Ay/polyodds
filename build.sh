#!/bin/bash
# Gradle-free build: aapt2 -> javac -> d8 -> zipalign -> apksigner. Run from WSL.
set -e
cd "$(dirname "$0")"
WIN_LOCAL=$(wslpath "$(cmd.exe /c 'echo %LOCALAPPDATA%' 2>/dev/null | tr -d '\r')")
SDK=${ANDROID_SDK:-$WIN_LOCAL/Android/Sdk}   # override with ANDROID_SDK=/path
BT=$SDK/build-tools/36.1.0
JDK="/mnt/c/Program Files/Android/Android Studio/jbr/bin"
JAR=$(wslpath -w "$SDK/platforms/android-34/android.jar")
rm -rf build && mkdir -p build/gen build/classes build/dex
$BT/aapt2.exe compile --dir res -o build/res.zip
$BT/aapt2.exe link -I "$JAR" --manifest AndroidManifest.xml --java build/gen -A assets -o build/base.apk build/res.zip \
  --min-sdk-version 22 --target-sdk-version 30 --version-code 2 --version-name 2.0
"$JDK/javac.exe" -nowarn --release 8 -classpath "$JAR" -d build/classes \
  $(find src build/gen -name '*.java') 2>&1 | grep -v "warning:" || true
"$JDK/java.exe" -cp "$(wslpath -w $BT/lib/d8.jar)" com.android.tools.r8.D8 --release --min-api 22 --lib "$JAR" --output build/dex $(find build/classes -name '*.class') 
python3 -c "
import zipfile,shutil;shutil.copy('build/base.apk','build/unsigned.apk')
zipfile.ZipFile('build/unsigned.apk','a').write('build/dex/classes.dex','classes.dex')"
[ -f debug.keystore ] || "$JDK/keytool.exe" -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PolyOdds" >/dev/null 2>&1
$BT/zipalign.exe -f 4 build/unsigned.apk build/aligned.apk
"$JDK/java.exe" -jar "$(wslpath -w $BT/lib/apksigner.jar)" sign --ks debug.keystore --ks-pass pass:android --out PolyOdds.apk build/aligned.apk
ls -la PolyOdds.apk
