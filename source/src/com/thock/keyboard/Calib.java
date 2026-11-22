package com.thock.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 오타 학습 · 터치 범위 보정 (쿼티 한글/영어 자판).
 *
 *  - 따라 쓰기: 정해진 문장을 치는 동안 "누르려던 키 / 실제 눌린 키 / 터치 위치"를 모은다 (지우기·스페이스 포함).
 *  - 추천: 키마다 실제로 누르는 위치의 평균 쪽으로 인식 범위의 중심을 옮긴다.
 *  - 인식: 각 키의 범위 = 키 칸을 (좌우·상하 이동, 크기 배율)만큼 바꾼 것. 터치는 "가장 깊이 들어온" 키로 간다.
 *    이동 0 · 크기 100%면 원래 키 칸과 똑같다.
 *
 * 설정 화면(Activity)과 키보드(IME)는 같은 프로세스라서 static 상태를 같이 쓴다.
 */
public final class Calib {
    private Calib() {}

    // ------------------------------------------------------------------ key ids
    static final String SP = "SP", BS = "BS", SH = "SH", EN = "EN", LG = "LG", SY = "SY", EM = "EM", DT = "DT";
    static final String R1 = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", R2 = "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", R3 = "ㅋㅌㅊㅍㅠㅜㅡ";
    private static final String LETTERS = R1 + R2 + R3;
    private static final String E1 = "qwertyuiop", E2 = "asdfghjkl", E3 = "zxcvbnm";
    private static final String SHIFTED = "ㅃㅉㄸㄲㅆㅒㅖ", UNSHIFT = "ㅂㅈㄷㄱㅅㅐㅔ";

    static boolean isLetter(String id) { return id != null && id.length() == 1 && LETTERS.indexOf(id) >= 0; }

    /** 키에 적힌 글자 → 자리 id (한글 기본 자모). 영어 쿼티는 같은 자리의 한글 자모로 본다. */
    static String idOfLabel(String label) {
        if (label == null || label.length() != 1) return null;
        char c = label.charAt(0);
        int i = SHIFTED.indexOf(c);
        if (i >= 0) c = UNSHIFT.charAt(i);
        if (LETTERS.indexOf(c) >= 0) return String.valueOf(c);
        c = Character.toLowerCase(c);
        if ((i = E1.indexOf(c)) >= 0) return R1.substring(i, i + 1);
        if ((i = E2.indexOf(c)) >= 0) return R2.substring(i, i + 1);
        if ((i = E3.indexOf(c)) >= 0) return R3.substring(i, i + 1);
        if (c == '.') return DT;
        return null;
    }

    static String nameOf(String id) {
        if (SP.equals(id)) return "스페이스";
        if (BS.equals(id)) return "지우기";
        if (SH.equals(id)) return "쉬프트";
        if (EN.equals(id)) return "엔터";
        if (LG.equals(id)) return "한/영";
        if (SY.equals(id)) return "!#1";
        if (EM.equals(id)) return "이모지";
        if (DT.equals(id)) return "마침표";
        if (id != null && id.startsWith("#")) return id.substring(1);
        return id;
    }

    static String shortLabel(String id) {
        if (SP.equals(id)) return "스페이스";
        if (BS.equals(id)) return "⌫";
        if (SH.equals(id)) return "⇧";
        if (EN.equals(id)) return "↵";
        if (LG.equals(id)) return "한/영";
        if (SY.equals(id)) return "!#1";
        if (EM.equals(id)) return "☺";
        if (DT.equals(id)) return ".";
        return id;
    }

    // ------------------------------------------------------------------ geometry
    /** 키 칸들의 위치. 화면 px(키보드) 또는 단위 좌표(가로 0~10, 세로 0~4: 설정 화면 그림·계산용). */
    static final class Geo {
        final List<String> ids = new ArrayList<>();
        final List<float[]> rects = new ArrayList<>();
        final List<Object> refs = new ArrayList<>();
        float uw = 1, uh = 1;     // 글자 키 한 칸의 너비·높이

        void add(String id, float l, float t, float r, float b, Object ref) {
            ids.add(id); rects.add(new float[]{l, t, r, b}); refs.add(ref);
        }

        int indexOf(String id) { return ids.indexOf(id); }
    }

    private static Geo std;

    /** 삼성 쿼티 배열 (숫자줄 제외 4줄), 단위 좌표. */
    static Geo standard() {
        if (std != null) return std;
        Geo g = new Geo();
        for (int i = 0; i < 10; i++) g.add(R1.substring(i, i + 1), i, 0, i + 1, 1, null);
        for (int i = 0; i < 9; i++) g.add(R2.substring(i, i + 1), i + 0.5f, 1, i + 1.5f, 2, null);
        g.add(SH, 0, 2, 1.5f, 3, null);
        for (int i = 0; i < 7; i++) g.add(R3.substring(i, i + 1), i + 1.5f, 2, i + 2.5f, 3, null);
        g.add(BS, 8.5f, 2, 10, 3, null);
        g.add(SY, 0, 3, 1.25f, 4, null);
        g.add(LG, 1.25f, 3, 2.25f, 4, null);
        g.add(EM, 2.25f, 3, 3.25f, 4, null);
        g.add(SP, 3.25f, 3, 7.5f, 4, null);
        g.add(DT, 7.5f, 3, 8.5f, 4, null);
        g.add(EN, 8.5f, 3, 10, 4, null);
        std = g;
        return g;
    }

    /** 보정값: id → {좌우 이동(키 너비 배), 상하 이동(키 높이 배), 크기 배율}. */
    static int pick(Geo g, float x, float y, Map<String, float[]> params) {
        int best = -1;
        float bs = Float.MAX_VALUE;
        for (int i = 0; i < g.rects.size(); i++) {
            float[] r = g.rects.get(i);
            float[] p = params == null ? null : params.get(g.ids.get(i));
            float ox = p == null ? 0 : p[0], oy = p == null ? 0 : p[1], s = p == null ? 1 : p[2];
            float cx = (r[0] + r[2]) / 2f + ox * g.uw, cy = (r[1] + r[3]) / 2f + oy * g.uh;
            float hw = (r[2] - r[0]) / 2f * s, hh = (r[3] - r[1]) / 2f * s;
            if (hw <= 0 || hh <= 0) continue;
            float sc = Math.max(Math.abs(x - cx) / hw, Math.abs(y - cy) / hh);
            if (sc < bs) { bs = sc; best = i; }
        }
        return best;
    }

