# 도록 키보드 수정판 — 맞춤법 제안 기능 추가 작업 지시문

## 0. 첨부 파일과 시작 방법
이 지시문과 함께 `dorok-keyboard-handoff.zip` 을 첨부했다. 압축을 풀면:
- `base.apk` : 빌드의 바탕이 되는 원작자 v5.5.0 기반 수정판 apk (다른 사람이 만든 오픈 소스 키보드 "도록 키보드"). 이 apk에서 `classes4.dex`, `AndroidManifest.xml`, `assets/typo_lm.bin` 만 바꿔 넣고 다시 서명해서 결과 apk를 만든다.
- `README.txt` : 지금까지 넣은 기능 전체 목록 (한국어).
- `source/` : 지금까지 작업한 소스 전체. **마지막으로 사용자에게 전달한 버전(`dorok-keyboard-v5.5.0-mod-learn.apk`)과 똑같은 상태**다.
- `HANDOFF.md` : 이 문서.

작업 폴더 예: `~/kbd` 에 풀고, 아래 2장의 빌드를 **먼저 한 번 그대로 돌려서** 지금 상태가 재현되는지 확인한 뒤 기능을 추가한다.

사용자는 한국어로 대화한다. 답변은 한국어, 짧고 정확하게. 사용자는 폰(삼성 갤럭시)에서 apk를 설치해 직접 써 보고 스크린샷으로 피드백을 준다. 이 환경에는 폰·에뮬레이터가 없으니 **기기에서 확인하지 못한 것은 확인 못 했다고 분명히 말할 것.**

## 1. 프로젝트 구조
패키지 `com.thock.keyboard`. 원본 앱의 클래스(KeyboardService, Prefs, MainActivity 등)는 apk 안의 classes.dex~classes3.dex 에 있고, 우리가 만든 코드는 전부 `classes4.dex` 로 들어간다. 원본 클래스는 리플렉션으로 접근한다.

| 파일 | 역할 |
|---|---|
| `source/src/com/thock/keyboard/Ext.java` | 키보드(IME) 쪽 확장. 툴바(편집·문구·클립보드·최근복사칩·오타제안칩·마이크·커서·설정), 자판(천지인/나랏글/쿼티, 삼성 배열), 터치 처리, 오타 제안 연결(`typoHost`, `typoTouch`, `typoBefore`, `typoAfter`), 터치 보정 연결(`nearestKey`, `calibGeo`, `calibNoteDown/Typed`) |
| `source/src/com/thock/keyboard/Typo.java` | 오타 제안·자동 교정 엔진. 음절 2-gram 언어모델(`assets/typo_lm.bin`), 스페이스↔ㅌㅊㅍㅠㅜㅡ 뒤바뀜 후보, 내 단어/고정 바꾸기/고치지 않을 말, 되돌리기(`lastFix`/`Done`, `undo`) |
| `source/src/com/thock/keyboard/Calib.java` | 오타 학습·터치 보정. 따라 쓰기(Trainer), 샘플(출처 T/N/F/?), 추천 범위(최근 위주 가중), 직접 그린 범위(격자 `paint`), 설정 화면 그림(MapView) |
| `source/src/com/thock/keyboard/SettingsUi.java` | 설정 앱 화면 전체 (페이지: home, layout, clip, typo, typo_words, typo_rules, calib, calib_train, calib_result, calib_detail …). 공용 위젯: `card`, `switchRow`, `navRow`, `note`, `primaryBtn`, `ghostBtn`, `chipBtn`, `field`, `itemRow`, `onText` |
| `source/src/com/thock/keyboard/LineIcon.java` | 선 아이콘 |
| `source/stubs/…` | 원본 클래스 컴파일용 껍데기 (dex에 안 들어감) |
| `source/test/*.java` | JVM 테스트 (CalibTest, TypoTest, PaintTest, NormalTest 등) |
| `source/mod2/build_mod2.sh` | 빌드 스크립트 |
| `source/mod2/repack.py` | apk 안 파일 교체·추가 + zip 정렬 |
| `source/mod2/axml_add_queries.py` | 바이너리 매니페스트에 `<queries>` 추가 (구글 음성입력용) |
| `source/mod2/sign/thock-mod.p12` | 서명 키 (PKCS12, 비밀번호 `android`, alias `thockmod`). **반드시 이 키로 서명해야 사용자 폰에 덮어쓰기 설치가 된다.** |
| `source/mod2/typo_lm.bin`, `lm_build.py`, `tfr.py` | 오타 제안용 언어모델과 그 생성 스크립트 (위키백과 wiki40b ko + FrequencyWords ko 자막 빈도) |

