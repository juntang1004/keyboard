import com.thock.keyboard.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** 오타 제안 엔진을 JVM에서 시험: 조합, 언어 점수, 실제 Java 코드로 "장고규가기" 같은 예를 고친다. */
public class TypoTest {
    static Class<?> T;
    static Method m(String n, Class<?>... a) throws Exception { Method x = T.getDeclaredMethod(n, a); x.setAccessible(true); return x; }
    public static void main(String[] args) throws Exception {
        T = Class.forName("com.thock.keyboard.Typo");
        try (InputStream in = new FileInputStream(args[0])) { m("readLm", InputStream.class).invoke(null, in); }
        Method compose = m("compose", char[].class, int.class, int.class);
        Method score = m("score", String.class);
        String[][] cases = {{"ㅈㅏㅇㄱㅗㄱ ㄱㅏㄱㅣ", "장곡 가기"}, {"ㅇㅗㄴㅡㄹ ㅁㅝ ㅎㅐ", "오늘 뭐 해"}, {"ㄱㅏㅂㅅ ㅇㅡㅣㅅㅏ", "값 의사"}, {"ㄱㅗㅏㄴㄷㅡㄹ", "관들"}};
        for (String[] c : cases) {
            String k = c[0].replace(" ", " ");
            String got = (String) compose.invoke(null, k.toCharArray(), 0, k.length());
            if (!got.equals(c[1])) throw new RuntimeException("compose " + c[0] + " -> " + got);
        }
        System.out.println("score 장고규가기=" + score.invoke(null, "장고규가기") + "  장곡 가기=" + score.invoke(null, "장곡 가기"));

        // 가짜 입력칸으로 find() 실행
        final StringBuilder doc = new StringBuilder();
        Class<?> hostC = Class.forName("com.thock.keyboard.Typo$Host");
        Object host = Proxy.newProxyInstance(TypoTest.class.getClassLoader(), new Class<?>[]{hostC}, (p, meth, a) -> {
            if (meth.getName().equals("ic")) return null;
            return null;
        });
        // find() needs InputConnection: emulate by checking engine on candidate generation directly via reflection of log
        Field logF = T.getDeclaredField("log"); logF.setAccessible(true);
        @SuppressWarnings("unchecked") List<Object> log = (List<Object>) logF.get(null);
        Class<?> sc = Class.forName("com.thock.keyboard.Typo$Stroke");
        Constructor<?> scc = sc.getDeclaredConstructor(char.class, String.class, float.class, float.class); scc.setAccessible(true);
        // 표준 좌표: ㅠ 키 = (5.5~6.5, 2~3). 스페이스 = (3.25~7.5, 3~4). ㅠ 아래쪽 경계 근처를 눌렀다고 가정 (y=2.85)
        String typed = "ㅈㅏㅇㄱㅗㄱㅠㄱㅏㄱㅣ";
        for (char ch : typed.toCharArray()) {
            if (ch == 'ㅠ') log.add(scc.newInstance(ch, "ㅠ", 6.0f, 2.85f));
            else log.add(scc.newInstance(ch, String.valueOf(ch), Float.NaN, Float.NaN));
        }
        doc.append((String) compose.invoke(null, typed.toCharArray(), 0, typed.length()));
        System.out.println("화면: " + doc);
        android.view.inputmethod.InputConnection ic = (android.view.inputmethod.InputConnection) Proxy.newProxyInstance(TypoTest.class.getClassLoader(),
                new Class<?>[]{android.view.inputmethod.InputConnection.class}, (p, meth, a) -> {
                    if (meth.getName().equals("getTextBeforeCursor")) { int n = (Integer) a[0]; return doc.substring(Math.max(0, doc.length() - n)); }
                    if (meth.getReturnType() == boolean.class) return true;
                    return null;
                });
        Object host2 = Proxy.newProxyInstance(TypoTest.class.getClassLoader(), new Class<?>[]{hostC}, (p, meth, a) -> meth.getName().equals("ic") ? ic : null);
        Object fix = m("find", hostC).invoke(null, host2);
        if (fix == null) throw new RuntimeException("제안 없음");
        Class<?> fc = fix.getClass();
        Field lab = fc.getDeclaredField("label"); lab.setAccessible(true);
        Field nt = fc.getDeclaredField("newTail"); nt.setAccessible(true);
        Field ot = fc.getDeclaredField("oldTail"); ot.setAccessible(true);
        Field dl = fc.getDeclaredField("delta"); dl.setAccessible(true);
        System.out.println("제안: " + lab.get(fix) + "  (" + ot.get(fix) + " -> " + nt.get(fix) + ", 점수차 " + dl.get(fix) + ")");
        if (!"장곡 가기".equals(nt.get(fix))) throw new RuntimeException("틀린 제안");
        // 같은 오타를 ㅠ 한가운데(y=2.5)에서 누른 경우
        log.clear();
        for (char ch : typed.toCharArray()) log.add(scc.newInstance(ch, String.valueOf(ch), ch == 'ㅠ' ? 6.0f : Float.NaN, ch == 'ㅠ' ? 2.5f : Float.NaN));
        Object fix2 = m("find", hostC).invoke(null, host2);
        System.out.println("ㅠ 한가운데를 누른 경우: " + (fix2 == null ? "제안 없음" : nt.get(fix2) + " (점수차 " + dl.get(fix2) + ")"));
        // 정상 문장에는 제안이 없어야
        String ok = "ㅇㅗㄴㅡㄹ ㅁㅝ ㅎㅐ";
        log.clear(); doc.setLength(0);
        for (char ch : ok.toCharArray()) log.add(scc.newInstance(ch, ch == ' ' ? "SP" : String.valueOf(ch), ch == ' ' ? 5.0f : Float.NaN, ch == ' ' ? 3.5f : Float.NaN));
        doc.append((String) compose.invoke(null, ok.toCharArray(), 0, ok.length()));
        Object fix3 = m("find", hostC).invoke(null, host2);
        System.out.println("정상 문장 '" + doc + "': " + (fix3 == null ? "제안 없음" : "제안 " + nt.get(fix3)));
        if (fix3 != null) throw new RuntimeException("정상 문장에 제안");
        // ㅠ·ㅜ 자동 교정: 스페이스 쪽으로 더 가깝게 눌렀으면 (y=2.95) 자동 교정 기준(3.0)을 넘어야
        log.clear(); doc.setLength(0);
        for (char ch : typed.toCharArray()) log.add(scc.newInstance(ch, String.valueOf(ch), ch == 'ㅠ' ? 6.0f : Float.NaN, ch == 'ㅠ' ? 2.95f : Float.NaN));
        doc.append((String) compose.invoke(null, typed.toCharArray(), 0, typed.length()));
        Object fu = m("find", hostC, boolean.class).invoke(null, host2, true);
        Field uuF = fc.getDeclaredField("uu"); uuF.setAccessible(true);
        System.out.println("y=2.95: " + nt.get(fu) + " (점수차 " + dl.get(fu) + ", ㅠㅜ=" + uuF.get(fu) + ")");
        System.out.println("y=2.85 는 점수차 " + dl.get(fix) + " → " + ((Double) dl.get(fix) > 3.0 ? "자동 교정" : "제안만"));
        System.out.println("OK");
    }
}