    // ------------------------------------------------------------------ stored state
    static final float MAX_SHIFT = 0.40f, MIN_SCALE = 0.70f, MAX_SCALE = 1.40f;

    static final class Sample {
        final String t, p;          // 누르려던 키, 실제 눌린 키
        final float dx, dy;         // 누르려던 키 중심에서 벗어난 정도 (키 너비·높이 배)
        final char src;             // T 따라 쓰기, N 평소 타자(일부만), F 오타 교정, ? 출처 구분 전 기록
        Sample(String t, String p, float dx, float dy) { this(t, p, dx, dy, '?'); }
        Sample(String t, String p, float dx, float dy, char src) { this.t = t; this.p = p; this.dx = dx; this.dy = dy; this.src = src; }
        boolean ok() { return t.equals(p); }
    }

    private static boolean loaded, on;
    private static final Map<String, float[]> map = new HashMap<>();
    static final List<Sample> samples = new ArrayList<>();
    static float aspect = 1.45f;        // 키 높이 / 키 너비 (실제 키보드에서 본 값)
    private static final int MAX_SAMPLES = 4000;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("thock_ext", Context.MODE_PRIVATE); }

    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            SharedPreferences p = sp(c);
            on = p.getBoolean("calib_on", false);
            aspect = p.getFloat("calib_aspect", 1.45f);
            map.clear();
            String m = p.getString("calib_map", null);
            if (m != null) {
                JSONObject o = new JSONObject(m);
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONArray a = o.getJSONArray(k);
                    map.put(k, new float[]{(float) a.getDouble(0), (float) a.getDouble(1), (float) a.getDouble(2)});
                }
            }
            java.util.Arrays.fill(paint, (byte) -1);
            painted = 0;
            String pb = p.getString("calib_paint", null);
            if (pb != null) {
                byte[] b = java.util.Base64.getDecoder().decode(pb);
                if (b.length == paint.length) { System.arraycopy(b, 0, paint, 0, b.length); for (byte x : b) if (x >= 0) painted++; }
            }
            samples.clear();
            String s = p.getString("calib_samples", null);
            if (s != null && !s.isEmpty()) {
                for (String rec : s.split(";")) {
                    String[] f = rec.split("\\|");
                    if (f.length >= 4) samples.add(new Sample(f[0], f[1], Float.parseFloat(f[2]), Float.parseFloat(f[3]), f.length >= 5 && f[4].length() == 1 ? f[4].charAt(0) : '?'));
                }
            }
        } catch (Throwable t) { android.util.Log.w("ThockCalib", "load", t); }
    }

    private static void saveMap(Context c) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, float[]> e : map.entrySet()) {
                JSONArray a = new JSONArray();
                a.put(e.getValue()[0]); a.put(e.getValue()[1]); a.put(e.getValue()[2]);
                o.put(e.getKey(), a);
            }
            sp(c).edit().putString("calib_map", o.toString()).putBoolean("calib_on", on).apply();
        } catch (Throwable t) { android.util.Log.w("ThockCalib", "saveMap", t); }
    }

    // ------------------------------------------------------------------ 직접 그린 범위
    /** 표준 자판 좌표를 키 한 칸당 GR칸으로 나눈 격자. 칸마다 그 자리를 차지한 키 (표준 순번), -1 = 그리지 않음 */
    static final int GR = 20, GW = 10 * GR, GH = 5 * GR;
    static final float GY0 = -0.55f;                       // 격자 맨 위 = 첫 줄보다 0.55칸 위
    static final byte[] paint = new byte[GW * GH];
    static int painted;
    static { java.util.Arrays.fill(paint, (byte) -1); }
    private static final java.util.ArrayDeque<byte[]> undo = new java.util.ArrayDeque<>();

    static int cell(float x, float y) {
        int i = (int) Math.floor(x * GR), j = (int) Math.floor((y - GY0) * GR);
        return i < 0 || j < 0 || i >= GW || j >= GH ? -1 : j * GW + i;
    }

    static float cellX(int c) { return (c % GW + 0.5f) / GR; }
    static float cellY(int c) { return GY0 + (c / GW + 0.5f) / GR; }

    /** 그린 범위에서 이 위치의 키 (표준 순번), 없으면 -1 */
    static int paintAt(float x, float y) {
        if (painted == 0) return -1;
        int c = cell(x, y);
        return c < 0 ? -1 : paint[c];
    }

    /** 표준 좌표에서 최종 인식: 그린 범위가 먼저, 없으면 이동·크기 보정 */
    static int pickStd(float x, float y, Map<String, float[]> params) {
        int k = paintAt(x, y);
        return k >= 0 ? k : pick(standard(), x, y, params);
    }

    /** 칸마다 그리지 않았을 때의 주인 (except >= 0 이면 그 키를 뺀 가장 가까운 키) */
    static int[] baseOwners(Map<String, float[]> params, int except) {
        Geo g = standard();
        int[] out = new int[GW * GH];
        for (int c = 0; c < out.length; c++) out[c] = pickEx(g, cellX(c), cellY(c), params, except);
        return out;
    }

    static int pickEx(Geo g, float x, float y, Map<String, float[]> params, int except) {
        int best = -1;
        float bs = Float.MAX_VALUE;
        for (int i = 0; i < g.rects.size(); i++) {
            if (i == except) continue;
            float[] r = g.rects.get(i);
            float[] p = params == null ? null : params.get(g.ids.get(i));
            float ox = p == null ? 0 : p[0], oy = p == null ? 0 : p[1], sc = p == null ? 1 : p[2];
            float cx = (r[0] + r[2]) / 2f + ox * g.uw, cy = (r[1] + r[3]) / 2f + oy * g.uh;
            float hw = (r[2] - r[0]) / 2f * sc, hh = (r[3] - r[1]) / 2f * sc;
            float d = Math.max(Math.abs(x - cx) / hw, Math.abs(y - cy) / hh);
            if (d < bs) { bs = d; best = i; }
        }
        return best;
    }

    /** 다른 키의 가운데 부분(가로·세로 30%)은 그리기로 뺏지 않는다: 그 키를 아예 못 누르게 되는 걸 막는다 */
    static boolean coreOfOther(int c, int key) {
        Geo g = standard();
        float x = cellX(c), y = cellY(c);
        for (int i = 0; i < g.rects.size(); i++) {
            if (i == key) continue;
            float[] r = g.rects.get(i);
            float cx = (r[0] + r[2]) / 2f, cy = (r[1] + r[3]) / 2f, hw = (r[2] - r[0]) * 0.15f, hh = (r[3] - r[1]) * 0.15f;
            if (Math.abs(x - cx) <= hw && Math.abs(y - cy) <= hh) return true;
        }
        return false;
    }

    static void pushUndo() {
        undo.push(paint.clone());
        while (undo.size() > 40) undo.removeLast();
    }

    static boolean canUndo() { return !undo.isEmpty(); }

    static void undo(Context c) {
        if (undo.isEmpty()) return;
        byte[] b = undo.pop();
        System.arraycopy(b, 0, paint, 0, paint.length);
        savePaint(c);
    }

    static void clearUndo() { undo.clear(); }

    static void savePaint(Context c) {
        int n = 0;
        for (byte b : paint) if (b >= 0) n++;
        painted = n;
        try {
            SharedPreferences.Editor e = sp(c).edit();
            if (n == 0) e.remove("calib_paint");
            else e.putString("calib_paint", java.util.Base64.getEncoder().encodeToString(paint));
            e.apply();
        } catch (Throwable t) { android.util.Log.w("ThockCalib", "savePaint", t); }
    }

    /** 이 키를 그린 흔적을 지운다 (그려서 넓힌 곳 + 지우개로 뺀 곳 모두) */
    static void clearKeyPaint(Context c, String id) {
        int k = standard().indexOf(id);
        if (k < 0) return;
        int[] base = baseOwners(map, -1);
        for (int i = 0; i < paint.length; i++) if (paint[i] == k || (paint[i] >= 0 && base[i] == k)) paint[i] = -1;
        savePaint(c);
    }

    static void saveSamples(Context c) {
        if (c == null) return;
        try {
            while (samples.size() > MAX_SAMPLES) samples.remove(0);
            StringBuilder sb = new StringBuilder();
            for (Sample s : samples) {
                if (sb.length() > 0) sb.append(';');
                sb.append(s.t).append('|').append(s.p).append('|').append(Math.round(s.dx * 1000) / 1000f).append('|').append(Math.round(s.dy * 1000) / 1000f).append('|').append(s.src);
            }
            sp(c).edit().putString("calib_samples", sb.toString()).putFloat("calib_aspect", aspect).apply();
        } catch (Throwable t) { android.util.Log.w("ThockCalib", "saveSamples", t); }
    }

    static void clearSamples(Context c) { load(c); samples.clear(); saveSamples(c); }

    static boolean isOn(Context c) { load(c); return on; }
    static void setOn(Context c, boolean v) { load(c); on = v; saveMap(c); }
    /** 키보드가 보정을 써야 하는가 */
    static boolean active(Context c) { load(c); return on && (!map.isEmpty() || painted > 0); }
    static Map<String, float[]> params() { return map; }

    static float[] param(String id) {
        float[] p = map.get(id);
        return p == null ? new float[]{0, 0, 1} : p;
    }

    static void setParam(Context c, String id, float ox, float oy, float s) {
        load(c);
        ox = clamp(ox, -MAX_SHIFT, MAX_SHIFT); oy = clamp(oy, -MAX_SHIFT, MAX_SHIFT); s = clamp(s, MIN_SCALE, MAX_SCALE);
        if (Math.abs(ox) < 0.005f && Math.abs(oy) < 0.005f && Math.abs(s - 1) < 0.005f) map.remove(id);
        else map.put(id, new float[]{ox, oy, s});
        saveMap(c);
    }

    static void resetParams(Context c) { load(c); map.clear(); saveMap(c); java.util.Arrays.fill(paint, (byte) -1); undo.clear(); savePaint(c); }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------------ analysis
    // ---- 최근 기록 위주 · 출처별 무게
    static final int RECENT = 300;          // 키마다 최근 몇 개까지 쓰는가
    static final float HALF_LIFE = 150f;    // 이만큼 오래되면 무게가 절반

    /** 평소 타자에서 제대로 누른 터치를 기록하는 비율 (키마다). 오타 교정 기록도 같은 비율로 무게를 줘서 오타가 부풀려지지 않게 */
    static float normalRate(String id) {
        if (SP.equals(id)) return 0.34f;
        if (BS.equals(id)) return 0.25f;
        return 0.25f;
    }

    static float srcWeight(Sample s) { return s.src == 'F' ? normalRate(s.t) : 1f; }

    /** id → {가중 좌우 합, 가중 상하 합, 가중치 합, 개수} : 최근 RECENT개, 오래될수록 가볍게 */
    static Map<String, float[]> weightedSums() {
        Map<String, float[]> sum = new HashMap<>();
        for (int i = samples.size() - 1; i >= 0; i--) {
            Sample s = samples.get(i);
            float[] a = sum.get(s.t);
            if (a == null) sum.put(s.t, a = new float[4]);
            if (a[3] >= RECENT) continue;
            float w = (float) Math.pow(0.5, a[3] / HALF_LIFE) * srcWeight(s);
            a[0] += s.dx * w; a[1] += s.dy * w; a[2] += w; a[3]++;
        }
        return sum;
    }

    /** 키마다 실제로 누른 위치의 (최근 위주) 평균 쪽으로 중심을 옮긴 추천값 (크기 배율은 지금 값을 그대로 둔다). */
    static Map<String, float[]> recommend() {
        Map<String, float[]> sum = weightedSums();
        Map<String, float[]> out = new HashMap<>();
        for (Map.Entry<String, float[]> e : map.entrySet()) {
            if (Math.abs(e.getValue()[2] - 1) >= 0.005f) out.put(e.getKey(), new float[]{0, 0, e.getValue()[2]});
        }
        for (Map.Entry<String, float[]> e : sum.entrySet()) {
            float[] a = e.getValue();
            if (a[3] < 5 || a[2] <= 0) continue;          // 너무 적으면 믿지 않는다
            float n = Math.min(a[3], a[2] * 2);           // 무게로 줄어든 만큼 덜 믿는다
            float k = n / (n + 4f);                       // 적게 모인 키는 조금만 옮긴다
            float ox = SP.equals(e.getKey()) ? 0 : clamp(a[0] / a[2] * k, -MAX_SHIFT, MAX_SHIFT);   // 스페이스는 넓어서 상하만
            float oy = clamp(a[1] / a[2] * k, -MAX_SHIFT, MAX_SHIFT);
            float[] cur = out.get(e.getKey());
            float sc = cur == null ? 1 : cur[2];
            if (Math.abs(ox) < 0.01f && Math.abs(oy) < 0.01f && Math.abs(sc - 1) < 0.005f) continue;
            out.put(e.getKey(), new float[]{ox, oy, sc});
        }
        return out;
    }

    static void applyRecommended(Context c) {
        load(c);
        Map<String, float[]> r = recommend();
        map.clear();
        map.putAll(r);
        on = true;
        saveMap(c);
    }

    /** 그린 범위를 모두 지우고 추천 범위만 */
    static void applyRecommendedClean(Context c) {
        load(c);
        java.util.Arrays.fill(paint, (byte) -1);
        undo.clear();
        savePaint(c);
        applyRecommended(c);
    }

    static int countSrc(char src) { int n = 0; for (Sample s : samples) if (s.src == src) n++; return n; }

    static void removeSrc(Context c, char src) {
        for (Iterator<Sample> it = samples.iterator(); it.hasNext(); ) if (it.next().src == src) it.remove();
        saveSamples(c);
    }

    /** 이 키만 추천 범위로 (기록이 5번보다 적으면 false) */
    static boolean applyRecommendedKey(Context c, String id) {
        load(c);
        float[] r = recommend().get(id);
        int n = 0;
        for (Sample s : samples) if (s.t.equals(id)) n++;
        if (n < 5) return false;
        float sc = param(id)[2];
        setParam(c, id, r == null ? 0 : r[0], r == null ? 0 : r[1], sc);
        on = true;
        saveMap(c);
        return true;
    }

    /** 키보드 높이를 바꾸면 키 모양(높이/너비)이 바뀐다: 설정 화면 그림도 같은 비율로 */
    static void setAspect(Context c, float v) {
        if (v <= 0 || Math.abs(v - aspect) < 0.02f) return;
        aspect = v;
        sp(c).edit().putFloat("calib_aspect", v).apply();
    }


    /** 모아둔 터치를 이 보정값으로 다시 인식했을 때 오타 수. */
    static int errorsWith(Map<String, float[]> params) { return errorsWith(params, true); }

    /** usePaint=false: 그린 범위 없이 */
    static int errorsWith(Map<String, float[]> params, boolean usePaint) {
        Geo g = standard();
        int err = 0;
        for (Sample s : samples) {
            int i = g.indexOf(s.t);
            if (i < 0) continue;
            float[] r = g.rects.get(i);
            float x = (r[0] + r[2]) / 2f + s.dx, y = (r[1] + r[3]) / 2f + s.dy;
            int k = usePaint ? pickStd(x, y, params) : pick(g, x, y, params);
            if (k < 0 || !g.ids.get(k).equals(s.t)) err++;
        }
        return err;
    }

    /** id → {횟수, 오타 수} */
    static Map<String, int[]> stats() {
        Map<String, int[]> m = new HashMap<>();
        for (Sample s : samples) {
            int[] a = m.get(s.t);
            if (a == null) m.put(s.t, a = new int[2]);
            a[0]++;
            if (!s.ok()) a[1]++;
        }
        return m;
    }

    /** "ㅅ 3번, ㄷ 2번" : 이 키를 누르려다 대신 눌린 키들 */
    static String confusions(String id) {
        final Map<String, Integer> m = new HashMap<>();
        for (Sample s : samples) if (s.t.equals(id) && !s.ok()) { Integer n = m.get(s.p); m.put(s.p, n == null ? 1 : n + 1); }
        List<String> keys = new ArrayList<>(m.keySet());
        Collections.sort(keys, (a, b) -> m.get(b) - m.get(a));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size() && i < 4; i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(nameOf(keys.get(i))).append(' ').append(m.get(keys.get(i))).append("번");
        }
        return sb.toString();
    }

    /** {평균 좌우, 평균 상하, 횟수} */
    static float[] meanOf(String id) {
        float[] a = weightedSums().get(id);
        return a == null || a[2] <= 0 ? new float[]{0, 0, 0} : new float[]{a[0] / a[2], a[1] / a[2], a[3]};
    }

    // ------------------------------------------------------------------ 평소 타자에서 일부 기록
    /** 친 뒤 지우지 않고 이만큼 더 친 터치만 "제대로 누른 것"으로 기록한다 */
    private static final int SETTLE = 6;
    private static final class Pend { final Sample s; final int seq; Pend(Sample s, int seq) { this.s = s; this.seq = seq; } }
    private static final ArrayList<Pend> pend = new ArrayList<>();
    private static int keySeq, unsaved;
    private static final java.util.Random rnd = new java.util.Random();

    /** 글자·스페이스·마침표가 입력됨 (dx, dy = 눌린 키 가운데에서 벗어난 정도) */
    static void noteKey(Context c, String id, float dx, float dy) {
        keySeq++;
        if (rnd.nextFloat() < normalRate(id)) pend.add(new Pend(new Sample(id, id, dx, dy, 'N'), keySeq));
        settle(c, false);
    }

    /** 지우기: 방금 친 터치는 틀렸을 수 있으니 버린다. 지우기 키 자체는 일부 기록 */
    static void noteBackspace(Context c, float dx, float dy) {
        if (!pend.isEmpty() && pend.get(pend.size() - 1).seq == keySeq) pend.remove(pend.size() - 1);
        if (keySeq > 0) keySeq--;
        if (rnd.nextFloat() < normalRate(BS)) { samples.add(new Sample(BS, BS, dx, dy, 'N')); unsaved++; }
        settle(c, false);
    }

    /** 지우기를 누르고 있어서 계속 지워질 때 */
    static void noteBackspaceRepeat() {
        if (!pend.isEmpty() && pend.get(pend.size() - 1).seq == keySeq) pend.remove(pend.size() - 1);
        if (keySeq > 0) keySeq--;
    }

    /** 오타를 고쳤을 때: 아직 확정 안 된 기록은 버린다 (틀린 터치가 섞였을 수 있음) */
    static void discardPending() { pend.clear(); }

    /** 지우고 다시 친 것에서 배우기 (FixLearn) */
    static final FixLearn fix = new FixLearn();

    /** FixLearn 이 찾은 것: 오타는 오타 교정 기록(F)으로 모두, 제대로 누른 것은 평소 기록(N)과 같은 비율로 */
    static void addLearned(Context c, List<FixLearn.Learned> ls) {
        if (ls == null) return;
        boolean err = false;
        for (FixLearn.Learned l : ls) {
            if (l.error) { samples.add(new Sample(l.want, l.pressed, l.dx, l.dy, 'F')); err = true; }
            else if (rnd.nextFloat() < normalRate(l.want)) samples.add(new Sample(l.want, l.want, l.dx, l.dy, 'N'));
            else continue;
            unsaved++;
        }
        settle(c, false);
        if (err) { unsaved = 0; saveSamples(c); }        // 오타 기록은 바로 저장
    }

    /** 확정된 기록을 넣는다. finish = 입력을 마칠 때 (아직 덜 확정된 건 버림) */
    static void settle(Context c, boolean finish) {
        while (!pend.isEmpty() && pend.get(0).seq <= keySeq - SETTLE) { samples.add(pend.remove(0).s); unsaved++; }
        if (finish) { pend.clear(); keySeq = 0; }
        if (unsaved >= (finish ? 1 : 25)) { unsaved = 0; saveSamples(c); }
    }

    // ------------------------------------------------------------------ 두벌식 조합 (따라 쓰기 화면용)
    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
    private static final String[] VCOMB = {"ㅗㅏㅘ", "ㅗㅐㅙ", "ㅗㅣㅚ", "ㅜㅓㅝ", "ㅜㅔㅞ", "ㅜㅣㅟ", "ㅡㅣㅢ"};
    private static final String[] JCOMB = {"ㄱㅅㄳ", "ㄴㅈㄵ", "ㄴㅎㄶ", "ㄹㄱㄺ", "ㄹㅁㄻ", "ㄹㅂㄼ", "ㄹㅅㄽ", "ㄹㅌㄾ", "ㄹㅍㄿ", "ㄹㅎㅀ", "ㅂㅅㅄ"};

    private static char comb(String[] table, char a, char b) {
        for (String t : table) if (t.charAt(0) == a && t.charAt(1) == b) return t.charAt(2);
        return 0;
    }

    private static String split(String[] table, char c) {
        for (String t : table) if (t.charAt(2) == c) return t.substring(0, 2);
        return String.valueOf(c);
    }

    /** 문장 → 눌러야 하는 키 순서. 쉬프트가 필요한 글자(쌍자음·ㅒ·ㅖ)가 있으면 null. */
    static List<String> decompose(String s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') { out.add(SP); continue; }
            if (c < 0xAC00 || c > 0xD7A3) return null;
            int v = c - 0xAC00;
            String parts = String.valueOf(CHO.charAt(v / 588)) + split(VCOMB, JUNG.charAt(v % 588 / 28));
            if (v % 28 != 0) parts += split(JCOMB, JONG.charAt(v % 28));
            for (int k = 0; k < parts.length(); k++) {
                if (LETTERS.indexOf(parts.charAt(k)) < 0) return null;
                out.add(parts.substring(k, k + 1));
            }
        }
        return out;
    }

    static final class Composed {
        final StringBuilder text = new StringBuilder();
        final List<int[]> span = new ArrayList<>();      // 글자마다: 그 글자를 만든 키 순번들
    }

    /** 키 순서 → 화면에 보이는 글 (두벌식 조합). */
    static Composed compose(List<String> keys) {
        Composed out = new Composed();
        char cho = 0;
        String jung = "", jong = "";
        List<Integer> idx = new ArrayList<>();
        for (int k = 0; k < keys.size(); k++) {
            String id = keys.get(k);
            char c = isLetter(id) ? id.charAt(0) : 0;
            if (c == 0) {
                flush(out, cho, jung, jong, idx);
                cho = 0; jung = ""; jong = ""; idx = new ArrayList<>();
                out.text.append(SP.equals(id) ? " " : DT.equals(id) ? "." : id.startsWith("#") ? id.substring(1) : "?");
                out.span.add(new int[]{k});
            } else if (JUNG.indexOf(c) >= 0) {                 // 모음
                if (!jong.isEmpty()) {
                    char last = jong.charAt(jong.length() - 1);
                    Integer li = idx.remove(idx.size() - 1);
                    flush(out, cho, jung, jong.substring(0, jong.length() - 1), idx);
                    cho = last; jung = String.valueOf(c); jong = "";
                    idx = new ArrayList<>();
                    idx.add(li); idx.add(k);
                } else if (!jung.isEmpty()) {
                    if (jung.length() == 1 && comb(VCOMB, jung.charAt(0), c) != 0) { jung += c; idx.add(k); }
                    else { flush(out, cho, jung, jong, idx); cho = 0; jung = String.valueOf(c); idx = new ArrayList<>(); idx.add(k); }
                } else { jung = String.valueOf(c); idx.add(k); }
            } else {                                           // 자음
                if (cho != 0 && !jung.isEmpty()) {
                    if (jong.isEmpty()) { jong = String.valueOf(c); idx.add(k); }
                    else if (jong.length() == 1 && comb(JCOMB, jong.charAt(0), c) != 0) { jong += c; idx.add(k); }
                    else { flush(out, cho, jung, jong, idx); cho = c; jung = ""; jong = ""; idx = new ArrayList<>(); idx.add(k); }
                } else {
                    if (cho != 0 || !jung.isEmpty()) { flush(out, cho, jung, jong, idx); idx = new ArrayList<>(); }
                    cho = c; jung = ""; jong = "";
                    idx.add(k);
                }
            }
        }
        flush(out, cho, jung, jong, idx);
        return out;
    }

    private static void flush(Composed out, char cho, String jung, String jong, List<Integer> idx) {
        if (cho == 0 && jung.isEmpty()) return;
        char v = jung.isEmpty() ? 0 : jung.length() == 2 ? comb(VCOMB, jung.charAt(0), jung.charAt(1)) : jung.charAt(0);
        char ch;
        if (cho != 0 && v != 0) {
            char j = jong.isEmpty() ? ' ' : jong.length() == 2 ? comb(JCOMB, jong.charAt(0), jong.charAt(1)) : jong.charAt(0);
            ch = (char) (0xAC00 + (CHO.indexOf(cho) * 21 + JUNG.indexOf(v)) * 28 + Math.max(0, JONG.indexOf(j)));
        } else ch = cho != 0 ? cho : v;
        out.text.append(ch);
        int[] a = new int[idx.size()];
        for (int i = 0; i < a.length; i++) a[i] = idx.get(i);
        out.span.add(a);
    }

    // ------------------------------------------------------------------ 따라 쓰기
    /** 쉬프트 없이 칠 수 있고, 자판 26키를 골고루 쓰는 문장들. */
    static final String[] SENTENCES = {
            "오늘 하늘이 참 맑고 푸르네요",
            "토요일 아침에 자전거를 타고 달려요",
            "바다를 보며 밥과 반찬을 배불리 먹어요",
            "야구장에서 약속한 친구를 기다려요",
            "휴대폰 충전기를 챙겨서 출근해요",
            "유리컵에 우유를 가득 담아 두어요",
            "크리스마스 트리 옆에 케이크를 놓아요",
            "퇴근 후에 대학 동창과 영화를 보러 가요",
            "규칙적인 운동과 휴식은 건강에 좋대요",
            "택배 상자에 튼튼한 테이프를 붙여요",
            "약국 옆 카페에서 쿠키와 초콜릿을 팔아요",
            "얇은 이불을 덮고 야식 생각을 참아요",
            "매일 저녁 일기장에 재미난 일을 적어요",
            "냠냠 맛난 귤과 달걀 요리를 준비해요",
    };

    static boolean training;
    static Trainer trainer;

    static void stopTraining(Context c) {
        boolean had = training || trainer != null;
        training = false;
        trainer = null;
        if (had) saveSamples(c);
    }

    static final class Trainer {
        final String sentence;
        final List<String> target;
        final List<String> stack = new ArrayList<>();
        Runnable onChange;

        Trainer(String sentence) {
            this.sentence = sentence;
            List<String> t = decompose(sentence);
            target = t == null ? new ArrayList<String>() : t;
        }

        boolean done() { return stack.equals(target); }

        boolean badAt(int j) { return j >= target.size() || !stack.get(j).equals(target.get(j)); }

        private boolean hasError() {
            for (int j = 0; j < stack.size(); j++) if (badAt(j)) return true;
            return false;
        }

        private static boolean near(Geo g, String id, float x, float y) {
            int i = g.indexOf(id);
            if (i < 0) return false;
            float[] r = g.rects.get(i);
            return x >= r[0] - g.uw && x <= r[2] + g.uw && y >= r[1] - g.uh && y <= r[3] + g.uh;
        }

        private static void record(Geo g, String want, String pressed, float x, float y) {
            int i = g.indexOf(want);
            if (i < 0) return;
            float[] r = g.rects.get(i);
            samples.add(new Sample(want, pressed, (x - (r[0] + r[2]) / 2f) / g.uw, (y - (r[1] + r[3]) / 2f) / g.uh, 'T'));
        }

        /**
         * 키 하나가 눌림. pid = 눌린 키, (x, y) = 터치 위치, g = 그때의 키 위치.
         * 눌린 키들은 "쌓았다가 지우기로 하나씩 빼는" 식으로 관리해서, 지우고 다시 친 것도 같은 자리의 글자로 본다.
         */
        void onKey(String pid, float x, float y, Geo g) {
            if (pid == null) return;
            final int i = stack.size(), n = target.size();
            final String want = i < n ? target.get(i) : null;
            final boolean err = hasError();
            final boolean prevOk = i == 0 || !badAt(i - 1);
            if (BS.equals(pid)) {
                if (err) record(g, BS, BS, x, y);                                  // 틀린 걸 지우려고 누른 지우기
                else if (want != null && near(g, want, x, y)) record(g, want, BS, x, y);   // 글자를 치려다 지우기를 누름
                if (i > 0) stack.remove(i - 1);
            } else {
                boolean types = isLetter(pid) || SP.equals(pid) || DT.equals(pid) || pid.startsWith("#");
                if (want != null && pid.equals(want)) {
                    record(g, want, pid, x, y);
                } else {
                    // 한 글자를 빼먹고 다음 글자를 친 것처럼 보이면 오타로 세지 않는다
                    boolean skipped = i + 1 < n && pid.equals(target.get(i + 1));
                    if (want != null && prevOk && !skipped && near(g, want, x, y)) record(g, want, pid, x, y);
                    else if (err && !SP.equals(pid) && near(g, BS, x, y)) record(g, BS, pid, x, y);   // 지우려다 옆 키를 누름
                }
                if (types && stack.size() < n + 12) stack.add(pid);
            }
            if (onChange != null) onChange.run();
        }
    }

    // ------------------------------------------------------------------ 설정 화면 그림
    /**
     * 자판 그림.
     *  결과(regions=false): 터치 점 초록/빨강.
     *  상세(regions=true): 키를 안 골랐으면 키마다 색칠, 골랐으면 그 키의 범위만 색칠 + 그 키의 터치 점.
     *  그리기 모드: 손가락으로 그 키의 범위를 그리거나 지운다.
     */
    public static final class MapView extends View {
        public interface Pick { void on(String id); }

        private static final float TOP = -GY0, BOT = 0.45f;      // 자판 위아래 여백 (줄 높이 배) = 격자 범위
        private final boolean regions;
        private final int cText, cLine, cAccent;
        private final boolean dark;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private Bitmap raster;
        private int[] px, base, baseEx;
        String sel;
        Pick pick;
        boolean editing;
        int tool;                 // 0 그리기, 1 지우개
        float brushDp = 14;
        Runnable onEdited;
        private float lastX, lastY;
        private boolean stroking;
        /** 확대·이동 (페이지를 다시 그려도 유지). pan은 보이는 화면 크기에 대한 비율 */
        static float zoom = 1f, panFx, panFy;
        static boolean hideDots;

        static void resetView() { zoom = 1f; panFx = 0; panFy = 0; }

        private float panX() { return panFx * getWidth(); }
        private float panY() { return panFy * getHeight(); }

        private void clampPan() {
            float maxF = zoom - 1f;
            panFx = Math.max(0, Math.min(maxF, panFx));
            panFy = Math.max(0, Math.min(maxF, panFy));
        }

        /** 화면 가운데를 기준으로 확대·축소 */
        void zoomBy(float f) {
            float nz = Math.max(1f, Math.min(5f, zoom * f));
            float cx = panFx + 0.5f, cy = panFy + 0.5f;               // 가운데 (화면 비율, 확대 전 좌표 * zoom)
            panFx = cx / zoom * nz - 0.5f;
            panFy = cy / zoom * nz - 0.5f;
            zoom = nz;
            clampPan();
            invalidate();
        }

        /** 화면 크기의 비율만큼 이동 (오른쪽·아래가 +) */
        void panBy(float fx, float fy) { panFx += fx; panFy += fy; clampPan(); invalidate(); }

        void fit() { resetView(); invalidate(); }

        void setHideDots(boolean v) { hideDots = v; invalidate(); }

        public MapView(Context c, boolean regions, int cText, int cLine, int cAccent, boolean dark) {
            super(c);
            this.regions = regions; this.cText = cText; this.cLine = cLine; this.cAccent = cAccent; this.dark = dark;
        }

        private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
        private float ux() { return getWidth() / 10f; }
        private float uy() { return ux() * Math.max(1.0f, Math.min(2.0f, aspect)); }
        private int selIdx() { return sel == null ? -1 : standard().indexOf(sel); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            float uy = w / 10f * Math.max(1.0f, Math.min(2.0f, aspect));
            setMeasuredDimension(w, Math.round(uy * (4 + TOP + BOT)));
        }

        void refresh() { raster = null; base = null; baseEx = null; invalidate(); }

        private void ensureBase() {
            if (base == null) base = baseOwners(map, -1);
            int s = selIdx();
            if (s >= 0 && baseEx == null) baseEx = baseOwners(map, s);
        }

        private void fillRaster() {
            ensureBase();
            if (px == null) px = new int[GW * GH];
            int s = selIdx();
            float[] hsv = new float[3];
            int on = Color.argb(dark ? 170 : 150, Color.red(cAccent), Color.green(cAccent), Color.blue(cAccent));
            int off = dark ? 0x12FFFFFF : 0x0D000000;
            for (int c = 0; c < px.length; c++) {
                int k = paint[c] >= 0 ? paint[c] : base[c];
                if (s >= 0) px[c] = k == s ? on : off;
                else { hsv[0] = (k * 137) % 360; hsv[1] = dark ? 0.55f : 0.38f; hsv[2] = dark ? 0.50f : 0.97f; px[c] = Color.HSVToColor(hsv); }
            }
            if (raster == null) raster = Bitmap.createBitmap(GW, GH, Bitmap.Config.ARGB_8888);
            raster.setPixels(px, 0, GW, 0, 0, GW, GH);
        }

        @Override
        protected void onDraw(Canvas c) {
            Geo g = standard();
            float ux = ux(), uy = uy(), y0 = TOP * uy, in = dp(2.5f);
            boolean one = sel != null;                          // 키를 골랐으면 그 키의 범위만 색칠
            float z = regions ? zoom : 1f;
            c.save();
            c.clipRect(0, 0, getWidth(), getHeight());
            if (regions) { c.translate(-panX(), -panY()); c.scale(z, z); }
            if (regions || sel != null) {
                if (raster == null) fillRaster();
                rf.set(0, 0, getWidth(), getHeight());
                p.setFilterBitmap(false);
                c.drawBitmap(raster, null, rf, p);
            }
            for (int i = 0; i < g.ids.size(); i++) {
                float[] r = g.rects.get(i);
                String id = g.ids.get(i);
                rf.set(r[0] * ux + in, y0 + r[1] * uy + in, r[2] * ux - in, y0 + r[3] * uy - in);
                boolean s = id.equals(sel);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(s ? 2.5f : 1f) / z);
                p.setColor(s ? cAccent : regions ? (dark ? 0x66FFFFFF : 0x55000000) : cLine);
                if (one && !s) p.setColor(dark ? 0x40FFFFFF : 0x33000000);
                c.drawRoundRect(rf, dp(6), dp(6), p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(cText);
                p.setAlpha(one && !s ? 110 : regions ? 235 : 150);
                String lb = shortLabel(id);
                p.setTextSize(dp(lb.length() > 2 ? 11 : 16));
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(lb, rf.centerX(), rf.centerY() - (p.descent() + p.ascent()) / 2f, p);
                p.setAlpha(255);
                if (regions && sel == null) {                       // 옮겨진 중심
                    float[] q = map.get(id);
                    if (q != null && (q[0] != 0 || q[1] != 0)) {
                        p.setColor(cAccent);
                        c.drawCircle((r[0] + r[2]) / 2f * ux + q[0] * ux, y0 + (r[1] + r[3]) / 2f * uy + q[1] * uy, dp(3), p);
                    }
                }
            }
            if (!regions || (sel != null && !(editing && hideDots))) {
                float rad = dp(sel != null ? 4f : 3f) / (float) Math.sqrt(z);
                boolean faint = regions && editing;                 // 편집 중: 점을 흐리게 해서 범위 색이 보이게
                for (int pass = 0; pass < 3; pass++) {              // 빈 동그라미, 초록, 빨강 순서로 (빨강이 맨 위)
                    for (Sample s : samples) {
                        boolean mine = sel == null || s.t.equals(sel);
                        boolean into = sel != null && !s.t.equals(sel) && s.p.equals(sel);   // 다른 키를 누르려다 이 키가 눌림
                        if (!mine && !into) continue;
                        if (pass == 0 && !into) continue;
                        if (pass == 1 && (into || !s.ok())) continue;
                        if (pass == 2 && (into || s.ok())) continue;
                        int i = g.indexOf(s.t);
                        if (i < 0) continue;
                        float[] r = g.rects.get(i);
                        float x = ((r[0] + r[2]) / 2f + s.dx) * ux, y = y0 + ((r[1] + r[3]) / 2f + s.dy) * uy;
                        if (into) {
                            p.setStyle(Paint.Style.STROKE);
                            p.setStrokeWidth(dp(1.5f) / z);
                            p.setColor(dark ? 0xFF6CB4FF : 0xFF2563EB);
                            p.setAlpha(faint ? 110 : 150);
                        } else {
                            // 반투명: 같은 곳에 여러 번 찍힐수록 진해져서 자주 누른(틀린) 곳이 보인다
                            p.setStyle(Paint.Style.FILL);
                            int al = faint ? 0x30 : 0x48;
                            p.setColor(((s.ok() ? al : al + 0x10) << 24) | (s.ok() ? 0x2EAD5B : 0xE5484D));
                        }
                        c.drawCircle(x, y, rad, p);
                    }
                }
            }
            c.restore();
            if (regions && zoom > 1.01f) {                          // 확대 배율 표시
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(dp(11));
                p.setTextAlign(Paint.Align.RIGHT);
                p.setColor(cText);
                p.setAlpha(170);
                c.drawText(String.format(java.util.Locale.ROOT, "%.1f배", zoom), getWidth() - dp(6), dp(14), p);
                p.setAlpha(255);
            }
            if (editing && stroking) {                              // 붓 크기 표시
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.5f));
                p.setColor(tool == 0 ? cAccent : (dark ? Color.WHITE : Color.BLACK));
                c.drawCircle(lastX, lastY, dp(brushDp), p);
            }
        }

        /** 붓 한 번: 원 안의 칸을 이 키로 칠하거나(그리기) 이 키에서 뺀다(지우개). */
        private void stamp(float sx, float sy) {
            int s = selIdx();
            if (s < 0) return;
            ensureBase();
            float x = (sx + panX()) / zoom, y = (sy + panY()) / zoom, br = dp(brushDp) / zoom;   // 화면 → 그림 좌표
            float ux = ux(), uy = uy();
            float gx = x / ux, gy = y / uy - TOP, rx = br / ux, ry = br / uy;
            int i0 = Math.max(0, (int) Math.floor((gx - rx) * GR)), i1 = Math.min(GW - 1, (int) Math.ceil((gx + rx) * GR));
            int j0 = Math.max(0, (int) Math.floor((gy - ry - GY0) * GR)), j1 = Math.min(GH - 1, (int) Math.ceil((gy + ry - GY0) * GR));
            for (int j = j0; j <= j1; j++) {
                for (int i = i0; i <= i1; i++) {
                    int c = j * GW + i;
                    float dx = (cellX(c) - gx) / rx, dy = (cellY(c) - gy) / ry;
                    if (dx * dx + dy * dy > 1) continue;
                    int owner = paint[c] >= 0 ? paint[c] : base[c];
                    if (tool == 0) {
                        if (owner == s || coreOfOther(c, s)) continue;
                        paint[c] = base[c] == s ? (byte) -1 : (byte) s;
                    } else {
                        if (owner != s) continue;
                        if (paint[c] == s) paint[c] = -1;
                        if ((paint[c] >= 0 ? paint[c] : base[c]) == s) paint[c] = (byte) baseEx[c];   // 원래 내 자리면 가장 가까운 다른 키에게
                    }
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (editing && selIdx() >= 0) {
                float x = e.getX(), y = e.getY();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        pushUndo();
                        stroking = true;
                        stamp(x, y);
                        lastX = x; lastY = y;
                        fillRaster();
                        invalidate();
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        float d = (float) Math.hypot(x - lastX, y - lastY), step = Math.max(1, dp(brushDp) / 3f);
                        int n = (int) Math.ceil(d / step);
                        for (int k = 1; k <= n; k++) stamp(lastX + (x - lastX) * k / n, lastY + (y - lastY) * k / n);
                        lastX = x; lastY = y;
                        fillRaster();
                        invalidate();
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        stroking = false;
                        savePaint(getContext());
                        invalidate();
                        if (onEdited != null) onEdited.run();
                        break;
                }
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP && pick != null) {
                Geo g = standard();
                float z = regions ? zoom : 1f;
                float ex = regions ? (e.getX() + panX()) / z : e.getX(), ey = regions ? (e.getY() + panY()) / z : e.getY();
                float gx = ex / ux(), gy = (ey - TOP * uy()) / uy();
                String hit = null;
                for (int i = 0; i < g.ids.size(); i++) {
                    float[] r = g.rects.get(i);
                    if (gx >= r[0] && gx < r[2] && gy >= r[1] && gy < r[3]) hit = g.ids.get(i);
                }
                pick.on(hit);
            }
            return true;
        }
    }
}