설정값은 SharedPreferences `thock_ext` 에 저장한다 (예: `typo_on`, `typo_auto`, `typo_auto_uu`, `typo_learn`, `typo_words`, `typo_rules`, `typo_block`, `calib_*`, `tb_*`).

## 2. 빌드 방법 (도구 받기 → 빌드 → 검증)
필요: JDK 17 이상, python3, (테스트용) 아무 JVM.
```bash
mkdir -p ~/kbd/tools && cd ~/kbd/tools
# android.jar 대용 (컴파일·D8 라이브러리, JVM 테스트 런타임)
curl -L -o android-all.jar https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
# D8 (dex 변환)
curl -L -o r8lib.jar https://storage.googleapis.com/r8-releases/raw/8.5.35/r8lib.jar
# apk 서명
curl -L -o apksig.jar https://repo1.maven.org/maven2/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar
```
빌드:
```bash
cd ~/kbd
bash source/mod2/build_mod2.sh $PWD/base.apk $PWD/tools/android-all.jar $PWD/tools/r8lib.jar $PWD/tools/apksig.jar $PWD/out/dorok-keyboard-v5.5.0-mod-spell.apk
rm -rf source/mod2/work
```
마지막 줄에 `verified=true v1=false v2=true` 가 나오면 성공.
Windows(Git Bash)에서도 그대로 된다: `PY=python bash source/mod2/build_mod2.sh ...` (클래스패스는 스크립트가 알아서 변환). 
맞춤법 목록은 빌드 때 `spell_build.py` 가 `spell_list.tsv` → `assets/spell.tsv` 로 바꿔 넣는다 (맞춤법 제안 작업 완료, README 참고).

알아둔 함정:
- apksig 2.3.0 의 v1 서명은 JDK 21에서 깨진다 → 스크립트는 v2 서명만 쓴다 (`--add-exports java.base/sun.security.x509=ALL-UNNAMED …` 옵션 포함). minSdk 26 이라 v2만으로 충분.
- 컴파일은 `javac --release 8 -encoding UTF-8`. 람다는 D8이 처리.
- JVM 테스트에서 `android.util.Log` 를 부르면 네이티브 오류로 죽는다 → 순수 로직은 Log 없이, 또는 Context null 일 때 일찍 return.
- `android.os.Handler` 를 static 필드에서 바로 만들면 JVM 테스트에서 죽는다 → 지연 생성(`Typo.H()` 참고).
- 한글 출력이 깨지면 `java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`.

JVM 테스트 실행 예:
```bash
cd ~/kbd && mkdir -p build/tcls
javac --release 8 -nowarn -cp tools/android-all.jar -encoding UTF-8 -d build/tcls source/src/com/thock/keyboard/Calib.java source/src/com/thock/keyboard/Typo.java source/test/TypoTest.java
java -Dstdout.encoding=UTF-8 -cp build/tcls:tools/android-all.jar TypoTest source/mod2/typo_lm.bin
```

## 3. 이번 작업: 맞춤법 제안 (사용자가 정한 범위)
사용자 결정:
1. **"항상 틀린 형태" 목록 방식(1안)만** 넣는다. 문맥 규칙(되/돼 일반, 안/않, 로서/로써 등 앞뒤를 봐야 하는 것), 사전 기반 철자 검사, 띄어쓰기 검사, 인터넷 맞춤법 검사기 연동은 **하지 않는다.**
2. 목록 자료는 **인터넷에서 찾아서** 만든다.
3. 툴바에 오타 제안과 맞춤법 제안이 **같이 생기면 오타 제안을 우선해서 하나만** 보여준다.
4. 자동 교정은 하지 않고 **제안만** (툴바 칩을 누르면 바뀜).

