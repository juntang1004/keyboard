import com.thock.keyboard.*;
import java.lang.reflect.*;
import java.util.*;

/** 지우고 다시 친 것에서 오타 쌍·터치 배우기 + 자동 교정 규칙 맞추기. 입력창 글은 손으로 적어 넣는다. */
public class FixLearnTest {
    static int fail;
    static FixLearn f;
    static String text = "";
    static List<String> got = new ArrayList<>();

    static void type(String id, String after) {             // 키 하나 입력 (after = 입력 뒤 커서 앞 글)
        f.beforeKey(text);
        text = after;
        char ch = id.equals("SP") ? ' ' : id.charAt(0);
        String kid = id.equals("SP") ? "SP" : "ㅃㅉㄸㄲㅆㅒㅖ".indexOf(ch) >= 0 ? String.valueOf("ㅂㅈㄷㄱㅅㅐㅔ".charAt("ㅃㅉㄸㄲㅆㅒㅖ".indexOf(ch))) : id;
        FixLearn.Result r = f.key(new FixLearn.Key(kid, ch, 0.1f, 0.2f), text);
        if (r != null) got.add(r.toString());
    }
    static void bs(String after) { f.beforeBackspace(text); text = after; }
    static void start(String pre) { f = new FixLearn(); text = pre; got.clear(); }
    static void typeAll(String keys, String... afters) { for (int i = 0; i < afters.length; i++) type(keys.substring(i, i + 1).equals(" ") ? "SP" : keys.substring(i, i + 1), afters[i]); }
    static void expect(String name, String want) {
        String g = got.toString();
        if (!g.equals(want)) { fail++; System.out.println("FAIL " + name + ": " + g + "   (기대 " + want + ")  " + FixLearn.lastSkip); }
        else System.out.println("ok   " + name + ": " + g);
    }

