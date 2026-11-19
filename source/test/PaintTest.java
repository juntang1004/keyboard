import com.thock.keyboard.*;
import java.lang.reflect.*;
import java.util.*;

/** 직접 그린 범위: 스페이스로 ㅜ 아래쪽을 칠하면 그 터치가 스페이스로 인식되고, ㅜ 가운데는 그대로인지 확인. */
public class PaintTest {
    static Method m(String n, Class<?>... a) throws Exception { Method x = Calib.class.getDeclaredMethod(n, a); x.setAccessible(true); return x; }
    static Object f(String n) throws Exception { Field x = Calib.class.getDeclaredField(n); x.setAccessible(true); return x.get(null); }
    public static void main(String[] a) throws Exception {
        Object g = m("standard").invoke(null);
        Field fi = g.getClass().getDeclaredField("ids"); fi.setAccessible(true);
        Field fr = g.getClass().getDeclaredField("rects"); fr.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> ids = (List<String>) fi.get(g);
        @SuppressWarnings("unchecked") List<float[]> rects = (List<float[]>) fr.get(g);
        int sp = ids.indexOf("SP"), uu = ids.indexOf("ㅜ");
        byte[] paint = (byte[]) f("paint");
        Method cell = m("cell", float.class, float.class), core = m("coreOfOther", int.class, int.class);
        Method pickStd = m("pickStd", float.class, float.class, Map.class);
        Field painted = Calib.class.getDeclaredField("painted"); painted.setAccessible(true);
        float[] u = rects.get(uu);   // ㅜ = (6.5~7.5, 2~3)
        // 사용자가 ㅜ 아래 절반을 스페이스로 칠함 (붓이 ㅜ 가운데까지 넘어가도 가운데는 보호돼야)
        int n = 0, blocked = 0;
        for (float y = 2.3f; y < 3.0f; y += 0.02f) for (float x = 6.5f; x < 7.5f; x += 0.02f) {
            int c = (Integer) cell.invoke(null, x, y);
            if ((Boolean) core.invoke(null, c, sp)) { blocked++; continue; }
            paint[c] = (byte) sp; n++;
        }
        int cnt = 0; for (byte b : paint) if (b >= 0) cnt++;
        painted.setInt(null, cnt);
        String at = ids.get((Integer) pickStd.invoke(null, 7.0f, 2.9f, null));
        String mid = ids.get((Integer) pickStd.invoke(null, 7.0f, 2.5f, null));
        String below = ids.get((Integer) pickStd.invoke(null, 7.0f, 3.3f, null));
        System.out.println("칠한 칸 " + cnt + " (가운데라서 막힌 칸 " + blocked + ")");
        System.out.println("ㅜ 아래쪽(7.0, 2.9) → " + at + ",  ㅜ 가운데(7.0, 2.5) → " + mid + ",  스페이스(7.0, 3.3) → " + below);
        if (!at.equals("SP") || !mid.equals("ㅜ") || !below.equals("SP")) throw new RuntimeException("인식 틀림");
        String enc = java.util.Base64.getEncoder().encodeToString(paint);
        byte[] dec = java.util.Base64.getDecoder().decode(enc);
        if (!Arrays.equals(dec, paint)) throw new RuntimeException("저장 형식");
        System.out.println("저장 문자열 길이 " + enc.length() + "자, OK");
    }
}
