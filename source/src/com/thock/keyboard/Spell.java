package com.thock.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.inputmethod.InputConnection;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 맞춤법 제안: "항상 틀린 형태" 목록 (assets/spell.tsv, 원본은 mod2/spell_list.tsv).
 *
 * 띄어쓰기·마침표·쉼표·?·!·엔터로 어절이 끝난 직후, 방금 끝난 어절을 목록에서 찾아 툴바에 제안한다.
 * 자동으로 바꾸지 않는다. 입력창의 글(getTextBeforeCursor)만 보므로 천지인·나랏글·쿼티 모두 동작한다.
 * 칩을 눌러 바꾼 직후 지우기 = 되돌리기 + 그 항목은 다시 제안하지 않음.
 */
public final class Spell {
    private Spell() {}

    // ------------------------------------------------------------------ 목록
    static final class Item {
        final String id, disp, mode;
        final String[] wrong, right;
        Item(String id, String disp, String mode, String[] wrong, String[] right) { this.id = id; this.disp = disp; this.mode = mode; this.wrong = wrong; this.right = right; }
    }

    private static final class Ent {
        final String w, r; final Item it;
        Ent(String w, String r, Item it) { this.w = w; this.r = r; this.it = it; }
    }

    static final List<Item> items = new ArrayList<>();
    private static final Map<String, Ent> exact = new HashMap<>(), whole = new HashMap<>(), end = new HashMap<>(),
            pre = new HashMap<>(), sub = new HashMap<>();
    private static int maxEnd, maxPre, maxSub;

    static boolean on = true;
    static final Set<String> off = new HashSet<>();     // 설정에서 끈 항목
    static final Set<String> block = new HashSet<>();   // 바꾼 뒤 되돌려서 꺼진 항목
    private static boolean listLoaded, prefsLoaded;

    static boolean enabled(Item it) { return !off.contains(it.id) && !block.contains(it.id); }

