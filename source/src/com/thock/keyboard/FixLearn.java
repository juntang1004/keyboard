package com.thock.keyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * 평소 타자에서 "지우고 다시 친 것"으로 오타를 배운다 (쿼티 한글).
 *
 *  고ㅓ → 지우기 1번 → ㅏ  : 지운 자모 [ㅓ] 와 다시 친 자모 [ㅏ] → 오타 쌍 "고ㅓ → 과", 터치 "ㅏ 를 치려다 ㅓ"
 *  다짐 → 지우기 4번 → ㄱㅏㅈㅣㅁ : [ㄷㅏㅈㅣㅁ] vs [ㄱㅏㅈㅣㅁ] → 오타 쌍 "다짐 → 가짐", 터치 "ㄱ 을 치려다 ㄷ", 나머지는 제대로 누름
 *
 * 화면의 글만 본다: 지우기 직전 글(t0)과 다시 치기 시작할 때 글(t1)을 자모로 풀어서, t1 이 t0 의 앞부분이면
 * 그 뒤가 지워진 자모다. 그 뒤 화면의 자모가 지운 만큼 다시 늘어나면 자리별로 비교한다.
 * (조합 중인 글자는 자모 하나씩, 다 만든 글자는 한 번에 지워지는 차이와 상관없이 동작)
 * 바뀐 자모가 모두 원래 키와 상하좌우로 맞닿은 키이고, 지운 것의 1/3 이하일 때만 믿는다.
 * 안드로이드 의존 없음 (JVM 테스트용).
 */
public final class FixLearn {
    /** 눌린 키 하나: id = 자리(기본 자모 또는 SP), ch = 입력된 자모, (dx, dy) = 눌린 키 가운데에서 벗어난 정도. 위치를 모르면 NaN */
    public static final class Key {
        final String id; final char ch; final float dx, dy;
        public Key(String id, char ch, float dx, float dy) { this.id = id; this.ch = ch; this.dx = dx; this.dy = dy; }
    }

    /** 터치 학습 결과 하나 */
    public static final class Learned {
        public final String want, pressed; public final float dx, dy; public final boolean error;
        Learned(String want, String pressed, float dx, float dy, boolean error) { this.want = want; this.pressed = pressed; this.dx = dx; this.dy = dy; this.error = error; }
        @Override public String toString() { return error ? want + "←" + pressed : want; }
    }

    /** 한 번 비교한 결과: 오타 쌍(없으면 null) + 터치 학습 */
    public static final class Result {
        public String typo, right;
        public final List<Learned> touches = new ArrayList<>();
        @Override public String toString() { return (typo == null ? "-" : typo + "→" + right) + " " + touches; }
    }

    // 진단 (설정 화면에 보여줌)
    public static int nBackspace, nChecked, nLearned;
    public static String lastSkip = "";

    private static final int MAX_PRESS = 60;
    private final List<Key> presses = new ArrayList<>();      // 최근에 누른 글자·스페이스 (화면 자모와 하나씩 맞물림)
    private List<Key> pressAtDel;
    private int phase;          // 0 보통, 1 지우는 중, 2 다시 치는 중
    private String t0;
    private List<Character> k0, base;

    public void reset() { presses.clear(); phase = 0; t0 = null; k0 = base = null; pressAtDel = null; }

    /** 입력창에서 읽을 글 길이 */
    public int need() { return 48; }

    /** 지우기가 적용되기 직전 (누를 때마다). before = 커서 앞 글 */
    public void beforeBackspace(CharSequence before) {
        nBackspace++;
        if (phase == 1) return;
        phase = 0;
        if (before == null) { skip("입력창 글을 못 읽음"); return; }
        t0 = before.toString();
        k0 = keys(t0);
        pressAtDel = new ArrayList<>(presses);
        phase = 1;
    }

    /** 글자·스페이스가 들어가기 직전. before = 커서 앞 글 */
    public void beforeKey(CharSequence before) {
        if (phase != 1) return;
        phase = 0;
        if (before == null) { skip("입력창 글을 못 읽음"); return; }
        List<Character> k1 = keys(before.toString());
        if (k1.size() > k0.size() || !startsWith(k0, k1)) { skip("지운 뒤 화면이 앞 글과 안 맞음"); presses.clear(); return; }
        if (k1.size() == k0.size()) return;
        int d = k0.size() - k1.size();
        for (int i = 0; i < d && !presses.isEmpty(); i++) presses.remove(presses.size() - 1);
        base = k1;
        phase = 2;
    }

    /** 글자·스페이스가 들어간 뒤. after = 커서 앞 글. 비교할 만큼 모였으면 결과 (아니면 null) */
    public Result key(Key k, CharSequence after) {
        presses.add(k);
        if (presses.size() > MAX_PRESS) presses.remove(0);
        if (phase != 2) return null;
        if (after == null) { phase = 0; skip("입력창 글을 못 읽음"); return null; }
        List<Character> kn = keys(after.toString());
        if (!startsWith(kn, base)) { phase = 0; skip("다시 치는 중에 화면이 바뀜"); return null; }
        int d = k0.size() - base.size();
        if (kn.size() - base.size() < d) return null;
        phase = 0;
        nChecked++;
        List<Character> del = k0.subList(base.size(), k0.size()), re = kn.subList(base.size(), base.size() + d);
        Result r = compare(k0, base.size(), del, re, tail(pressAtDel, d));
        if (r == null) return null;
        nLearned++;
        lastSkip = "";
        return r;
    }

    private static void skip(String why) { lastSkip = why; }

    private static List<Key> tail(List<Key> ps, int d) {
        if (ps == null || ps.size() < d) return null;
        return ps.subList(ps.size() - d, ps.size());
    }