### 3-1. 자료 조사와 목록 만들기
- "항상 틀린 형태" = 표준어에 그 형태가 아예 없어서 문맥과 관계없이 틀린 것. 예: 되요→돼요, 됬→됐, 할께→할게, 몇일→며칠, 어떻해→어떡해, 금새→금세, 왠만하면→웬만하면, 오랫만→오랜만, 설겆이→설거지, 희안하다→희한하다, 역활→역할, 일일히→일일이, 깨끗히→깨끗이, 어의없다→어이없다, 무릎쓰다→무릅쓰다, 바램(희망 뜻)… 
- 출처 후보: 국립국어원(표준국어대사전, 우리말샘, 온라인가나다, "자주 틀리는 맞춤법"/"헷갈리기 쉬운 말" 자료), 한국어 어문 규범(kornorms.korean.go.kr), 신뢰할 만한 정리 글. 공개 데이터셋(GitHub 등)을 쓰면 **라이선스를 확인**하고 README에 출처를 적는다.
- **중요: 틀린 형태가 실제로는 맞는 말인 경우를 반드시 걸러낸다.** 예: "바램"은 '바래다(색이 변하다)'의 명사형으로는 맞음 → 넣지 않거나 아주 신중히. "로써/로서", "데/대", "맞추다/맞히다", "되/돼(일반)", "안/않"처럼 문맥 의존인 것은 1안 범위가 아니므로 제외. 가능하면 각 틀린 형태가 표준국어대사전·우리말샘에 표제어나 활용형으로 없는지 확인한다. (한국어 hunspell 사전 `spellcheck-ko/hunspell-dict-ko` 를 빌드 시 검증용으로만 쓰는 것도 방법: 틀린 형태가 사전에서 틀림으로 나오고 바른 형태가 맞음으로 나오는지 대조. 앱에는 넣지 않는다.)
- 활용형은 규칙으로 펼친다: 예) 어간 오류 "됬"→"됐" 는 됬다/됬어/됬는데/됬고… 전부, "되요"→"돼요" 처럼 어미 결합형. 목록 원본은 사람이 읽을 수 있는 텍스트(예: `source/mod2/spell_list.tsv`: `틀린형태<TAB>바른형태<TAB>메모/출처`)로 두고, 빌드 때 앱용 파일(예: `assets/spell.bin` 또는 tsv 그대로)로 만든다. `build_mod2.sh` 와 `repack.py` 는 이미 `assets/파일=경로` 인자로 새 파일을 추가할 수 있다 (typo_lm.bin 이 그렇게 들어감).
- 목표: 확실한 항목 200~300개 + 펼친 활용형. 용량은 수십~수백 KB 이내.

### 3-2. 동작
- 검사 시점: 띄어쓰기·마침표·쉼표·?·!·엔터로 **어절이 끝난 직후**, 방금 끝난 어절(커서 앞, 경계 문자 앞의 어절)을 목록에서 찾는다. 해시 조회 한 번이라 지연 없음. (치는 도중에는 검사하지 않는다: 어절이 아직 안 끝났으므로.)
- 어절 안에서 일치를 찾는 방식: 어절 전체가 일치하거나, 어절이 "틀린형태 + 조사/어미"로 시작하는 경우 등. 오탐이 없도록 보수적으로. 예) "됬어" 안의 "됬" 는 항상 틀림 → 어절 어디에 있어도 고침. 반대로 짧은 형태가 다른 단어의 일부가 될 수 있는 항목은 어절 전체 일치만.
- **모든 한글 자판(천지인·나랏글·쿼티)에서 동작**해야 한다. 오타 제안은 키 입력 기록을 쓰는 쿼티 전용이지만, 맞춤법은 `InputConnection.getTextBeforeCursor` 로 입력창 글을 보고 검사하므로 자판과 무관하다. 지금 `Ext.typoBefore` → `Typo.beforeBoundary(host, ctx, koq)` 는 쿼티가 아니면 고정 바꾸기만 하고 return 하고, `Ext.typoAfter` 는 쿼티가 아니면 `Typo.reset()` 한다. 맞춤법 검사는 이 흐름과 별도로, 경계 키가 실제로 입력된 **뒤**(`cjPress` 다음)에 실행되게 연결할 것. 천지인·나랏글의 스페이스(K_SPACE), 나랏글 쉼표(K_COMMA), 구두점(K_PUNCT), 엔터(K_ENTER), K_TEXT 구두점 모두.
- 툴바 표시: 기존 오타 제안 칩 `tbFix` 를 같이 쓴다. 표시 예: `되요 → 돼요` 또는 바른 형태만. **오타 제안이 떠 있거나 새로 뜨면 오타 제안 우선**, 맞춤법 제안은 오타 제안이 없을 때만. 다음 키를 누르면(글자가 바뀌면) 맞춤법 제안은 사라진다.
- 칩을 누르면: 커서 앞 텍스트가 아직 그 어절 + 경계 문자 그대로인지 다시 확인하고, 틀린 부분만 바른 형태로 바꾼다 (경계 문자는 유지). `Typo.replaceTail` 과 같은 방식(`beginBatchEdit` / `deleteSurroundingText` / `commitText`).
- **되돌리기**: 바꾼 직후 지우기를 누르면 원래대로 + 그 항목(또는 그 어절)을 "고치지 않기로 한 말"에 추가해 다시 제안하지 않는다. 기존 `Typo.lastFix` / `Done` / `undo()` / `block` 구조를 재사용하거나 맞춤법 전용으로 같은 패턴을 만든다. 기존 되돌리기는 고친 뒤 경계 문자 하나가 더 들어온 경우도 처리한다(`armed`).
- 비밀번호·이메일·URL 입력칸에서는 하지 않는다 (`Typo.usable(host)` 재사용).
- 고정 바꾸기(사용자 규칙)가 같은 어절을 이미 바꿨다면 맞춤법 제안은 생략.

