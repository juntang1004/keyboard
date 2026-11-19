import com.thock.keyboard.NaraEngine;
import java.io.*;

/** Compares NaraEngine with what Samsung 나랏글 produced on the phone. */
public class NaraTest {
    static String run(String seq) {
        NaraEngine e = new NaraEngine();
        StringBuilder out = new StringBuilder();
        for (String k : seq.trim().split("\\s+")) {
            switch (k) {
                case "g": out.append(e.consonant('ㄱ')); break;
                case "n": out.append(e.consonant('ㄴ')); break;
                case "r": out.append(e.consonant('ㄹ')); break;
                case "m": out.append(e.consonant('ㅁ')); break;
                case "s": out.append(e.consonant('ㅅ')); break;
                case "ng": out.append(e.consonant('ㅇ')); break;
                case "a": out.append(e.key(NaraEngine.AE)); break;
                case "o": out.append(e.key(NaraEngine.OU)); break;
                case "i": out.append(e.key(NaraEngine.I)); break;
                case "eu": out.append(e.key(NaraEngine.EU)); break;
                case "h": out.append(e.key(NaraEngine.STROKE)); break;
                case "ss": out.append(e.key(NaraEngine.DOUBLE)); break;
                case "sp": out.append(e.finish()).append(' '); break;
            }
        }
        return out + e.composing();
    }

    public static void main(String[] a) throws Exception {
        // expected values = Samsung 나랏글 results captured on the device
        String[][] c = {
            {"g a", "가"}, {"g a a", "거"}, {"g a a a", "가"}, {"n h", "ㄷ"}, {"n h h", "ㅌ"}, {"n h h h", "ㄴ"},
            {"g ss", "ㄲ"}, {"g ss ss", "ㄱ"}, {"m h", "ㅂ"}, {"m h h", "ㅍ"}, {"m h h h", "ㅁ"},
            {"s h", "ㅈ"}, {"s h h", "ㅊ"}, {"s h h h", "ㅅ"}, {"ng h", "ㅎ"}, {"ng h h", "ㅇ"},
            {"g h", "ㅋ"}, {"g h h", "ㄱ"}, {"r h", "ㄹ"}, {"s ss", "ㅆ"}, {"s h ss", "ㅉ"}, {"n h ss", "ㄸ"},
            {"m h ss", "ㅃ"}, {"g h ss", "ㅋ"},
            {"g a h", "갸"}, {"g a a h", "겨"}, {"g a h h", "가"}, {"g o", "고"}, {"g o o", "구"}, {"g o h", "교"},
            {"g o o h", "규"}, {"g a i", "개"}, {"g a a i", "게"}, {"g a h i", "걔"}, {"g a a h i", "계"},
            {"g o a", "과"}, {"g o o a", "궈"}, {"g o i", "괴"}, {"g o o i", "귀"}, {"g eu i", "긔"},
            {"g o a i", "괘"}, {"g o o a i", "궤"}, {"g a i h", "개"}, {"g o a a", "과"},
            {"g a g a", "가가"}, {"g a n i", "가니"}, {"g a r g", "갉"}, {"g a r g a", "갈가"}, {"g a g ss", "갂"},
            {"g a s ss", "갔"}, {"g a s ss a", "가싸"}, {"g a n h", "갇"}, {"g a n h h", "같"},
            {"g a sp", "가 "}, {"g a g sp g", "각 ㄱ"},
        };
        PrintStream o = new PrintStream(new FileOutputStream(a[0]), true, "UTF-8");
        int fail = 0;
        for (String[] x : c) {
            String got = run(x[0]);
            boolean ok = got.equals(x[1]);
            if (!ok) fail++;
            o.println((ok ? "OK   " : "FAIL ") + x[0] + " -> " + got + (ok ? "" : "   (삼성: " + x[1] + ")"));
        }
        o.println(fail == 0 ? "ALL " + c.length + " OK" : fail + " FAILED");
    }
}
