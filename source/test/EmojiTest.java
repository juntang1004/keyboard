import com.thock.keyboard.*;
import java.io.FileInputStream;
import java.lang.reflect.*;
import java.util.*;

/** 이모지 목록 읽기 · 분류 · 한국어 검색 (치는 중인 글자 포함). java EmojiTest source/mod2/emoji.tsv */
public class EmojiTest {
    static int fail;
    static void check(String what, boolean ok, Object got) { if (!ok) fail++; System.out.println((ok ? "ok   " : "FAIL ") + what + ": " + got); }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        Class<?> E = Class.forName("com.thock.keyboard.Emoji");
        Class<?> keep = Class.forName("com.thock.keyboard.Emoji$Keep");
        Method parse = E.getDeclaredMethod("parse", java.io.InputStream.class, keep); parse.setAccessible(true);
        List<Object> l = (List<Object>) parse.invoke(null, new FileInputStream(a[0]), null);
        Field all = E.getDeclaredField("all"); all.setAccessible(true);
        List<Object> al = (List<Object>) all.get(null); al.clear(); al.addAll(l);
        check("이모지 수", l.size() > 1800, l.size());
        Method ofCat = E.getDeclaredMethod("ofCat", int.class); ofCat.setAccessible(true);
        int sum = 0;
        for (int c = 1; c < 10; c++) { int n = ((List<String>) ofCat.invoke(null, c)).size(); sum += n; check("분류 " + c, n > 50, n); }
        check("분류 합", sum == l.size(), sum);
        Method search = E.getDeclaredMethod("search", String.class, int.class); search.setAccessible(true);
        String[][] q = {{"스마일", "😀"}, {"스마이", "😀"}, {"하트", "❤️"}, {"고양이", "🐱"}, {"고양", "🐱"}, {"웃음", "😀"}, {"태극기", "🇰🇷"}, {"피자", "🍕"}, {"ㅎㅎ", "😄"}};
        for (String[] x : q) {
            List<String> r = (List<String>) search.invoke(null, x[0], 80);
            check("검색 '" + x[0] + "'", r.contains(x[1]), r.size() + "개, 앞: " + r.subList(0, Math.min(8, r.size())));
        }
        List<String> none = (List<String>) search.invoke(null, "ㅋㅌㅊㅍ없는말", 80);
        check("없는 말", none.isEmpty(), none);
        System.out.println(fail == 0 ? "OK" : "FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}