### 3-3. 설정 화면 (SettingsUi 의 `pageTypo` 근처)
- "맞춤법 제안" 스위치 (기본 켜짐), 설명: 자주 틀리는 맞춤법을 툴바에 제안, 모든 한글 자판에서 동작, 자동으로 바꾸지 않음.
- "맞춤법 목록" 페이지: 내장 항목 전체를 검색 가능한 목록으로 (틀린 형태 → 바른 형태), 항목별로 끄기(제외) 가능, 제외한 항목·되돌려서 막힌 항목을 다시 켜기. 기존 `pageTypoWords` / `fillWords` / `itemRow` / `field` / `onText` 패턴 참고 (검색 칸은 사용자 요구로 목록 화면에 필수).
- 홈 메뉴의 "오타 제안 · 자동 교정" 요약에 맞춤법 상태도 짧게.
- README.txt 에 새 기능과 자료 출처를 추가.

### 3-4. 테스트 (JVM)
- 목록 검증 테스트: 모든 "바른 형태"가 목록에 다시 "틀린 형태"로 들어가 있지 않은지(순환 없음), 틀린 형태와 바른 형태가 다른지, 중복 없음.
- 검사 함수 테스트: 예문 여러 개 — 틀린 어절은 제안이 나오고(예: "오늘 되요 " → 돼요, "어떻해 " → 어떡해, "됬어 " → 됐어), 바른 문장·흔한 채팅 문장 수백 개에서는 제안이 0건인지(오탐률). 바른 문장 자료는 `source/mod2/lm_build.py` 가 쓰는 위키/자막 코퍼스를 다시 받아 쓰거나, 인터넷에서 구한 일반 문장을 쓴다: wiki40b ko `https://storage.googleapis.com/tfds-data/datasets/wiki40b/ko/1.3.0/wiki40b-validation.tfrecord-00000-of-00001` (tfr.py 로 읽음), 자막 어절 빈도 `https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/ko/ko_full.txt`. **위키 본문에서 제안이 뜨는 항목은 실제로 맞는 말일 가능성이 크니 목록에서 빼거나 조건을 좁힌다.**
- 기존 테스트(CalibTest, TypoTest, PaintTest, NormalTest)도 다시 돌려 깨지지 않았는지 확인.

## 4. 전달 방법 (사용자 요구사항)
- 결과 apk 와 함께, 모바일에서 `.apk` 직접 다운로드가 안 됐던 적이 있어서 **apk를 무압축 zip으로 감싼 파일도 같이** 보낸다: `zip -0 dorok-keyboard-spell-apk.zip dorok-keyboard-v5.5.0-mod-spell.apk`.
- 소스 묶음도 갱신해서 보낸다: `zip -r dorok-keyboard-mod-source.zip README.txt source` (작업 폴더 `source/mod2/work` 는 지우고).
- 파일은 사용자에게 파일 전송 도구로 첨부(첨부 형태, render 아님).
- 같은 서명 키이므로 사용자는 이전 수정판 위에 덮어쓰기 설치 가능.
- 보고에는: 바뀐 점, 테스트 결과 수치(오탐률 등), 자료 출처, 폰에서 확인하지 못한 점을 간단히.

## 5. 참고: 지금까지의 주요 기능 (README.txt 에 상세)
- 툴바: 편집·문구·클립보드(3번째) + 최근 복사 5자 칩(누르면 붙여넣기) + 마이크(구글 음성 입력으로 바로 전환) + 커서 이동 + 설정.
- 오타 학습·터치 보정(쿼티): 따라 쓰기 14문장, 학습 결과(반투명 점·겹칠수록 진하게, 키별 범위 표시, 모든 키 보기), 추천 범위(모든 키/선택 키/그린 범위 지우고), 터치 범위 상세 설정(이동·크기 슬라이더, 직접 그리기: 그리기/지우개/되돌리기, 붓 크기, 확대·이동), 평소 타자 일부 기록(지운 터치 제외), 기록 출처 구분, 최근 위주 가중, 세 가지 비교.
- 오타 제안(쿼티 한글): 스페이스↔윗줄 키 뒤바뀜. ㅠ·ㅜ 오타는 확실하면 자동 교정(기본 켜짐), 나머지는 제안. 내 단어(검색), 고정 바꾸기(검색), 되돌리기.
