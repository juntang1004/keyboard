package com.thock.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 오타 제안 · 자동 교정 (쿼티 한글).
 *
 * 1단계: 스페이스와 그 윗줄 키(ㅌㅊㅍㅠㅜㅡ)가 뒤바뀐 오타.
 *   "바부먹자" → "밥 먹자" : ㅜ 터치가 스페이스였다고 보면 더 말이 되는지를
 *   (터치 위치로 본 가능성) + (음절 연결 통계로 본 자연스러움) 으로 따진다.
 *  - 터치 가능성: 키 중심(따라 쓰기로 배운 치우침 포함)에서 벗어난 정도.
 *  - 자연스러움: assets/typo_lm.bin (위키백과 + 자막 어절 빈도로 만든 음절 2-gram), 내 단어 가산점.
 * 그 밖에: 내 단어(직접 등록 + 자동 학습), 고정 바꾸기(오타 → 바꿀 말), 고친 직후 지우기 = 되돌리기.
 */
public final class Typo {
    private Typo() {}

    interface Host {
        InputConnection ic();
        EditorInfo editor();
        void finishComposing();
        void show(String label);          // 툴바 제안 칩 (null = 숨김)
    }

    static final double T_SUGGEST = 1.0, T_AUTO = 6.0, T_AUTO_UU = 3.0, KNOWN_BONUS = 6.0;
    static final int MANUAL = 1000000;      // 직접 등록한 단어 표시
    private static final String OVER = "ㅌㅊㅍㅠㅜㅡ";   // 스페이스 바로 윗줄 키

