#!/bin/bash
# Local build without Gradle: aapt2 + javac + dx + jarsigner (v1, targetSdk 29)
set -e
T=/home/claude/tools; P=/home/claude/jstether; B=/home/claude/build
AJAR=$T/ap/android-33/android.jar
rm -rf $B; mkdir -p $B/classes $B/gen
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.johorstudio.tether" android:versionCode="5" android:versionName="2.0">\n    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="29" />#' \
  $P/app/src/main/AndroidManifest.xml > $B/AndroidManifest.xml
$T/aapt2 compile --dir $P/app/src/main/res -o $B/res.zip
$T/aapt2 link -o $B/unsigned.apk -I $AJAR --manifest $B/AndroidManifest.xml $B/res.zip --java $B/gen
javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath $AJAR -d $B/classes \
  $(find $P/app/src -name '*.java') $(find $B/gen -name '*.java')
java -cp $T/dxbuild com.android.dx.command.Main --dex --min-sdk-version=26 --output=$B/classes.dex $B/classes
(cd $B && zip -q unsigned.apk classes.dex)
KS=$P/release.keystore
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass jstether -keypass jstether -alias jstether \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Johor Studio, O=Johor Studio, C=MY" >/dev/null 2>&1
cp $B/unsigned.apk $B/JSTether.apk
jarsigner -keystore $KS -storepass jstether -keypass jstether -sigalg SHA256withRSA -digestalg SHA-256 \
  $B/JSTether.apk jstether >/dev/null
jarsigner -verify $B/JSTether.apk | tail -1
ls -la $B/JSTether.apk
