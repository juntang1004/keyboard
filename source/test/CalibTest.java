import com.thock.keyboard.*;
import java.lang.reflect.*;
import java.util.*;

/** 오타 학습: 문장 분해/조합, 지우기 포함 라벨링, 추천 보정 효과 (JVM에서 실행). */
public class CalibTest {
    static Object call(String m, Class<?>[] t, Object... a) throws Exception { Method x = Calib.class.getDeclaredMethod(m, t); x.setAccessible(true); return x.invoke(null, a); }
    static Object get(Object o, String f) throws Exception { Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o); }
    public static void main(String[] a) throws Exception {
        Field fs = Calib.class.getDeclaredField("SENTENCES"); fs.setAccessible(true);
        String[] S = (String[]) fs.get(null);
        Map<String, Integer> cnt = new TreeMap<>();
        int keys = 0;
        for (String s : S) {
            @SuppressWarnings("unchecked") List<String> k = (List<String>) call("decompose", new Class<?>[]{String.class}, s);
            if (k == null) throw new RuntimeException("쉬프트 필요: " + s);
            Object comp = call("compose", new Class<?>[]{List.class}, k);
            String back = get(comp, "text").toString();
            if (!back.equals(s)) throw new RuntimeException("조합 불일치: " + s + " -> " + back);
            for (String x : k) cnt.merge(x, 1, Integer::sum);
            keys += k.size();
        }
        System.out.println("문장 " + S.length + "개, 키 " + keys + "번: " + cnt);
        String all = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ";
        int min = 999; String minK = "";
        for (char c : all.toCharArray()) { int n = cnt.getOrDefault(String.valueOf(c), 0); if (n < min) { min = n; minK = "" + c; } }
        System.out.println("가장 적게 나오는 키: " + minK + " " + min + "번");
        if (min < 6) throw new RuntimeException("키 " + minK + " 부족");

        // ---- 모의 타자: 손가락이 오른쪽 아래로 치우친 사람 (+0.22, +0.12), 흔들림 0.20
        Method std = Calib.class.getDeclaredMethod("standard"); std.setAccessible(true);
        Object g = std.invoke(null);
        Class<?> geo = g.getClass();
        Method pick = Calib.class.getDeclaredMethod("pick", geo, float.class, float.class, Map.class); pick.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> ids = (List<String>) get(g, "ids");
        @SuppressWarnings("unchecked") List<float[]> rects = (List<float[]>) get(g, "rects");
        Class<?> tc = Class.forName("com.thock.keyboard.Calib$Trainer");
        Constructor<?> tcons = tc.getDeclaredConstructor(String.class); tcons.setAccessible(true);
        Method onKey = tc.getDeclaredMethod("onKey", String.class, float.class, float.class, geo); onKey.setAccessible(true);
        Method done = tc.getDeclaredMethod("done"); done.setAccessible(true);
        Random rnd = new Random(7);
        int presses = 0;
        for (int round = 0; round < 2; round++) for (String s : S) {
            Object tr = tcons.newInstance(s);
            @SuppressWarnings("unchecked") List<String> target = (List<String>) get(tr, "target");
            @SuppressWarnings("unchecked") List<String> stack = (List<String>) get(tr, "stack");
            int guard = 0;
            while (!(Boolean) done.invoke(tr) && guard++ < 3000) {
                // 사람: 쌓인 글이 목표와 다르면 지우기를, 아니면 다음 글자를 노린다
                boolean bad = false;
                for (int j = 0; j < stack.size(); j++) if (j >= target.size() || !stack.get(j).equals(target.get(j))) bad = true;
                String aim = bad ? "BS" : target.get(stack.size());
                float[] r = rects.get(ids.indexOf(aim));
                float sx = aim.equals("SP") ? 1.0f : 0.20f;
                float x = (r[0] + r[2]) / 2f + (aim.equals("SP") ? 0 : 0.22f) + (float) rnd.nextGaussian() * sx;
                float y = (r[1] + r[3]) / 2f + 0.12f + (float) rnd.nextGaussian() * 0.17f;
                int k = (Integer) pick.invoke(null, g, x, y, null);
                onKey.invoke(tr, ids.get(k), x, y, g);
                presses++;
            }
            if (!(Boolean) done.invoke(tr)) throw new RuntimeException("문장을 못 끝냄: " + s);
        }
        Field sf = Calib.class.getDeclaredField("samples"); sf.setAccessible(true);
        List<?> samples = (List<?>) sf.get(null);
        int red = 0, bsN = 0, spN = 0, bsRed = 0;
        for (Object o : samples) {
            String t = (String) get(o, "t"), p = (String) get(o, "p");
            if (!t.equals(p)) red++;
            if (t.equals("BS")) { bsN++; if (!p.equals("BS")) bsRed++; }
            if (t.equals("SP")) spN++;
        }
        System.out.println("누름 " + presses + "번, 기록 " + samples.size() + "개, 오타 " + red + "개, 지우기 " + bsN + "개(오타 " + bsRed + "), 스페이스 " + spN + "개");
        @SuppressWarnings("unchecked") Map<String, float[]> rec = (Map<String, float[]>) call("recommend", new Class<?>[0]);
        int before = (Integer) call("errorsWith", new Class<?>[]{Map.class}, new HashMap<String, float[]>());
        int after = (Integer) call("errorsWith", new Class<?>[]{Map.class}, rec);
        float[] gq = rec.get("ㄱ"), bq = rec.get("BS"), sq = rec.get("SP");
        System.out.println("추천 ㄱ: " + Arrays.toString(gq) + "  지우기: " + Arrays.toString(bq) + "  스페이스: " + Arrays.toString(sq));
        System.out.println("같은 터치 기준 오타: 보정 전 " + before + " -> 추천 적용 " + after);
        if (after >= before) throw new RuntimeException("추천이 오타를 줄이지 못함");
        // 보정 0이면 원래 키 칸과 같아야 한다
        for (int i = 0; i < ids.size(); i++) { float[] r = rects.get(i);
            int k = (Integer) pick.invoke(null, g, (r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, null);
            if (k != i) throw new RuntimeException("pick 불일치 " + ids.get(i)); }
        System.out.println("OK");
    }
}