    // ---------------------------------------------------------------- 비교
    static Result compare(List<Character> k0, int at, List<Character> del, List<Character> re, List<Key> ps) {
        int n = del.size(), bad = 0;
        for (int i = 0; i < n; i++) {
            char a = del.get(i), b = re.get(i);
            if (a == b) continue;
            if (!adjacent(idOf(a), idOf(b))) { skip("바꾼 글자가 붙은 키가 아님 (" + a + "→" + b + ")"); return null; }
            bad++;
        }
        if (bad == 0) { skip("지우고 그대로 다시 침"); return null; }
        if (bad > Math.max(1, (n + 1) / 3)) { skip("바꾼 글자가 너무 많음"); return null; }
        Result r = new Result();
        // 터치: 누른 기록이 지운 자모와 맞을 때만
        boolean psOk = ps != null && ps.size() == n;
        for (int i = 0; psOk && i < n; i++) if (ps.get(i).ch != del.get(i)) psOk = false;
        if (psOk) {
            for (int i = 0; i < n; i++) {
                Key p = ps.get(i);
                if (Float.isNaN(p.dx)) continue;
                char b = re.get(i);
                if (p.ch == b) r.touches.add(new Learned(p.id, p.id, p.dx, p.dy, false));
                else {
                    String want = idOf(b);
                    float[] cp = center(p.id), cw = center(want);
                    if (cp != null && cw != null) r.touches.add(new Learned(want, p.id, p.dx + cp[0] - cw[0], p.dy + cp[1] - cw[1], true));
                }
            }
        }
        // 오타 쌍: 지운 곳이 들어 있는 어절 (스페이스를 넘어 지웠으면 만들지 않음)
        if (!del.contains(' ') && !re.contains(' ')) {
            int ws = at;
            while (ws > 0 && !isBreak(k0.get(ws - 1))) ws--;
            List<Character> wrong = new ArrayList<>(k0.subList(ws, k0.size()));
            List<Character> right = new ArrayList<>(k0.subList(ws, at));
            right.addAll(re);
            String w = compose(wrong), g = compose(right);
            if (!w.equals(g) && w.length() <= 20) { r.typo = w; r.right = g; }
        }
        return r;
    }

    private static boolean isBreak(char c) { return c == ' ' || c == '\n' || c == '.' || c == ',' || c == '?' || c == '!'; }

    // ---------------------------------------------------------------- 자모
    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
    private static final String[] COMB = {"ㅘㅗㅏ", "ㅙㅗㅐ", "ㅚㅗㅣ", "ㅝㅜㅓ", "ㅞㅜㅔ", "ㅟㅜㅣ", "ㅢㅡㅣ",
            "ㄳㄱㅅ", "ㄵㄴㅈ", "ㄶㄴㅎ", "ㄺㄹㄱ", "ㄻㄹㅁ", "ㄼㄹㅂ", "ㄽㄹㅅ", "ㄾㄹㅌ", "ㄿㄹㅍ", "ㅀㄹㅎ", "ㅄㅂㅅ"};

    private static void addJamo(List<Character> out, char c) {
        for (String s : COMB) if (s.charAt(0) == c) { out.add(s.charAt(1)); out.add(s.charAt(2)); return; }
        out.add(c);
    }

    /** 글 → 누른 자모 순서 (겹모음·겹받침은 두 번 누른 것으로). 한글이 아닌 글자는 그대로 */
    static List<Character> keys(String s) {
        List<Character> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int v = c - 0xAC00;
                out.add(CHO.charAt(v / 588));
                addJamo(out, JUNG.charAt(v % 588 / 28));
                if (v % 28 != 0) addJamo(out, JONG.charAt(v % 28));
            } else addJamo(out, c);
        }
        return out;
    }

    static String compose(List<Character> ks) {
        char[] a = new char[ks.size()];
        for (int i = 0; i < a.length; i++) a[i] = ks.get(i);
        return Typo.compose(a, 0, a.length);
    }

    private static boolean startsWith(List<Character> a, List<Character> p) {
        if (p.size() > a.size()) return false;
        for (int i = 0; i < p.size(); i++) if (!a.get(i).equals(p.get(i))) return false;
        return true;
    }

    private static final String DOUBLE = "ㄲㄸㅃㅆㅉㅒㅖ", SINGLE = "ㄱㄷㅂㅅㅈㅐㅔ";

    /** 자모 → 키 자리 id (쌍자음은 같은 자리) */
    static String idOf(char c) {
        if (c == ' ') return Calib.SP;
        int i = DOUBLE.indexOf(c);
        if (i >= 0) c = SINGLE.charAt(i);
        String s = String.valueOf(c);
        return Calib.isLetter(s) ? s : null;
    }

    /** 두 키 칸이 맞닿아 있나 (같은 줄 옆 칸, 윗줄·아랫줄에서 겹치는 칸) */
    static boolean adjacent(String a, String b) {
        if (a == null || b == null) return false;
        Calib.Geo g = Calib.standard();
        int i = g.indexOf(a), j = g.indexOf(b);
        if (i < 0 || j < 0 || i == j) return false;
        float[] p = g.rects.get(i), q = g.rects.get(j);
        float gx = Math.max(p[0], q[0]) - Math.min(p[2], q[2]), gy = Math.max(p[1], q[1]) - Math.min(p[3], q[3]);
        return gx <= 0.01f && gy <= 0.01f && !(gx > -0.01f && gy > -0.01f);   // 모서리만 닿은 것은 빼고
    }

    private static float[] center(String id) {
        if (id == null) return null;
        Calib.Geo g = Calib.standard();
        int i = g.indexOf(id);
        if (i < 0) return null;
        float[] r = g.rects.get(i);
        return new float[]{(r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f};
    }
}
