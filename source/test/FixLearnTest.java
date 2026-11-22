import com.thock.keyboard.FixLearn;
import java.util.*;

/** 지우고 다시 친 것에서 오타 배우기 (사용자가 든 예 그대로). 입력창 글은 손으로 적어 넣는다. */
public class FixLearnTest {
    static int fail;
    static FixLearn f;
    static String text = "";
    static List<FixLearn.Learned> got = new ArrayList<>();

    static void type(String id, String after) {             // 키 하나 입력 (after = 입력 뒤 커서 앞 글)
        f.beforeKey(text);
        text = after;
        char ch = id.equals("SP") ? ' ' : id.charAt(0);
        List<FixLearn.Learned> r = f.key(new FixLearn.Key(id, ch, 0.1f, 0.2f));
        if (r != null) got.addAll(r);
    }
    static void bs(String after) { f.beforeBackspace(text); text = after; }
    static void start(String pre) { f = new FixLearn(); text = pre; got.clear(); }
    static void expect(String name, String want) {
        String g = got.toString();
        if (!g.equals(want)) { fail++; System.out.println("FAIL " + name + ": " + g + " (기대 " + want + ")"); }
        else System.out.println("ok   " + name + ": " + g);
    }

    public static void main(String[] a) {
        // 1) 과 를 치려다 고ㅓ → 지우기 1번 → ㅏ
        start("");
        type("ㄱ", "ㄱ"); type("ㅗ", "고"); type("ㅓ", "고ㅓ");
        bs("고");
        type("ㅏ", "과");
        expect("고ㅓ → 과", "[ㅏ←ㅓ]");

        // 2) 가지마 를 치려다 다짐 → 지우기 4번 (ㅁ ㅣ ㅈ 하나씩, 다 는 한 번에) → ㄱㅏㅈㅣㅁ
        start("오늘 ");
        type("ㄷ", "오늘 ㄷ"); type("ㅏ", "오늘 다"); type("ㅈ", "오늘 닺"); type("ㅣ", "오늘 다지"); type("ㅁ", "오늘 다짐");
        bs("오늘 다지"); bs("오늘 다ㅈ"); bs("오늘 다"); bs("오늘 ");
        type("ㄱ", "오늘 ㄱ"); type("ㅏ", "오늘 가"); type("ㅈ", "오늘 갖"); type("ㅣ", "오늘 가지"); type("ㅁ", "오늘 가짐");
        expect("다짐 → 가짐", "[ㄱ←ㄷ, ㅏ, ㅈ, ㅣ, ㅁ]");

        // 3) 앞 어절까지 이어서: 안녕 하셰요 → ㅔ 를 ㅖ 로? (ㅖ는 쉬프트라 안 따라감) 대신 ㅇㄷ 오타: 하세ㅛ → ㅛ 지우고 ㅇ
        start("");
        for (String[] k : new String[][]{{"ㅎ", "ㅎ"}, {"ㅏ", "하"}, {"SP", "하 "}, {"ㅅ", "하 ㅅ"}, {"ㅔ", "하 세"}, {"ㅛ", "하 세ㅛ"}}) type(k[0], k[1]);
        bs("하 세");
        type("ㅇ", "하 셍");
        expect("ㅛ 옆 ㅇ? (붙지 않음 → 배우지 않음)", "[]");

        // 4) 아예 다른 말로 고쳐 씀: 가다 → 지우고 나무 (ㄱ-ㄴ, ㄷ-ㅁ 은 붙은 키 아님)
        start("");
        type("ㄱ", "ㄱ"); type("ㅏ", "가"); type("ㄷ", "갇"); type("ㅏ", "가다");
        bs("가"); bs("");
        type("ㄴ", "ㄴ"); type("ㅏ", "나"); type("ㅁ", "남"); type("ㅜ", "나무");
        expect("다른 말로 고쳐 씀", "[]");

        // 5) 지우고 그대로 다시 친 것 (바뀐 키 없음) → 배우지 않음
        start("");
        type("ㄴ", "ㄴ"); type("ㅏ", "나");
        bs("ㄴ");
        type("ㅏ", "나");
        expect("그대로 다시 침", "[]");

        // 6) ㅇ 대신 ㄷ? → ㅇ(2줄) ㄷ(1줄) 붙음: 동 을 치려다 ㄷ ㅗ ㄷ → ㄷ 지우고 ㅇ
        start("");
        type("ㄷ", "ㄷ"); type("ㅗ", "도"); type("ㄷ", "돋");
        bs("도");
        type("ㅇ", "동");
        expect("돋 → 동 (ㅇ←ㄷ)", "[ㅇ←ㄷ]");

        // 7) 화면이 기록과 다르면 (커서를 옮긴 뒤 등) 배우지 않음
        start("");
        type("ㄱ", "ㄱ"); type("ㅗ", "고"); type("ㅓ", "고ㅓ");
        text = "전혀 다른 글";
        bs("전혀 다른 그");
        type("ㅏ", "전혀 다른 그ㅏ");
        expect("화면과 기록이 다름", "[]");

        System.out.println(fail == 0 ? "OK" : "FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}
