package com.thock.keyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * 평소 타자에서 "지우고 다시 친 것"으로 오타를 배운다 (쿼티 한글).
 *
 *  고ㅓ → 지우기 1번 → ㅏ  : 지운 키 [ㅓ] 와 다시 친 키 [ㅏ] 를 비교 → ㅏ 를 치려다 ㅓ 를 누름
 *  다짐 → 지우기 4번(ㅁ·ㅣ·ㅈ 하나씩, '다'는 한 번에) → ㄱㅏㅈㅣㅁ : [ㄷㅏㅈㅣㅁ] vs [ㄱㅏㅈㅣㅁ]
 *     → ㄱ 을 치려다 ㄷ 을 누름(오타), ㅏㅈㅣㅁ 은 제대로 누름
 *
 * 몇 개의 키가 지워졌는지는 지우기 횟수가 아니라 지우기 전후 입력창의 글을 보고 알아낸다
 * (조합 중인 글자는 자모 하나씩, 다 만든 글자는 한 번에 지워지는 차이를 신경 쓰지 않아도 되게).
 * 다시 친 키가 지운 키 수만큼 모이면 자리별로 비교한다. 바뀐 키가 모두 원래 키와 상하좌우로 붙어 있을 때만 믿는다.
 * 안드로이드 의존 없음 (JVM 테스트용).
 */
public final class FixLearn {
    /** 눌린 키 하나: id = 자리(기본 자모 또는 SP), ch = 입력된 글자, (dx, dy) = 눌린 키 가운데에서 벗어난 정도. 위치를 모르면 NaN */
    public static final class Key {
        final String id; final char ch; final float dx, dy;
        public Key(String id, char ch, float dx, float dy) { this.id = id; this.ch = ch; this.dx = dx; this.dy = dy; }
    }

    /** 배운 결과 하나 */
    public static final class Learned {
        public final String want, pressed; public final float dx, dy; public final boolean error;
        Learned(String want, String pressed, float dx, float dy, boolean error) { this.want = want; this.pressed = pressed; this.dx = dx; this.dy = dy; this.error = error; }
        @Override public String toString() { return error ? want + "←" + pressed : want; }
    }

    private static final int MAX_LOG = 40;
    private final List<Key> log = new ArrayList<>();
    private final List<Key> deleted = new ArrayList<>(), retyped = new ArrayList<>();
    private int phase;          // 0 보통, 1 지우는 중, 2 다시 치는 중
    private String t0;

    public void reset() { log.clear(); deleted.clear(); retyped.clear(); phase = 0; t0 = null; }

    /** 입력창에서 읽어야 할 글 길이 */
    public int need() { return logText(log.size()).length() + 4; }

    /** 지우기가 적용되기 직전 (누를 때마다). before = 커서 앞 글 */
    public void beforeBackspace(CharSequence before) {
        if (phase == 2) { log.addAll(retyped); retyped.clear(); deleted.clear(); phase = 0; }
        if (phase == 0) {
            if (before == null || log.isEmpty()) { reset(); return; }
            t0 = before.toString();
            phase = 1;
        }
    }

    /** 글자·스페이스가 들어가기 직전. before = 커서 앞 글 */
    public void beforeKey(CharSequence before) {
        if (phase != 1) return;
        phase = 0;
        int m = before == null ? -1 : remaining(t0, before.toString());
        if (m < 0) { reset(); return; }
        deleted.clear();
        deleted.addAll(log.subList(m, log.size()));
        while (log.size() > m) log.remove(log.size() - 1);
        retyped.clear();
        if (!deleted.isEmpty()) phase = 2;
    }

    /** 글자·스페이스가 들어간 뒤. 다시 친 키가 다 모였으면 배운 것을 돌려준다 (없으면 null) */
    public List<Learned> key(Key k) {
        if (!(k.ch == ' ' || Calib.isLetter(String.valueOf(k.ch)))) { reset(); return null; }   // 쌍자음(쉬프트) 등은 조합을 따라갈 수 없음
        if (phase != 2) { add(k); return null; }
        retyped.add(k);
        if (retyped.size() < deleted.size()) return null;
        List<Learned> out = compare(deleted, retyped);
        for (Key r : retyped) add(r);
        retyped.clear(); deleted.clear(); phase = 0;
        return out;
    }

