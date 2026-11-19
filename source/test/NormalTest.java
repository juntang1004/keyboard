import com.thock.keyboard.*;
import java.lang.reflect.*;
import java.util.*;

/** 평소 타자 기록: 지운 터치는 기록 안 됨, 오타 교정 기록은 같은 비율로만 반영, 최근 기록 위주. */
public class NormalTest {
    static Method m(String n, Class<?>... a) throws Exception { Method x = Calib.class.getDeclaredMethod(n, a); x.setAccessible(true); return x; }
    public static void main(String[] a) throws Exception {
        Field sf = Calib.class.getDeclaredField("samples"); sf.setAccessible(true);
        @SuppressWarnings("unchecked") List<Object> samples = (List<Object>) sf.get(null);
        Field ld = Calib.class.getDeclaredField("loaded"); ld.setAccessible(true); ld.setBoolean(null, true);
        Method key = m("noteKey", android.content.Context.class, String.class, float.class, float.class);
        Method bs = m("noteBackspace", android.content.Context.class, float.class, float.class);
        Method bsr = m("noteBackspaceRepeat");
        Method settle = m("settle", android.content.Context.class, boolean.class);
        Class<?> sc = Class.forName("com.thock.keyboard.Calib$Sample");
        Field fdx = sc.getDeclaredField("dx"), ft = sc.getDeclaredField("t"), fsrc = sc.getDeclaredField("src");
        fdx.setAccessible(true); ft.setAccessible(true); fsrc.setAccessible(true);
        // 1) ㄱ을 2000번: 보통은 dx=0.1, 10번에 한 번은 틀린 터치(dx=0.9)를 치고 바로 지운다. 5번에 한 번은 지우기를 누르고 있어서 3글자를 더 지운다(그 3글자는 dx=0.7)
        Random r = new Random(1);
        for (int i = 0; i < 2000; i++) {
            if (i % 10 == 9) { key.invoke(null, null, "ㄱ", 0.9f, 0f); bs.invoke(null, null, 0f, 0f); }
            else if (i % 50 == 49) { for (int k = 0; k < 3; k++) key.invoke(null, null, "ㄱ", 0.7f, 0f); bs.invoke(null, null, 0f, 0f); bsr.invoke(null); bsr.invoke(null); }
            else key.invoke(null, null, "ㄱ", 0.1f, 0f);
        }
        settle.invoke(null, null, true);
        int n = 0, bad = 0, nBs = 0;
        for (Object o : samples) {
            if ("BS".equals(ft.get(o))) { nBs++; continue; }
            n++;
            if (fdx.getFloat(o) > 0.5f) bad++;
        }
        System.out.println("ㄱ 기록 " + n + "개 (약 " + Math.round(1700 * 0.25) + "개 예상), 지운 터치가 섞인 것 " + bad + "개, 지우기 기록 " + nBs + "개");
        if (bad != 0) throw new RuntimeException("지운 터치가 기록됨");
        // 2) 스페이스: 평소 기록(dx=0, dy=0.0) + 오타 교정(dy=-0.55) 들이 실제 비율(오타 10%)대로 쌓였을 때 추천이 오타 쪽으로 쏠리지 않는지
        samples.clear();
        Constructor<?> cc = sc.getDeclaredConstructor(String.class, String.class, float.class, float.class, char.class); cc.setAccessible(true);
        for (int i = 0; i < 1000; i++) {
            if (i % 10 == 0) samples.add(cc.newInstance("SP", "ㅜ", 0f, -0.55f, 'F'));          // 오타는 교정 때 항상 기록
            else if (r.nextFloat() < 0.34f) samples.add(cc.newInstance("SP", "SP", 0f, 0f, 'N'));   // 맞게 누른 건 34%만
        }
        @SuppressWarnings("unchecked") Map<String, float[]> rec = (Map<String, float[]>) m("recommend").invoke(null);
        float[] mean = (float[]) m("meanOf", String.class).invoke(null, "SP");
        Field fdy = sc.getDeclaredField("dy"); fdy.setAccessible(true); float naive = 0; for (Object o : samples) naive += fdy.getFloat(o); naive /= samples.size();
        System.out.println("스페이스 평균 상하: 무게 없이 " + naive + " → 무게 적용 " + mean[1] + " (실제 평균 -0.055)  추천 " + Arrays.toString(rec.get("SP")));
        if (Math.abs(mean[1] + 0.055f) > 0.03f) throw new RuntimeException("오타 교정 기록 무게가 맞지 않음");
        // 3) 최근 위주: 예전 300개는 dy=0.3, 최근 150개는 dy=-0.1
        samples.clear();
        for (int i = 0; i < 300; i++) samples.add(cc.newInstance("ㅏ", "ㅏ", 0f, 0.3f, 'T'));
        for (int i = 0; i < 150; i++) samples.add(cc.newInstance("ㅏ", "ㅏ", 0f, -0.1f, 'N'));
        float[] mr = (float[]) m("meanOf", String.class).invoke(null, "ㅏ");
        System.out.println("습관이 바뀐 키: 단순 평균 " + ((300 * 0.3f + 150 * -0.1f) / 450) + " → 최근 위주 " + mr[1]);
        if (mr[1] > 0.1f) throw new RuntimeException("최근 기록이 충분히 반영 안 됨");
        System.out.println("OK");
    }
}
