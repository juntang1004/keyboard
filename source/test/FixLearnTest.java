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

        System.out.println(fail == 0 ? "OK" : "FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}
