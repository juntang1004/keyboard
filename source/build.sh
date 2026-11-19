#!/bin/bash
# 사용: ./build.sh <원작자 도록키보드v5.5.0.apk> <android.jar 경로>
# 필요: JDK 17, apktool.jar, r8.jar(d8), uber-apk-signer.jar, python 3  (이 폴더에 jar 3개를 두면 됨)
set -e
ORIG="$1"; AJ="$2"; H="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$H/work" && mkdir -p "$H/work/cls" "$H/work/dex"
java -jar "$H/apktool.jar" d -f "$ORIG" -o "$H/work/dec"
python "$H/prep_v550.py" "$H/work/dec"
python "$H/trim_sounds.py" "$H/work/dec/res/raw"
javac --release 8 -cp "$AJ" -encoding UTF-8 -d "$H/work/cls" "$H"/src/com/thock/keyboard/*.java "$H"/stubs/com/thock/keyboard/*.java
# stubs는 컴파일용이라 빼고 dex로
for c in Prefs SoundEngine SoundKind Haptics ClipHistory; do rm -f "$H/work/cls/com/thock/keyboard/$c.class"; done
java -cp "$H/r8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$AJ" --output "$H/work/dex" $(find "$H/work/cls" -name "*.class")
java -jar "$H/apktool.jar" b "$H/work/dec" -o "$H/work/unsigned.apk"
cp "$H/work/dex/classes.dex" "$H/work/classes4.dex"
(cd "$H/work" && python -c "import zipfile;z=zipfile.ZipFile('unsigned.apk','a',zipfile.ZIP_DEFLATED);z.write('classes4.dex','classes4.dex');z.close()")
java -jar "$H/uber-apk-signer.jar" -a "$H/work/unsigned.apk" -o "$H/work/out"
echo "완료: $H/work/out"