    private void add(Key k) {
        log.add(k);
        if (log.size() > MAX_LOG) {          // 앞쪽을 어절 단위로 버린다 (글자 조합이 바뀌지 않게)
            int sp = -1;
            for (int i = 0; i < log.size() - 1; i++) if (log.get(i).ch == ' ') { sp = i; break; }
            if (sp < 0) log.clear(); else log.subList(0, sp + 1).clear();
        }
    }

    // ---------------------------------------------------------------- 몇 개 남았나
    private String logText(int m) {
        List<String> ks = new ArrayList<>();
        for (int i = 0; i < m; i++) ks.add(log.get(i).ch == ' ' ? Calib.SP : String.valueOf(log.get(i).ch));
        return Calib.compose(ks).text.toString();
    }

    /** 지운 뒤 남은 키 수 (모르면 -1) */
    int remaining(String before, String after) {
        int n = log.size();
        String all = logText(n);
        if (!before.endsWith(all)) return -1;                    // 기록과 화면이 다름 (커서 이동 등)
        String pre = before.substring(0, before.length() - all.length());
        if (after.length() < pre.length() || !after.startsWith(pre)) return -1;   // 기록보다 앞까지 지움
        String rest = after.substring(pre.length());
        List<String> ks = new ArrayList<>();
        for (Key k : log) ks.add(k.ch == ' ' ? Calib.SP : String.valueOf(k.ch));
        Calib.Composed full = Calib.compose(ks);
        for (int m = n - 1; m >= 0; m--) {
            if (logText(m).equals(rest)) return m;              // 남은 키로 다시 조합한 모양
            if (fixedText(full, ks, m).equals(rest)) return m;  // 글자 경계는 그대로, 마지막 글자만 덜 만든 모양
        }
        return -1;
    }

    private static String fixedText(Calib.Composed full, List<String> ks, int m) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < full.span.size(); c++) {
            int[] sp = full.span.get(c);
            if (sp[sp.length - 1] < m) { sb.append(full.text.charAt(c)); continue; }
            if (sp[0] < m) sb.append(Calib.compose(ks.subList(sp[0], m)).text);
            break;
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 비교
    static List<Learned> compare(List<Key> del, List<Key> re) {
        int n = del.size(), bad = 0;
        List<Learned> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Key d = del.get(i), r = re.get(i);
            if (d.id.equals(r.id)) {
                if (!Float.isNaN(d.dx)) out.add(new Learned(d.id, d.id, d.dx, d.dy, false));
                continue;
            }
            float[] cd = center(d.id), cr = center(r.id);
            if (cd == null || cr == null || !adjacent(d.id, r.id)) return null;   // 붙은 키가 아니면 다르게 고쳐 쓴 것
            bad++;
            // 누르려던 키(r) 가운데에서 벗어난 정도로 바꾼다
            if (!Float.isNaN(d.dx)) out.add(new Learned(r.id, d.id, d.dx + cd[0] - cr[0], d.dy + cd[1] - cr[1], true));
        }
        if (bad == 0 || bad > Math.max(1, (n + 1) / 3)) return null;
        return out;
    }

    /** 두 키 칸이 맞닿아 있나 (같은 줄 옆 칸, 윗줄·아랫줄에서 겹치는 칸) */
    static boolean adjacent(String a, String b) {
        Calib.Geo g = Calib.standard();
        int i = g.indexOf(a), j = g.indexOf(b);
        if (i < 0 || j < 0 || i == j) return false;
        float[] p = g.rects.get(i), q = g.rects.get(j);
        float gx = Math.max(p[0], q[0]) - Math.min(p[2], q[2]), gy = Math.max(p[1], q[1]) - Math.min(p[3], q[3]);
        return gx <= 0.01f && gy <= 0.01f && !(gx > -0.01f && gy > -0.01f);   // 모서리만 닿은 것은 빼고
    }

    private static float[] center(String id) {
        Calib.Geo g = Calib.standard();
        int i = g.indexOf(id);
        if (i < 0) return null;
        float[] r = g.rects.get(i);
        return new float[]{(r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f};
    }
}