    // ------------------------------------------------------------------ settings & personal data
    private static boolean loaded;
    static boolean on = true, auto = false, learn = true, autoUU = true;
    static final Map<String, Integer> words = new HashMap<>();
    static final Map<String, String> rules = new HashMap<>();
    static final Set<String> block = new HashSet<>();
    /** 지우고 다시 친 것에서 모은 오타 쌍: "오타	원래 말" → 횟수 (설정 › 자동 학습한 오타) */
    static final Map<String, Integer> learned = new HashMap<>();
    /** 앞으로 무시: 같은 오타 쌍이 다시 모여도 목록에 올리지 않음 */
    static final Set<String> ignored = new HashSet<>();
    /** 자동 교정 규칙마다 등록 뒤 실제로 고친 횟수 (바로 되돌린 것도 셈) */
    static final Map<String, Integer> ruleHits = new HashMap<>();
    private static int dirty;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("thock_ext", Context.MODE_PRIVATE); }

    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            SharedPreferences p = sp(c);
            on = p.getBoolean("typo_on", true);
            autoUU = p.getBoolean("typo_auto_uu", true);
            auto = p.getBoolean("typo_auto", false);
            learn = p.getBoolean("typo_learn", true);
            String s = p.getString("typo_words", null);
            if (s != null) { JSONObject o = new JSONObject(s); for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); words.put(k, o.getInt(k)); } }
            s = p.getString("typo_rules", null);
            if (s != null) { JSONObject o = new JSONObject(s); for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); rules.put(k, o.getString(k)); } }
            // "고치지 않기로 한 말"은 자동 교정과 겹쳐서 없앰 (2026-11-23): 예전 기록은 읽지 않고, 저장할 때 비운다
            s = p.getString("typo_learned", null);
            if (s != null) { JSONObject o = new JSONObject(s); for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); learned.put(k, o.getInt(k)); } }
            s = p.getString("typo_rule_hits", null);
            if (s != null) { JSONObject o = new JSONObject(s); for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); ruleHits.put(k, o.getInt(k)); } }
            s = p.getString("typo_ignored", null);
            if (s != null) { JSONArray a = new JSONArray(s); for (int i = 0; i < a.length(); i++) ignored.add(a.getString(i)); }
            cleanLearned();         // 예전에 모인 것 중 이제 모으지 않는 쌍 정리
        } catch (Throwable t) { android.util.Log.w("ThockTypo", "load", t); }
    }

    static void saveFlags(Context c) { sp(c).edit().putBoolean("typo_on", on).putBoolean("typo_auto", auto).putBoolean("typo_learn", learn).putBoolean("typo_auto_uu", autoUU).apply(); }

    static void saveData(Context c) {
        try {
            if (words.size() > 3000) {                    // 적게 쓴 자동 학습 단어부터 버린다
                List<String> ks = new ArrayList<>(words.keySet());
                Collections.sort(ks, (a, b) -> words.get(a) - words.get(b));
                for (int i = 0; i < ks.size() && words.size() > 2500; i++) if (words.get(ks.get(i)) < MANUAL) words.remove(ks.get(i));
            }
            JSONObject w = new JSONObject();
            for (Map.Entry<String, Integer> e : words.entrySet()) w.put(e.getKey(), e.getValue());
            JSONObject r = new JSONObject();
            for (Map.Entry<String, String> e : rules.entrySet()) r.put(e.getKey(), e.getValue());
            JSONArray b = new JSONArray();
            for (String s : block) b.put(s);
            JSONObject l = new JSONObject();
            for (Map.Entry<String, Integer> e : learned.entrySet()) l.put(e.getKey(), e.getValue());
            JSONArray ig = new JSONArray();
            for (String s : ignored) ig.put(s);
            JSONObject rh = new JSONObject();
            for (Map.Entry<String, Integer> e : ruleHits.entrySet()) if (rules.containsKey(e.getKey())) rh.put(e.getKey(), e.getValue());
            sp(c).edit().putString("typo_words", w.toString()).putString("typo_rules", r.toString()).putString("typo_block", b.toString())
                    .putString("typo_learned", l.toString()).putString("typo_ignored", ig.toString()).putString("typo_rule_hits", rh.toString()).apply();
            dirty = 0;
        } catch (Throwable t) { android.util.Log.w("ThockTypo", "save", t); }
    }

    /**
     * 모인 오타 정리: 모을 수 없는 쌍(자음·모음만, 한 글자)은 빼고, 깨진 글자가 든 것은 바뀐 부분만 남겨 합치고,
     * 이미 자동 교정이 고치는 것은 뺀다.
     */
    static void cleanLearned() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : learned.entrySet()) {
            String k = e.getKey(); int t = k.indexOf('	');
            if (t < 0) continue;
            String ty = k.substring(0, t), ri = k.substring(t + 1);
            if (hasLoneJamo(ty)) { String[] cr = core(ty, ri); ty = cr[0]; ri = cr[1]; }
            if (!collectable(ty, ri) || covered(ty)) continue;
            String nk = ty + "	" + ri;
            if (ignored.contains(nk)) continue;
            Integer n = out.get(nk);
            out.put(nk, (n == null ? 0 : n) + e.getValue());
        }
        learned.clear();
        learned.putAll(out);
    }

    /** 오타 쌍 하나를 모은다 (무시했거나 이미 자동 교정에 있으면 넘어감) */
    static void addPair(Context c, String typo, String right) {
        if (typo == null || right == null || typo.equals(right)) return;
        if (hasLoneJamo(typo)) { String[] cr = core(typo, right); typo = cr[0]; right = cr[1]; }   // 해보ㅓ → 해봐 는 보ㅓ → 봐 로
        if (!collectable(typo, right) || covered(typo)) return;
        String k = typo + "	" + right;
        if (ignored.contains(k) || right.equals(rules.get(typo))) return;
        Integer n = learned.get(k);
        learned.put(k, n == null ? 1 : n + 1);
        while (learned.size() > 500) {                 // 가장 적게 모인 것부터 버린다
            String min = null;
            for (Map.Entry<String, Integer> e : learned.entrySet()) if (min == null || e.getValue() < learned.get(min)) min = e.getKey();
            learned.remove(min);
        }
        if (c != null) saveData(c);
    }

    /** 자음·모음만으로 된 말 (ㄹ, ㅂㅈ 등): 자동 교정에 넣으면 그 자모를 칠 때마다 바뀌어 버린다 */
    static boolean jamoOnly(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) { char ch = s.charAt(i); if (ch < 0x3131 || ch > 0x3163) return false; }
        return true;
    }

    /**
     * 모으거나 등록해도 되는 쌍인가: 자음·모음만으로 된 것(ㄹ → ㅎ), 한 글자 오타(키 → 티, 인 → 안)는 안 됨.
     * 한 글자는 앞뒤 문맥 없이 오타인지 알 수 없고, 등록하면 그 글자로 시작하는 말이 모두 바뀌어 버린다.
     * 보ㅓ → 봐 처럼 깨진 글자가 든 두 글자는 됨.
     */
    static boolean collectable(String typo, String right) {
        return typo != null && right != null && !typo.equals(right) && !jamoOnly(typo) && !jamoOnly(right) && typo.length() >= 2;
    }

    /** 이미 등록된 자동 교정이 이 오타를 고치는가 (보ㅓ 가 있으면 해보ㅓ 도 고쳐짐). 고치는 규칙의 오타, 없으면 null */
    static String coveredBy(String typo) {
        String[] m = matchRule(typo);
        return m == null ? null : m[2];
    }

    static boolean covered(String typo) { return coveredBy(typo) != null; }

    /**
     * 깨진 글자가 든 오타 쌍에서 바뀐 부분만 남긴다: 앞뒤의 같은 글자를 떼어 냄.
     * 해보ㅓ → 해봐 = 보ㅓ → 봐, 허ㅏ면 → 화면 = 허ㅏ → 화. 떼어 낸 뒤 자모만 남으면 앞 글자를 다시 붙인다.
     */
    static String[] core(String typo, String right) {
        int p = 0;
        while (p < typo.length() && p < right.length() && typo.charAt(p) == right.charAt(p)) p++;
        int q = 0;
        while (q < typo.length() - p && q < right.length() - p && typo.charAt(typo.length() - 1 - q) == right.charAt(right.length() - 1 - q)) q++;
        while (p > 0 && jamoOnly(typo.substring(p, typo.length() - q))) p--;
        String t = typo.substring(p, typo.length() - q), r = right.substring(p, right.length() - q);
        if (t.isEmpty() || r.isEmpty() || !hasLoneJamo(t)) return new String[]{typo, right};
        return new String[]{t, r};
    }

    /** 자동 교정 규칙 등록 (등록할 수 없는 것이면 false). 고친 횟수는 0부터 */
    static boolean addRule(String typo, String right) {
        if (typo == null || right == null || typo.isEmpty() || right.isEmpty() || typo.equals(right) || jamoOnly(typo) || typo.length() < 2) return false;
        rules.put(typo, right);
        ruleHits.put(typo, 0);
        return true;
    }

    static void removeRule(String typo) { rules.remove(typo); ruleHits.remove(typo); }

    /** 홀로 선 자모(ㄱ~ㅣ)가 들어 있나: 이런 말은 맞는 말일 수 없어서 어절 어디에 있어도 고친다 */
    static boolean hasLoneJamo(String s) {
        for (int i = 0; i < s.length(); i++) { char ch = s.charAt(i); if (ch >= 0x3131 && ch <= 0x3163) return true; }
        return false;
    }

    /**
     * 자동 교정(고정 바꾸기) 찾기: 어절 = 오타 그대로, 어절이 오타로 시작 (고ㅓ일 → 과일),
     * 또는 오타에 홀로 선 자모가 있으면 어절 안 어디서나 (학고ㅓ → 학과). 긴 오타 먼저.
     * @return {바꿀 부분(어절 안 오타 시작부터 끝까지), 바뀐 글} 또는 null
     */
    static String[] matchRule(String word) {
        String best = null; int at = -1;
        for (String k : rules.keySet()) {
            if (jamoOnly(k) || (best != null && k.length() <= best.length())) continue;
            int i = word.startsWith(k) ? 0 : hasLoneJamo(k) ? word.indexOf(k) : -1;
            if (i >= 0) { best = k; at = i; }
        }
        if (best == null) return null;
        String old = word.substring(at);
        return new String[]{old, rules.get(best) + old.substring(best.length()), best};
    }

    static boolean known(String w) {
        Integer n = words.get(w);
        return n != null && n >= 3;
    }

    private static final String[] JOSA = {"에서", "으로", "에게", "까지", "부터", "은", "는", "이", "가", "을", "를", "에", "의", "도", "로", "와", "과", "만"};

    /** 내 단어이거나, 내 단어 + 흔한 조사 */
    private static boolean knownWord(String w) {
        if (known(w)) return true;
        for (String j : JOSA) if (w.length() > j.length() + 1 && w.endsWith(j) && known(w.substring(0, w.length() - j.length()))) return true;
        return false;
    }

    // ------------------------------------------------------------------ language model
    private static volatile boolean lmReady, lmLoading;
    private static short[] tokIdx;
    private static float[] uni;
    private static int[] biKey;
    private static byte[] biVal;
    private static int nTok, spaceTok, otherTok;
    private static double total;

    static void loadLm(final Context c) {
        if (lmReady || lmLoading) return;
        lmLoading = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try (InputStream in = app.getAssets().open("typo_lm.bin")) { readLm(in); }
            catch (Throwable t) { android.util.Log.w("ThockTypo", "lm", t); }
        }, "thock-typo-lm").start();
    }

    static void readLm(InputStream in) throws Exception {
        DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(in, 1 << 16));
        byte[] magic = new byte[4];
        d.readFully(magic);
        int n = d.readInt();
        short[] ti = new short[0x10000];
        java.util.Arrays.fill(ti, (short) -1);
        byte[] buf = new byte[n * 2];
        d.readFully(buf);
        ByteBuffer bb = ByteBuffer.wrap(buf);
        for (int i = 0; i < n; i++) ti[bb.getChar()] = (short) i;
        buf = new byte[n * 4];
        d.readFully(buf);
        bb = ByteBuffer.wrap(buf);
        float[] u = new float[n];
        double tot = 0;
        for (int i = 0; i < n; i++) { u[i] = bb.getFloat(); tot += u[i]; }
        int m = d.readInt();
        buf = new byte[m * 4];
        d.readFully(buf);
        bb = ByteBuffer.wrap(buf);
        int[] k = new int[m];
        for (int i = 0; i < m; i++) k[i] = bb.getInt();
        byte[] v = new byte[m];
        d.readFully(v);
        tokIdx = ti; uni = u; biKey = k; biVal = v; nTok = n; total = tot;
        spaceTok = ti[' ']; otherTok = ti['#'];
        lmReady = true;
    }

    private static int tok(char c) {
        if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3131 && c <= 0x3163)) return tokIdx[c];
        return c == ' ' ? spaceTok : otherTok;
    }

    private static double lp(char a, char b) {
        int ia = tok(a), ib = tok(b);
        double pu = (ib >= 0 ? uni[ib] : 0.5) / total;
        if (ia < 0) return Math.log(pu);
        double cnt = 0;
        if (ib >= 0) {
            int i = java.util.Arrays.binarySearch(biKey, ia * nTok + ib);
            if (i >= 0) cnt = Math.pow(2, (biVal[i] & 0xFF) / 8.0);
        }
        return Math.log((cnt + 8.0 * pu) / (uni[ia] + 8.0));
    }

    /** 글이 얼마나 자연스러운가 (클수록 자연스러움). 앞은 띄어쓰기라고 본다. */
    static double score(String text) {
        double s = 0;
        char prev = ' ';
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); s += lp(prev, c); prev = c; }
        int st = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == ' ') {
                if (i - st >= 2 && knownWord(text.substring(st, i))) s += KNOWN_BONUS;
                st = i + 1;
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ 두벌식 조합 (쌍자음·ㅒㅖ 포함)
    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
    private static final String[] VCOMB = {"ㅗㅏㅘ", "ㅗㅐㅙ", "ㅗㅣㅚ", "ㅜㅓㅝ", "ㅜㅔㅞ", "ㅜㅣㅟ", "ㅡㅣㅢ"};
    private static final String[] JCOMB = {"ㄱㅅㄳ", "ㄴㅈㄵ", "ㄴㅎㄶ", "ㄹㄱㄺ", "ㄹㅁㄻ", "ㄹㅂㄼ", "ㄹㅅㄽ", "ㄹㅌㄾ", "ㄹㅍㄿ", "ㄹㅎㅀ", "ㅂㅅㅄ"};

    private static char comb(String[] table, char a, char b) {
        for (String t : table) if (t.charAt(0) == a && t.charAt(1) == b) return t.charAt(2);
        return 0;
    }

    static String compose(char[] keys, int from, int to) {
        StringBuilder out = new StringBuilder();
        char cho = 0, v1 = 0, v2 = 0, j1 = 0, j2 = 0;
        for (int k = from; k < to; k++) {
            char c = keys[k];
            if (JUNG.indexOf(c) >= 0 && c != ' ') {
                if (j1 != 0) {
                    char last = j2 != 0 ? j2 : j1;
                    if (j2 != 0) j2 = 0; else j1 = 0;
                    flush(out, cho, v1, v2, j1, j2);
                    cho = last; v1 = c; v2 = 0; j1 = 0; j2 = 0;
                } else if (v1 != 0) {
                    if (v2 == 0 && comb(VCOMB, v1, c) != 0) v2 = c;
                    else { flush(out, cho, v1, v2, j1, j2); cho = 0; v1 = c; v2 = 0; }
                } else v1 = c;
            } else if (CHO.indexOf(c) >= 0) {
                if (cho != 0 && v1 != 0) {
                    if (j1 == 0 && JONG.indexOf(c) > 0) j1 = c;
                    else if (j1 != 0 && j2 == 0 && comb(JCOMB, j1, c) != 0) j2 = c;
                    else { flush(out, cho, v1, v2, j1, j2); cho = c; v1 = 0; v2 = 0; j1 = 0; j2 = 0; }
                } else {
                    if (cho != 0 || v1 != 0) flush(out, cho, v1, v2, j1, j2);
                    cho = c; v1 = 0; v2 = 0; j1 = 0; j2 = 0;
                }
            } else {
                flush(out, cho, v1, v2, j1, j2);
                cho = 0; v1 = 0; v2 = 0; j1 = 0; j2 = 0;
                out.append(c);
            }
        }
        flush(out, cho, v1, v2, j1, j2);
        return out.toString();
    }

    private static void flush(StringBuilder out, char cho, char v1, char v2, char j1, char j2) {
        if (cho == 0 && v1 == 0) return;
        char v = v1 == 0 ? 0 : v2 != 0 ? comb(VCOMB, v1, v2) : v1;
        if (cho != 0 && v != 0) {
            char j = j1 == 0 ? ' ' : j2 != 0 ? comb(JCOMB, j1, j2) : j1;
            out.append((char) (0xAC00 + (CHO.indexOf(cho) * 21 + JUNG.indexOf(v)) * 28 + Math.max(0, JONG.indexOf(j))));
        } else out.append(cho != 0 ? cho : v);
    }

    // ------------------------------------------------------------------ 터치 가능성
    private static final double SX = 0.22, SY = 0.20;
    private static int biasVer = -1;
    private static final Map<String, float[]> bias = new HashMap<>();

    private static float[] biasOf(String id) {
        if (biasVer != Calib.samples.size()) {
            biasVer = Calib.samples.size();
            bias.clear();
            Map<String, float[]> sum = new HashMap<>();
            for (Calib.Sample s : Calib.samples) {
                float[] a = sum.get(s.t);
                if (a == null) sum.put(s.t, a = new float[3]);
                a[0] += s.dx; a[1] += s.dy; a[2]++;
            }
            for (Map.Entry<String, float[]> e : sum.entrySet()) {
                float[] a = e.getValue();
                if (a[2] >= 5) bias.put(e.getKey(), new float[]{a[0] / a[2], a[1] / a[2]});
            }
        }
        return bias.get(id);
    }

    /** 이 키를 노렸을 때 (x, y)를 누를 가능성 (log). 좌표는 Calib.standard() 단위. */
    private static double ll(String id, float x, float y) {
        Calib.Geo g = Calib.standard();
        int i = g.indexOf(id);
        if (i < 0) return -99;
        float[] r = g.rects.get(i);
        float[] b = biasOf(id);
        double cy = (r[1] + r[3]) / 2.0 + (b == null ? 0 : b[1]);
        double ly = -0.5 * Math.pow((y - cy) / SY, 2) - Math.log(SY * 2.5066);
        if (Calib.SP.equals(id)) {
            if (x < r[0] - 0.3 || x > r[2] + 0.3) return -99;
            return ly - Math.log(r[2] - r[0]);
        }
        double cx = (r[0] + r[2]) / 2.0 + (b == null ? 0 : b[0]);
        return ly - 0.5 * Math.pow((x - cx) / SX, 2) - Math.log(SX * 2.5066);
    }

    private static char above(float x) {
        Calib.Geo g = Calib.standard();
        for (int k = 0; k < OVER.length(); k++) {
            float[] r = g.rects.get(g.indexOf(OVER.substring(k, k + 1)));
            if (x >= r[0] && x < r[2]) return OVER.charAt(k);
        }
        return 0;
    }

    // ------------------------------------------------------------------ 친 키 기록
    static final class Stroke {
        final char c; final String id; final float x, y;
        Stroke(char c, String id, float x, float y) { this.c = c; this.id = id; this.x = x; this.y = y; }
    }

    static final ArrayList<Stroke> log = new ArrayList<>();
    private static Handler h0;
    private static Handler H() { if (h0 == null) h0 = new Handler(Looper.getMainLooper()); return h0; }
    private static Fix pending;
    private static Host pendingHost;

    static void push(char c, String id, float x, float y) {
        if (log.size() > 200) log.subList(0, 100).clear();
        log.add(new Stroke(c, id, x, y));
        // 고친 바로 뒤에 들어오는 띄어쓰기·마침표는 되돌리기를 막지 않는다
        if (lastFix != null && lastFix.armed && isBoundary(c)) lastFix.armed = false;
        else lastFix = null;
    }

    static void backspace() { if (!log.isEmpty()) log.remove(log.size() - 1); }

    static void reset() {
        log.clear();
        lastFix = null;
        clearPending();
    }

    private static void clearPending() {
        if (pending != null && pendingHost != null) pendingHost.show(null);
        pending = null;
    }

    static boolean usable(Host h) {
        try {
            EditorInfo e = h.editor();
            if (e == null) return false;
            int cls = e.inputType & InputType.TYPE_MASK_CLASS, var = e.inputType & InputType.TYPE_MASK_VARIATION;
            if (cls != InputType.TYPE_CLASS_TEXT) return false;
            return var != InputType.TYPE_TEXT_VARIATION_PASSWORD && var != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    && var != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD && var != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    && var != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS && var != InputType.TYPE_TEXT_VARIATION_URI;
        } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------ 후보 찾기
    static final class Fix {
        String oldTail, newTail, label, blockKey;
        double delta;
        String wantId, pressedId;      // 터치 보정 기록용
        boolean uu;                    // ㅠ·ㅜ 와 스페이스가 뒤바뀐 오타
        float x, y;
    }

    /** 기록의 뒤쪽(최근 어절 3개까지)이 화면의 글과 같은지 확인하고, 가장 그럴듯한 고침 하나를 찾는다. */
    static Fix find(Host h) { return find(h, false); }

    /** uuOnly: ㅠ·ㅜ 와 스페이스가 뒤바뀐 경우만 본다 (자동 교정용) */
    static Fix find(Host h, boolean uuOnly) {
        if (!lmReady || log.isEmpty()) return null;
        int n = log.size(), start = n, runs = 0;
        while (start > 0 && n - start < 48) {
            if (log.get(start - 1).c == ' ') { if (++runs >= 3) break; }
            start--;
        }
        if (start > 0 && log.get(start - 1).c != ' ') {          // 어절 중간에서 끊기면 다음 어절부터
            while (start < n && log.get(start).c != ' ') start++;
            if (start < n) start++;
        }
        int len = n - start;
        if (len < 3) return null;
        char[] keys = new char[len];
        for (int i = 0; i < len; i++) keys[i] = log.get(start + i).c;
        String base = compose(keys, 0, len);
        InputConnection ic = h.ic();
        if (ic == null) return null;
        CharSequence before = ic.getTextBeforeCursor(base.length(), 0);
        if (before == null || !base.contentEquals(before)) { log.clear(); return null; }   // 화면과 다르면 믿지 않는다

        double baseScore = score(base);
        Fix best = null;
        for (int i = 0; i < len; i++) {
            Stroke s = log.get(start + i);
            if (Float.isNaN(s.x)) continue;
            char alt;
            String altId;
            if (OVER.indexOf(s.c) >= 0 && s.c != ' ') { alt = ' '; altId = Calib.SP; }
            else if (s.c == ' ' && Calib.SP.equals(s.id)) { alt = above(s.x); if (alt == 0) continue; altId = String.valueOf(alt); }
            else continue;
            // ㅠㅠ ㅜㅜ ㅡㅡ 처럼 일부러 겹쳐 친 것은 건드리지 않는다
            if (s.c != ' ' && ((i > 0 && keys[i - 1] == s.c) || (i + 1 < len && keys[i + 1] == s.c))) continue;
            if (alt == ' ' && (i == 0 || i == len - 1 || keys[i - 1] == ' ' || keys[i + 1] == ' ')) continue;
            double llr = ll(altId, s.x, s.y) - ll(s.id, s.x, s.y);
            if (llr < -9) continue;
            // 바뀌는 어절의 시작
            int rs = i;
            if (alt != ' ') rs = i - 1;                          // 스페이스 → 글자: 앞 어절부터 합쳐짐
            while (rs > 0 && keys[rs - 1] != ' ') rs--;
            if (rs < 0) rs = 0;
            int re = i + 1;
            while (re < len && keys[re] != ' ') re++;
            String oldRun = compose(keys, rs, re);
            char keep = keys[i];
            keys[i] = alt;
            String cand = compose(keys, 0, len);
            double d = score(cand) - baseScore + llr;
            boolean uu = s.c == 'ㅠ' || s.c == 'ㅜ' || alt == 'ㅠ' || alt == 'ㅜ';
            if (uuOnly && !uu) { keys[i] = keep; continue; }
            if (d > T_SUGGEST && (best == null || d > best.delta)) {
                Fix f = new Fix();
                f.delta = d;
                f.oldTail = base.substring(compose(keys0(keys, i, keep), 0, rs).length());
                f.newTail = cand.substring(compose(keys, 0, rs).length());
                f.label = compose(keys, rs, re);
                f.blockKey = oldRun;
                f.wantId = altId; f.pressedId = s.id; f.x = s.x; f.y = s.y;
                f.uu = uu;
                best = f;
            }
            keys[i] = keep;
        }
        return best;
    }

    /** keys의 i번째만 원래 글자로 되돌린 복사본 */
    private static char[] keys0(char[] keys, int i, char orig) {
        char[] k = keys.clone();
        k[i] = orig;
        return k;
    }

    // ------------------------------------------------------------------ 제안 · 적용 · 되돌리기
    private static final class Done { String oldText, newText, blockKey, ruleKey; long time; boolean armed = true; }
    private static Done lastFix;

    /** 키를 친 뒤 잠깐 쉬면 제안을 계산한다 (타자를 늦추지 않게). */
    static void schedule(final Host h) {
        H().removeCallbacksAndMessages(null);
        if (!on || !lmReady) { clearPending(); return; }
        H().postDelayed(() -> {
            try {
                Fix f = usable(h) ? find(h) : null;
                pending = f;
                pendingHost = h;
                h.show(f == null ? null : f.label);
            } catch (Throwable t) { android.util.Log.w("ThockTypo", "eval", t); }
        }, 110);
    }

    /** 툴바의 제안을 눌렀을 때 */
    static void applyPending(Host h, Context c) {
        try {
            H().removeCallbacksAndMessages(null);
            Fix f = usable(h) ? find(h) : null;        // 그 사이에 글이 바뀌었을 수 있으니 다시 확인
            if (f != null) apply(h, c, f);
            else h.show(null);
            pending = null;
        } catch (Throwable t) { android.util.Log.w("ThockTypo", "apply", t); }
    }

    /** 글을 바꿀 때마다 늘어난다 (맞춤법 제안이 같은 어절을 다시 보지 않게) */
    static int fixSeq;

    private static void replaceTail(Host h, String oldText, String newText, String blockKey) {
        InputConnection ic = h.ic();
        if (ic == null) return;
        fixSeq++;
        h.finishComposing();
        ic.beginBatchEdit();
        ic.deleteSurroundingText(oldText.length(), 0);
        ic.commitText(newText, 1);
        ic.endBatchEdit();
        log.clear();
        Done d = new Done();
        d.oldText = oldText; d.newText = newText; d.blockKey = blockKey; d.time = android.os.SystemClock.uptimeMillis();
        lastFix = d;
    }

    private static void apply(Host h, Context c, Fix f) {
        replaceTail(h, f.oldTail, f.newTail, f.blockKey);
        h.show(null);
        pending = null;
        try {                                          // 이 터치의 정답을 알았으니 터치 보정 기록에도 넣는다
            if (learn && c != null) {
                Calib.Geo g = Calib.standard();
                int i = g.indexOf(f.wantId);
                if (i >= 0) {
                    float[] r = g.rects.get(i);
                    Calib.discardPending();
                    Calib.samples.add(new Calib.Sample(f.wantId, f.pressedId, f.x - (r[0] + r[2]) / 2f, f.y - (r[1] + r[3]) / 2f, 'F'));
                    Calib.saveSamples(c);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isBoundary(char c) { return c == ' ' || c == '\n' || c == '.' || c == ',' || c == '?' || c == '!'; }

    /**
     * 스페이스·마침표·엔터 등을 누르기 직전: 고정 바꾸기 → 자동 교정 → 단어 학습.
     */
    static void beforeBoundary(Host h, Context c, boolean koq) {
        try {
            H().removeCallbacksAndMessages(null);
            if (!usable(h)) { reset(); return; }
            InputConnection ic = h.ic();
            if (ic == null) return;
            if (!rules.isEmpty()) {
                CharSequence cs = ic.getTextBeforeCursor(40, 0);
                String tail = cs == null ? "" : cs.toString();
                int ws = tail.length();
                while (ws > 0 && !isBoundary(tail.charAt(ws - 1))) ws--;
                String[] m = ws < tail.length() ? matchRule(tail.substring(ws)) : null;
                if (m == null) for (Map.Entry<String, String> e : rules.entrySet()) {     // 띄어쓰기가 들어 있는 오타는 예전처럼 끝부분 그대로
                    String k = e.getKey();
                    if (k.indexOf(' ') < 0 || !tail.endsWith(k)) continue;
                    if (tail.length() > k.length() && !isBoundary(tail.charAt(tail.length() - k.length() - 1))) continue;
                    m = new String[]{k, e.getValue(), k};
                    break;
                }
                if (m != null) {
                    replaceTail(h, m[0], m[1], null);
                    if (lastFix != null) lastFix.ruleKey = m[2];
                    Integer hc = ruleHits.get(m[2]);
                    ruleHits.put(m[2], hc == null ? 1 : hc + 1);
                    if (++dirty >= 5) saveData(c);
                    clearPending();
                    return;
                }
            }
            if (!koq) return;
            if (autoUU) {                                  // ㅠ·ㅜ 오타는 확실하면 바로 고친다
                Fix u = find(h, true);
                if (u != null && u.delta > T_AUTO_UU) { apply(h, c, u); return; }
            }
            Fix f = on && auto ? find(h) : pending;
            if (on && auto && f != null && f.delta > T_AUTO) { apply(h, c, f); return; }
            // 단어 학습: 방금 끝난 어절 (제안이 걸려 있던 어절은 오타일 수 있으니 뺀다)
            if (learn && f == null && !log.isEmpty()) {
                int n = log.size(), st = n;
                while (st > 0 && log.get(st - 1).c != ' ') st--;
                if (n - st >= 3 && n - st <= 30) {
                    char[] keys = new char[n - st];
                    for (int i = 0; i < keys.length; i++) keys[i] = log.get(st + i).c;
                    String w = compose(keys, 0, keys.length);
                    boolean ok = w.length() >= 2 && w.length() <= 8;
                    for (int i = 0; ok && i < w.length(); i++) if (w.charAt(i) < 0xAC00 || w.charAt(i) > 0xD7A3) ok = false;
                    CharSequence before = ok ? ic.getTextBeforeCursor(w.length(), 0) : null;
                    if (ok && before != null && w.contentEquals(before)) {
                        Integer cnt = words.get(w);
                        if (cnt == null || cnt < MANUAL) { words.put(w, cnt == null ? 1 : cnt + 1); if (++dirty >= 15) saveData(c); }
                    }
                }
            }
            clearPending();
        } catch (Throwable t) { android.util.Log.w("ThockTypo", "boundary", t); }
    }

    /** 고친 직후의 지우기 = 되돌리기. 되돌렸으면 true (지우기는 하지 않는다). */
    static boolean undo(Host h, Context c) {
        try {
            Done d = lastFix;
            lastFix = null;
            if (d == null || android.os.SystemClock.uptimeMillis() - d.time > 15000) return false;
            InputConnection ic = h.ic();
            if (ic == null) return false;
            CharSequence before = ic.getTextBeforeCursor(d.newText.length() + 1, 0);
            if (before == null) return false;
            String b = before.toString();
            int extra = b.endsWith(d.newText) ? 0 : (b.length() > d.newText.length() && isBoundary(b.charAt(b.length() - 1)) && b.startsWith(d.newText, b.length() - 1 - d.newText.length()) ? 1 : -1);
            if (extra < 0) return false;
            h.finishComposing();
            ic.beginBatchEdit();
            ic.deleteSurroundingText(d.newText.length() + extra, 0);
            ic.commitText(d.oldText + (extra == 1 ? b.substring(b.length() - 1) : ""), 1);
            ic.endBatchEdit();
            log.clear();
            return true;
        } catch (Throwable t) { android.util.Log.w("ThockTypo", "undo", t); return false; }
    }

    static void flush(Context c) { if (dirty > 0) saveData(c); }
}