    public static void main(String[] a) throws Exception {
        // 1) 과 를 치려다 고ㅓ → 지우기 1번 → ㅏ (조합기가 '고'에 다시 붙여 '과'를 만듦)
        start("");
        typeAll("ㄱㅗㅓ", "ㄱ", "고", "고ㅓ");
        bs("고");
        type("ㅏ", "과");
        expect("고ㅓ → 과", "[고ㅓ→과 [ㅏ←ㅓ]]");

        // 1b) 조합기가 '고'에 다시 붙이지 않아 '고ㅏ'가 되는 경우도 같음
        start("");
        typeAll("ㄱㅗㅓ", "ㄱ", "고", "고ㅓ");
        bs("고");
        type("ㅏ", "고ㅏ");
        expect("고ㅓ → 고ㅏ 화면", "[고ㅓ→과 [ㅏ←ㅓ]]");

        // 2) 가지마 를 치려다 다짐 → 지우기 4번 (ㅁ ㅣ ㅈ 하나씩, 다 는 한 번에) → ㄱㅏㅈㅣㅁ
        start("오늘 ");
        typeAll("ㄷㅏㅈㅣㅁ", "오늘 ㄷ", "오늘 다", "오늘 닺", "오늘 다지", "오늘 다짐");
        bs("오늘 다지"); bs("오늘 다ㅈ"); bs("오늘 다"); bs("오늘 ");
        typeAll("ㄱㅏㅈㅣㅁ", "오늘 ㄱ", "오늘 가", "오늘 갖", "오늘 가지", "오늘 가짐");
        expect("다짐 → 가짐", "[다짐→가짐 [ㄱ←ㄷ, ㅏ, ㅈ, ㅣ, ㅁ]]");

        // 3) 기록 없이 시작해도 화면 글만으로 오타 쌍은 모음 (터치는 기록이 없으니 없음)
        start("안녕 돋");
        bs("안녕 도");
        type("ㅇ", "안녕 동");
        expect("기록 없이 돋 → 동", "[돋→동 []]");

        // 4) 아예 다른 말로 고쳐 씀: 가다 → 지우고 나무
        start("");
        typeAll("ㄱㅏㄷㅏ", "ㄱ", "가", "갇", "가다");
        bs("가"); bs("");
        typeAll("ㄴㅏㅁㅜ", "ㄴ", "나", "남", "나무");
        expect("다른 말로 고쳐 씀", "[]");

        // 5) 그대로 다시 침 → 배우지 않음
        start("");
        typeAll("ㄴㅏ", "ㄴ", "나");
        bs("ㄴ");
        type("ㅏ", "나");
        expect("그대로 다시 침", "[]");

        // 6) 겹모음: 과 를 치려다 고ㅓ… 가 아니라 궈 → 지우고 ㅏ? (ㅝ=ㅜㅓ, ㅘ=ㅗㅏ : 두 자모 다 바뀜 → 2/2 라 배우지 않음)
        start("");
        typeAll("ㄱㅜㅓ", "ㄱ", "구", "궈");
        bs("구"); bs("ㄱ");
        typeAll("ㅗㅏ", "고", "과");
        expect("궈 → 과 (바뀐 자모가 너무 많음)", "[]");

        // 7) 화면이 기록과 다르면 배우지 않음
        start("");
        typeAll("ㄱㅗ", "ㄱ", "고");
        text = "전혀 다른 글";
        bs("전혀 다른 그");
        type("ㅏ", "고ㅏ");
        expect("화면과 기록이 다름", "[]");

        // 8) 쌍자음(쉬프트): 깍 → 지우고 깎? ㄱ→ㄲ 은 같은 자리 키라 붙은 키 아님 → 배우지 않음
        start("");
        typeAll("ㄲㅏㄱ", "ㄲ", "까", "깍");
        bs("까");
        type("ㄲ", "깎");
        expect("깍 → 깎 (같은 자리)", "[]");

        // ---- 자동 교정 규칙 맞추기
        Field fr = Class.forName("com.thock.keyboard.Typo").getDeclaredField("rules"); fr.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, String> rules = (Map<String, String>) fr.get(null);
        rules.put("고ㅓ", "과"); rules.put("다짐", "가짐");
        Method mr = Class.forName("com.thock.keyboard.Typo").getDeclaredMethod("matchRule", String.class); mr.setAccessible(true);
        String[][] cases = {{"고ㅓ", "고ㅓ>과"}, {"고ㅓ일", "고ㅓ일>과일"}, {"고ㅓ를", "고ㅓ를>과를"}, {"학고ㅓ", "고ㅓ>과"}, {"다짐", "다짐>가짐"}, {"다짐하다", "다짐하다>가짐하다"}, {"각다짐", "-"}, {"과일", "-"}};
        for (String[] c : cases) {
            String[] m = (String[]) mr.invoke(null, c[0]);
            String g = m == null ? "-" : m[0] + ">" + m[1];
            if (!g.equals(c[1])) { fail++; System.out.println("FAIL 규칙 " + c[0] + ": " + g + " (기대 " + c[1] + ")"); }
            else System.out.println("ok   규칙 " + c[0] + ": " + g);
        }

        // ---- 자음·모음만으로 된 쌍은 모으지도, 규칙으로 쓰지도 않음
        Class<?> T = Class.forName("com.thock.keyboard.Typo");
        Method addPair = T.getDeclaredMethod("addPair", Class.forName("android.content.Context"), String.class, String.class); addPair.setAccessible(true);
        Field fl = T.getDeclaredField("learned"); fl.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Integer> learned = (Map<String, Integer>) fl.get(null);
        learned.clear();
        addPair.invoke(null, null, "ㄹ", "ㅎ"); addPair.invoke(null, null, "ㅂㅈ", "ㅈㅂ"); addPair.invoke(null, null, "욘규", "연규");
        if (!learned.keySet().toString().equals("[욘규	연규]")) { fail++; System.out.println("FAIL 자모 쌍 거르기: " + learned.keySet()); } else System.out.println("ok   자모 쌍 거르기: " + learned.keySet());
        rules.put("ㄹ", "ㅎ");
        String[] m = (String[]) mr.invoke(null, "ㄹ");
        if (m != null) { fail++; System.out.println("FAIL 자모 규칙 무시"); } else System.out.println("ok   자모 규칙 무시");
        Method addRule = T.getDeclaredMethod("addRule", String.class, String.class); addRule.setAccessible(true);
        boolean r1 = (Boolean) addRule.invoke(null, "ㅂ", "ㅈ"), r2 = (Boolean) addRule.invoke(null, "부담오", "부담고");
        if (r1 || !r2) { fail++; System.out.println("FAIL addRule " + r1 + " " + r2); } else System.out.println("ok   addRule: ㅂ 거부, 부담오 등록");

        // ---- 한 글자 오타 제외, 깨진 글자는 바뀐 부분만, 이미 고치는 규칙이 있으면 모으지 않음
        rules.clear();
        learned.clear();
        addPair.invoke(null, null, "키", "티"); addPair.invoke(null, null, "인", "안"); addPair.invoke(null, null, "고", "오");
        addPair.invoke(null, null, "해보ㅓ", "해봐"); addPair.invoke(null, null, "허ㅏ면", "화면"); addPair.invoke(null, null, "감수해고", "감수해도");
        String lk = new TreeSet<>(learned.keySet()).toString().replace('	', '>');
        String want = "[감수해고>감수해도, 보ㅓ>봐, 허ㅏ>화]";
        if (!lk.equals(want)) { fail++; System.out.println("FAIL 모으기: " + lk + " (기대 " + want + ")"); } else System.out.println("ok   모으기: " + lk);
        learned.clear();
        rules.put("보ㅓ", "봐");
        addPair.invoke(null, null, "해보ㅓ", "해봐"); addPair.invoke(null, null, "보ㅓ서", "봐서");
        if (!learned.isEmpty()) { fail++; System.out.println("FAIL 이미 고치는 규칙: " + learned.keySet()); } else System.out.println("ok   보ㅓ 규칙이 있으면 해보ㅓ·보ㅓ서 안 모음");
        Method cov = T.getDeclaredMethod("coveredBy", String.class); cov.setAccessible(true);
        Object cb = cov.invoke(null, "해보ㅓ");
        if (!"보ㅓ".equals(cb)) { fail++; System.out.println("FAIL coveredBy: " + cb); } else System.out.println("ok   coveredBy(해보ㅓ) = 보ㅓ");
        boolean r3 = (Boolean) addRule.invoke(null, "인", "안");
        if (r3) { fail++; System.out.println("FAIL 한 글자 규칙 등록됨"); } else System.out.println("ok   한 글자 규칙 거부");
        // 예전에 모인 목록 정리
        rules.clear();
        learned.clear();
        learned.put("키	티", 1); learned.put("해보ㅓ	해봐", 2); learned.put("보ㅓ	봐", 1); learned.put("ㄹ	ㅎ", 3); learned.put("유투	유튜", 1);
        Method clean = T.getDeclaredMethod("cleanLearned"); clean.setAccessible(true); clean.invoke(null);
        String cl = new TreeMap<>(learned).toString().replace('	', '>');
        if (!cl.equals("{보ㅓ>봐=3, 유투>유튜=1}")) { fail++; System.out.println("FAIL 정리: " + cl); } else System.out.println("ok   정리: " + cl);

        System.out.println(fail == 0 ? "OK" : "FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}