    static void parse(InputStream in) throws Exception {
        items.clear(); exact.clear(); whole.clear(); end.clear(); pre.clear(); sub.clear();
        maxEnd = maxPre = maxSub = 0;
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String line; (line = r.readLine()) != null; ) {
            String[] f = line.split("\t");
            if (f.length < 4) continue;
            String[] pairs = f[3].split("\\|");
            String[] w = new String[pairs.length], rt = new String[pairs.length];
            for (int i = 0; i < pairs.length; i++) { int k = pairs[i].indexOf('='); w[i] = pairs[i].substring(0, k); rt[i] = pairs[i].substring(k + 1); }
            Item it = new Item(f[0], f[1], f[2], w, rt);
            items.add(it);
            for (int i = 0; i < w.length; i++) {
                Ent e = new Ent(w[i], rt[i], it);
                int n = w[i].length();
                switch (it.mode) {
                    case "x": exact.put(w[i], e); break;
                    case "w": whole.put(w[i], e); break;
                    case "e": end.put(w[i], e); maxEnd = Math.max(maxEnd, n); break;
                    case "p": pre.put(w[i], e); maxPre = Math.max(maxPre, n); break;
                    case "s": sub.put(w[i], e); maxSub = Math.max(maxSub, n); break;
                }
            }
        }
        listLoaded = true;
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("thock_ext", Context.MODE_PRIVATE); }

    static void load(Context c) {
        if (c == null) return;
        if (!prefsLoaded) {
            prefsLoaded = true;
            try {
                SharedPreferences p = sp(c);
                on = p.getBoolean("spell_on", true);
                readSet(p.getString("spell_off", null), off);
                readSet(p.getString("spell_block", null), block);
            } catch (Throwable t) { android.util.Log.w("ThockSpell", "prefs", t); }
        }
        if (!listLoaded) {
            try (InputStream in = c.getAssets().open("spell.tsv")) { parse(in); }
            catch (Throwable t) { listLoaded = true; android.util.Log.w("ThockSpell", "list", t); }
        }
    }

    private static void readSet(String s, Set<String> into) throws Exception {
        if (s == null) return;
        JSONArray a = new JSONArray(s);
        for (int i = 0; i < a.length(); i++) into.add(a.getString(i));
    }

    static void save(Context c) {
        try {
            JSONArray o = new JSONArray(), b = new JSONArray();
            for (String s : off) o.put(s);
            for (String s : block) b.put(s);
            sp(c).edit().putBoolean("spell_on", on).putString("spell_off", o.toString()).putString("spell_block", b.toString()).apply();
        } catch (Throwable t) { android.util.Log.w("ThockSpell", "save", t); }
    }

    // ------------------------------------------------------------------ 검사 (순수 로직, JVM 테스트 대상)
    /** 'w' 항목 뒤에 붙어도 되는 조사·어미 (긴 것부터) */
    private static final String[] JOSA = {"이라도", "이잖아", "이에요", "이라고", "에서", "에게", "까지", "부터", "보다", "처럼", "으로", "이나", "이랑", "하고",
            "이야", "예요", "이다", "이지", "이고", "라도", "이면", "인데", "인가", "잖아", "라고",
            "은", "는", "이", "가", "을", "를", "에", "의", "도", "로", "와", "과", "만", "나", "랑", "야", "다", "지", "고", "면", "요"};

    static boolean syllable(char c) { return c >= 0xAC00 && c <= 0xD7A3; }

    /** 고친 결과. ids = 쓰인 항목 */
    static final class Fix {
        String oldWord, newWord;
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
    }

    /** 한 어절(한글 음절만)을 고친다. 고칠 것이 없으면 null. */
    static Fix fixWord(String word) {
        if (word == null || word.isEmpty()) return null;
        Fix f = new Fix();
        String cur = word;
        for (int pass = 0; pass < 4; pass++) {
            String next = onePass(cur, f.ids);
            if (next == null) break;
            cur = next;
        }
        if (cur.equals(word)) return null;
        f.oldWord = word;
        f.newWord = cur;
        return f;
    }

    /** 띄어 쓴 조각마다 보고, 처음 고친 한 곳만 바꿔서 돌려준다. */
    private static String onePass(String s, Set<String> ids) {
        int st = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == ' ') {
                if (i > st) {
                    String r = fixSeg(s.substring(st, i), ids);
                    if (r != null) return s.substring(0, st) + r + s.substring(i);
                }
                st = i + 1;
            }
        }
        return null;
    }

    private static Ent ok(Ent e) { return e != null && enabled(e.it) ? e : null; }

    private static String fixSeg(String seg, Set<String> ids) {
        int n = seg.length();
        Ent e = ok(exact.get(seg));
        if (e == null) e = ok(whole.get(seg));
        if (e != null) { ids.add(e.it.id); return e.r; }
        for (String j : JOSA) {
            if (n > j.length() && seg.endsWith(j)) {
                e = ok(whole.get(seg.substring(0, n - j.length())));
                if (e != null) { ids.add(e.it.id); return e.r + j; }
            }
        }
        for (int L = Math.min(maxEnd, n); L >= 1; L--) {
            e = ok(end.get(seg.substring(n - L)));
            if (e != null) { ids.add(e.it.id); return seg.substring(0, n - L) + e.r; }
        }
        for (int L = Math.min(maxPre, n); L >= 1; L--) {
            e = ok(pre.get(seg.substring(0, L)));
            if (e != null) { ids.add(e.it.id); return e.r + seg.substring(L); }
        }
        for (int i = 0; i < n; i++) {
            for (int L = Math.min(maxSub, n - i); L >= 1; L--) {
                String t = seg.substring(i, i + L);
                e = ok(sub.get(t));
                if (e != null) { ids.add(e.it.id); return seg.substring(0, i) + e.r + seg.substring(i + L); }
            }
        }
        return null;
    }

    /** 어절 앞뒤에서 떼어 놓고 보는 글자: 문장부호·기호·낱자(ㅋㅋ ㅠㅠ). 영문·숫자는 아님 */
    private static boolean edge(char c) { return (c >= 0x3131 && c <= 0x318E) || !(syllable(c) || Character.isLetterOrDigit(c)); }

    static boolean isBoundary(char c) { return c == ' ' || c == '\n' || c == '.' || c == ',' || c == '?' || c == '!'; }

    private static boolean isSep(char c) { return Character.isWhitespace(c) || c == '.' || c == ',' || c == '?' || c == '!'; }

    static final int READ = 48;

    /** 제안 */
    static final class Sugg {
        String oldText, newText, label;   // oldText/newText = 어절 + 경계 문자
        final List<String> ids = new ArrayList<>();
    }

    /**
     * 커서 앞 글(경계 문자로 끝남)에서 방금 끝난 어절을 본다.
     * @param before getTextBeforeCursor(READ) 결과
     */
    static Sugg check(CharSequence before) {
        if (before == null) return null;
        int len = before.length();
        if (len < 2) return null;
        char bnd = before.charAt(len - 1);
        if (!isBoundary(bnd)) return null;
        int e = len - 1, s = e;
        while (s > 0 && !isSep(before.charAt(s - 1))) s--;
        if (s == 0 && len >= READ) return null;            // 어절 앞부분이 잘렸을 수 있다
        if (e - s < 1 || e - s > 30) return null;
        String run = before.subSequence(s, e).toString();
        int a = 0, b = run.length();
        while (a < b && edge(run.charAt(a))) a++;         // 앞뒤의 따옴표·괄호·ㅋㅋ 등은 그대로 둔다
        while (b > a && edge(run.charAt(b - 1))) b--;
        if (a >= b) return null;
        for (int i = a; i < b; i++) if (!syllable(run.charAt(i))) return null;
        Fix f = fixWord(run.substring(a, b));
        if (f == null) return null;
        Sugg g = new Sugg();
        g.oldText = run + bnd;
        g.newText = run.substring(0, a) + f.newWord + run.substring(b) + bnd;
        g.label = f.oldWord + " → " + f.newWord;
        g.ids.addAll(f.ids);
        return g;
    }

    // ------------------------------------------------------------------ 키보드 연결
    private static Sugg pending;

    private static final class Done { String oldText, newText; List<String> ids; long time; boolean armed = true; }
    private static Done done;

    /** 키를 누를 때마다(경계 키 포함, 처리 전): 떠 있는 제안은 지운다. 되돌리기는 경계 문자 하나까지만 봐준다. */
    static void beforeKey(Typo.Host h, boolean boundary) {
        if (pending != null) { pending = null; h.show(null); }
        if (done != null && done.armed && boundary) done.armed = false;
        else done = null;
    }

    static void reset(Typo.Host h) {
        done = null;
        if (pending != null) { pending = null; if (h != null) h.show(null); }
    }

    static void clearDone() { done = null; }

    /** 경계 키가 입력된 뒤. skip = 고정 바꾸기·오타 교정이 방금 글을 바꿨음 */
    static void afterBoundary(Typo.Host h, Context c, boolean skip) {
        try {
            pending = null;
            if (!on || skip || !Typo.usable(h)) { h.show(null); return; }
            load(c);
            InputConnection ic = h.ic();
            if (ic == null) return;
            Sugg g = check(ic.getTextBeforeCursor(READ, 0));
            pending = g;
            h.show(g == null ? null : g.label);
        } catch (Throwable t) { android.util.Log.w("ThockSpell", "check", t); }
    }

    static boolean hasPending() { return pending != null; }

    private static long now() { return System.nanoTime() / 1000000L; }

    /** 툴바 칩을 눌렀을 때 */
    static void apply(Typo.Host h, Context c) {
        Sugg g = pending;
        pending = null;
        h.show(null);
        try {
            InputConnection ic = h.ic();
            if (g == null || ic == null || !Typo.usable(h)) return;
            CharSequence before = ic.getTextBeforeCursor(g.oldText.length(), 0);
            if (before == null || !g.oldText.contentEquals(before)) return;   // 그 사이에 글이 바뀌었다
            h.finishComposing();
            ic.beginBatchEdit();
            ic.deleteSurroundingText(g.oldText.length(), 0);
            ic.commitText(g.newText, 1);
            ic.endBatchEdit();
            Typo.reset();
            Done d = new Done();
            d.oldText = g.oldText; d.newText = g.newText; d.ids = g.ids; d.time = now();
            done = d;
        } catch (Throwable t) { android.util.Log.w("ThockSpell", "apply", t); }
    }

    /** 바꾼 직후의 지우기 = 되돌리기. 되돌렸으면 true (지우기는 하지 않는다). */
    static boolean undo(Typo.Host h, Context c) {
        Done d = done;
        done = null;
        try {
            if (d == null || now() - d.time > 15000) return false;
            InputConnection ic = h.ic();
            if (ic == null) return false;
            CharSequence cs = ic.getTextBeforeCursor(d.newText.length() + 1, 0);
            if (cs == null) return false;
            String b = cs.toString();
            int extra = b.endsWith(d.newText) ? 0
                    : (b.length() > d.newText.length() && isBoundary(b.charAt(b.length() - 1)) && b.startsWith(d.newText, b.length() - 1 - d.newText.length()) ? 1 : -1);
            if (extra < 0) return false;
            h.finishComposing();
            ic.beginBatchEdit();
            ic.deleteSurroundingText(d.newText.length() + extra, 0);
            ic.commitText(d.oldText + (extra == 1 ? b.substring(b.length() - 1) : ""), 1);
            ic.endBatchEdit();
            Typo.reset();
            block.addAll(d.ids);                           // 다시는 제안하지 않는다
            if (c != null) save(c);
            return true;
        } catch (Throwable t) { android.util.Log.w("ThockSpell", "undo", t); return false; }
    }
}
