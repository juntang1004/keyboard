import com.thock.keyboard.SpellAccess;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 맞춤법 제안 테스트.
 *  java SpellTest <spell.tsv> [wiki.txt] [ko_full.txt]
 *  1) 목록 검증: 바른 형태가 다시 고쳐지지 않음(순환 없음), 틀린≠바른
 *  2) 예문: 틀린 어절은 제안, 바른 문장은 제안 0건
 *  3) (말뭉치가 있으면) 위키백과 본문 어절에서 제안이 뜨는 비율과 항목별 건수 (오탐 후보)
 */
public class SpellTest {
    static int fail;

    static void eq(String what, Object got, Object want) {
        if (want == null ? got != null : !want.equals(got)) { fail++; System.out.println("FAIL " + what + ": " + got + " (기대 " + want + ")"); }
    }

    /** "글 " 처럼 경계 문자로 끝나는 글 → 제안된 새 어절 (없으면 null) */
    static String sug(String text) {
        String[] r = SpellAccess.check(text);
        return r == null ? null : r[1];
    }

    public static void main(String[] a) throws Exception {
        SpellAccess.parse(new FileInputStream(a[0]));
        System.out.println("항목 " + SpellAccess.itemCount() + "개, 형태 " + SpellAccess.formCount() + "개");

        // 1) 목록 검증
        int cyc = 0;
        for (String[] p : SpellAccess.pairs()) {
            if (p[0].equals(p[1])) { fail++; System.out.println("FAIL 같음 " + p[0]); }
            String back = SpellAccess.fixWord(p[1]);
            if (back != null) { cyc++; fail++; System.out.println("FAIL 순환: " + p[0] + " → " + p[1] + " → " + back); }
            String fwd = SpellAccess.fixWord(p[0]);
            if (fwd == null) { fail++; System.out.println("FAIL 안 고쳐짐: " + p[0]); }
        }
        System.out.println("순환 " + cyc + "건");

        // 2) 예문
        eq("되요", sug("오늘 되요 "), "돼요 ");
        eq("안되요", sug("그럼 안되요."), "안돼요.");
        eq("어떻해", sug("어떻해 "), "어떡해 ");
        eq("됬어", sug("다 됬어 "), "됐어 ");
        eq("됬어?", sug("다 됬어?"), "됐어?");
        eq("몇일", sug("몇일이야?"), "며칠이야?");
        eq("할께요", sug("내일 연락할께요 "), "연락할게요 ");
        eq("갈께", sug("먼저 갈께\n"), "갈게\n");
        eq("할꺼야", sug("나 공부할꺼야 "), "공부할 거야 ");
        eq("할꺼에요", sug("할꺼에요 "), "할 거예요 ");
        eq("왠만하면", sug("왠만하면 "), "웬만하면 ");
        eq("금새", sug("금새 "), "금세 ");
        eq("고양이예요(맞음)", sug("고양이예요 "), null);
        eq("오랫만이야", sug("오랫만이야!"), "오랜만이야!");
        eq("따옴표", sug("\"되요\" "), "\"돼요\" ");
        eq("ㅋㅋ 뒤", sug("되요ㅋㅋ "), "돼요ㅋㅋ ");
        eq("만두국이", sug("만두국이 "), "만둣국이 ");
        eq("내꺼야", sug("이건 내꺼야 "), "내 거야 ");
        eq("뵈요", sug("내일 뵈요 "), "봬요 ");
        eq("생각치도", sug("생각치도 "), "생각지도 ");
        eq("깨끗히", sug("깨끗히 "), "깨끗이 ");
        eq("않해", sug("않해 "), "안 해 ");
        // 2026-11-20 추가 항목
        eq("됀다", sug("안됀다 "), "안된다 ");
        eq("몇칠", sug("몇칠 "), "며칠 ");
        eq("솔직이", sug("솔직이 "), "솔직히 ");
        eq("솔직히(맞음)", sug("솔직히 "), null);
        eq("했구요", sug("밥 먹었고 숙제도 했구요 "), "했고요 ");
        eq("뇌졸증", sug("뇌졸증으로 "), "뇌졸중으로 ");
        eq("뒤쳐진", sug("뒤쳐진 "), "뒤처진 ");
        eq("성공율", sug("성공율이 "), "성공률이 ");
        eq("출산률", sug("출산률 "), "출산율 ");
        eq("꺽지(물고기, 맞음)", sug("꺽지 "), null);
        eq("사건들이(맞음)", sug("사건들이 "), null);
        eq("친구요(맞음)", sug("친구요 "), null);
        // ㅔ·ㅐ 분류 (2026-11-23)
        eq("배개", sug("배개 "), "베개 ");
        eq("황테", sug("황테 "), "황태 ");
        eq("연애인이", sug("연애인이 "), "연예인이 ");
        eq("게선", sug("게선 "), "개선 ");
        eq("유투브에서", sug("유투브에서 "), "유튜브에서 ");
        eq("케릭터", sug("케릭터 "), "캐릭터 ");
        eq("해매다", sug("해매다 "), "헤매다 ");
        eq("제일(맞음)", sug("제일 "), null);
        eq("매뉴얼(맞음)", sug("매뉴얼 "), null);
        eq("안개속에(맞음)", sug("안개속에 "), null);
        eq("배끼리(맞음)", sug("배끼리 "), null);
        eq("경계 없음", sug("되요"), null);
        eq("두 번째 스페이스", sug("되요  "), null);
        eq("마침표 뒤 스페이스", sug("되요. "), null);
        eq("영문 섞임", sug("abc되요 "), null);
        eq("어절 잘림", sug("가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가되요 "), null);

        String[] okText = {
                "오늘 날씨 정말 좋다 ", "내일 몇 시에 만날까? ", "며칠 전에 봤던 영화 재밌었어 ", "그거 안 돼요 ", "이제 다 됐어 ",
                "금세 끝날 거야 ", "웬만하면 오늘 하자 ", "왠지 기분이 좋아 ", "어떡해 진짜 ", "어떻게 해야 할지 모르겠어 ",
                "연락할게요 ", "먼저 갈게 ", "고양이예요 ", "학생이에요 ", "아니에요 ", "오랜만이야 ", "설거지 했어? ",
                "되도록 빨리 와 ", "돼지고기 먹을래? ", "돼지 ", "살이 찌게 생겼어 ", "부모님들께 인사드렸어 ", "선생님께요 ",
                "되게 맛있다 ", "안 되는데 ", "잘 되길 바라 ", "그렇게 되면 좋겠다 ", "의견이 갈려서 ", "그래서 되레 ", "이 정도면 돼 ",
                "일부로 포함 ", "구지면 ", "요세미티 ", "왠지 ", "금새우 ", "희한하네 ", "어이없어 ", "역할 ", "깨끗이 ", "일일이 ",
                "어의가 진찰했다 ", "치루 수술 ", "바람이 불어 ", "색이 바래서 ", "늦게 돼서 미안 ", "돼요? ", "됐어요. ", "봬요 ",
                "할 거예요 ", "그럴 거야 ", "볼거리 ", "먹을거리 ", "내 거야 ", "수놈 ", "숫양 ", "숫염소 ", "만둣국 ", "최댓값 ",
                "합격률 ", "감소율 ", "비율 ", "확률 ", "할는지 ", "하려고 ", "살려고 ", "만들려고 ", "열려고 ", "놀려고 ", "알려고 ",
                "커튼 ", "케이크 ", "메시지 ", "콘텐츠 ", "리더십 ", "주스 ", "꺼림직하다 ", "삐지다 ", "이쁘다 ", "짜장면 ",
                "게시판 ", "계시다 ", "계시는 ", "깎아 ", "깍지 ", "다른 거에 ", "과거에 ", "근거에요? ", "학교에요 "
        };
        int okHits = 0;
        for (String t : okText) { String r = sug(t); if (r != null) { okHits++; fail++; System.out.println("FAIL 오탐: " + t.trim() + " → " + r); } }
        System.out.println("바른 예문 " + okText.length + "개 중 제안 " + okHits + "건");

        // 2-1) 키보드 흐름: 제안 → 누르기 → 되돌리기
        SpellAccess.reset();
        SpellAccess.type("오늘 되요 ", false);
        eq("흐름 제안", SpellAccess.shown, "되요 → 돼요");
        SpellAccess.tap();
        eq("흐름 바꿈", SpellAccess.text.toString(), "오늘 돼요 ");
        eq("흐름 칩 숨김", SpellAccess.shown, null);
        eq("흐름 되돌리기", SpellAccess.backspace(), true);
        eq("흐름 되돌린 글", SpellAccess.text.toString(), "오늘 되요 ");
        eq("흐름 막힘 기억", SpellAccess.blocked("되요"), true);
        SpellAccess.type("되요 ", false);
        eq("막힌 항목은 다시 제안 안 함", SpellAccess.shown, null);
        SpellAccess.clearBlock();

        SpellAccess.reset();
        SpellAccess.type("먼저 갈께.", false);
        SpellAccess.tap();
        SpellAccess.type(" ", false);                       // 바꾼 뒤 경계 문자 하나 더
        eq("경계 하나 뒤 글", SpellAccess.text.toString(), "먼저 갈게. ");
        eq("경계 하나 뒤 되돌리기", SpellAccess.backspace(), true);
        eq("경계 하나 뒤 되돌린 글", SpellAccess.text.toString(), "먼저 갈께. ");
        SpellAccess.clearBlock();

        SpellAccess.reset();
        SpellAccess.type("몇일 ", false);
        SpellAccess.type("ㅋ", false);                      // 다음 키를 누르면 제안이 사라짐
        eq("다음 키에 사라짐", SpellAccess.shown, null);
        SpellAccess.tap();
        eq("사라진 뒤 누르기는 무시", SpellAccess.text.toString(), "몇일 ㅋ");
        eq("보통 지우기", SpellAccess.backspace(), false);

        SpellAccess.reset();
        SpellAccess.type("몇일 ", false);
        SpellAccess.text.append("x");                        // 그 사이 글이 바뀜 (붙여넣기 등)
        SpellAccess.tap();
        eq("글이 바뀌면 안 바꿈", SpellAccess.text.toString(), "몇일 x");

        SpellAccess.reset();
        SpellAccess.type("몇일 ", true);
        eq("고정 바꾸기 직후 생략", SpellAccess.shown, null);

        SpellAccess.reset();
        SpellAccess.inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
        SpellAccess.type("몇일 ", false);
        eq("비밀번호 칸 제외", SpellAccess.shown, null);

        SpellAccess.reset();
        SpellAccess.setOff("몇일");
        SpellAccess.type("몇일 ", false);
        eq("끈 항목 제외", SpellAccess.shown, null);
        SpellAccess.clearBlock();

        SpellAccess.reset();
        SpellAccess.type("다 됬어 ", false);
        SpellAccess.type("ㅎ", false);
        eq("다른 글자 뒤 되돌리기 없음", SpellAccess.backspace(), false);

        // 3) 말뭉치
        if (a.length > 1) {
            long words = 0, hits = 0;
            Map<String, Integer> byItem = new TreeMap<>();
            Map<String, List<String>> ex = new HashMap<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[1]), "UTF-8"))) {
                for (String line; (line = r.readLine()) != null; ) {
                    for (String w : line.split("\\s+")) {
                        if (w.isEmpty()) continue;
                        words++;
                        String[] res = SpellAccess.check(w + " ");
                        if (res == null) continue;
                        hits++;
                        String id = res[2];
                        byItem.merge(id, 1, Integer::sum);
                        List<String> l = ex.computeIfAbsent(id, k -> new ArrayList<>());
                        if (l.size() < 4) l.add(w);
                    }
                }
            }
            System.out.printf("위키 어절 %d개 중 제안 %d건 (%.4f%%)%n", words, hits, 100.0 * hits / Math.max(1, words));
            for (Map.Entry<String, Integer> e : byItem.entrySet())
                System.out.println("  " + e.getKey() + " : " + e.getValue() + "  " + ex.get(e.getKey()));
        }
        if (a.length > 2) {
            long tot = 0, hit = 0;
            Map<String, Long> byItem = new TreeMap<>();
            Map<String, List<String>> ex = new HashMap<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[2]), "UTF-8"))) {
                for (String line; (line = r.readLine()) != null; ) {
                    int k = line.lastIndexOf(' ');
                    if (k <= 0) continue;
                    long c = Long.parseLong(line.substring(k + 1).trim());
                    String w = line.substring(0, k);
                    tot += c;
                    String[] res = SpellAccess.check(w + " ");
                    if (res == null) continue;
                    hit += c;
                    byItem.merge(res[2], c, Long::sum);
                    List<String> l = ex.computeIfAbsent(res[2], q -> new ArrayList<>());
                    if (l.size() < 4) l.add(w + "(" + c + ")");
                }
            }
            System.out.printf("자막 어절 %d개 중 제안 %d건 (%.4f%%)  ※ 자막에는 실제 맞춤법 오류가 많음%n", tot, hit, 100.0 * hit / Math.max(1, tot));
            for (Map.Entry<String, Long> e : byItem.entrySet())
                System.out.println("  " + e.getKey() + " : " + e.getValue() + "  " + ex.get(e.getKey()));
        }
        System.out.println(fail == 0 ? "OK" : "FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}
