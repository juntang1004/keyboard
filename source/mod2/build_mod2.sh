#!/bin/bash
# 사용: ./build_mod2.sh <도록키보드v5.5.0-수정판.apk> <android.jar> <r8.jar> <apksig.jar> <결과.apk>
# 수정판 apk에서 classes4.dex(이 소스)와 AndroidManifest.xml(<queries>에 구글 음성 입력 패키지 추가)만 바꾸고
# assets/typo_lm.bin(오타 제안)·assets/spell.tsv(맞춤법 목록)·assets/emoji.tsv(이모지 목록)를 넣은 뒤 v2 서명한다.
# 필요: JDK 17+, python 3. r8.jar = https://storage.googleapis.com/r8-releases/raw/8.5.35/r8lib.jar
#       apksig.jar = Maven Central com.android.tools.build:apksig:2.3.0 (v2 서명만 사용)
set -e
cpath() { case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) cygpath -wp "$1";; *) echo "$1";; esac; }; PY="${PY:-python3}"
BASE="$1"; AJ="$2"; R8="$3"; SIG="$4"; OUT="$5"; H="$(cd "$(dirname "$0")" && pwd)"; S="$H/.."
W="$H/work"; rm -rf "$W" && mkdir -p "$W/cls" "$W/dex" "$W/sign"
javac --release 8 -nowarn -cp "$AJ" -encoding UTF-8 -d "$W/cls" "$S"/src/com/thock/keyboard/*.java "$S"/stubs/com/thock/keyboard/*.java
for c in Prefs SoundEngine SoundKind Haptics ClipHistory; do rm -f "$W/cls/com/thock/keyboard/$c.class"; done
java -cp "$R8" com.android.tools.r8.D8 --release --min-api 26 --lib "$AJ" --output "$W/dex" $(find "$W/cls" -name "*.class")
(cd "$W" && unzip -q -o "$BASE" AndroidManifest.xml -d man)
"$PY" "$H/axml_add_queries.py" "$W/man/AndroidManifest.xml" "$W/AndroidManifest.q.xml" com.google.android.googlequicksearchbox com.google.android.tts
# 버전: 설치 화면·설정 맨 아래에 보이는 이름과 번호를 빌드마다 올린다 (빌드 번호 = git 커밋 수 + 1, 이 빌드를 커밋하면 그 번호가 됨)
N=$(git -C "$S" rev-list --count HEAD 2>/dev/null || echo 0); N=$((N + 1))
VN="${VERSION_NAME:-$(cat "$H/VERSION")}"; VC="${VERSION_CODE:-$((1000 + N))}"
"$PY" "$H/axml_set_version.py" "$W/AndroidManifest.q.xml" "$W/AndroidManifest.xml" "$VN" "$VC"
echo "version: $VN / $VC"
"$PY" "$H/spell_build.py" "$H/spell_list.tsv" "$W/spell.tsv"      # 맞춤법 목록 원본 → 앱용
"$PY" "$H/repack.py" "$BASE" "$W/unsigned.apk" "classes4.dex=$W/dex/classes.dex" "AndroidManifest.xml=$W/AndroidManifest.xml" "assets/typo_lm.bin=$H/typo_lm.bin" "assets/spell.tsv=$W/spell.tsv" "assets/emoji.tsv=$H/emoji.tsv"
javac -nowarn -encoding UTF-8 -cp "$SIG" -d "$W/sign" "$H"/sign/*.java
J="--add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED"
java $J -cp "$(cpath "$SIG:$W/sign")" Sign "$H/sign/thock-mod.p12" android thockmod "$W/unsigned.apk" "$OUT"
java $J -cp "$(cpath "$SIG:$W/sign")" Verify "$OUT"
