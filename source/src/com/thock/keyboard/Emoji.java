package com.thock.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 이모지 목록 · 최근 사용 · 한국어 검색.
 * 목록은 assets/emoji.tsv (mod2/emoji_build.py 가 Unicode emoji-test.txt + CLDR 한국어 이름으로 만듦).
 * 폰 글꼴에 없는 이모지(네모로 보이는 것)는 키보드에서 걸러낸다 (Paint.hasGlyph).
 */
public final class Emoji {
    private Emoji() {}

    /** 분류: 0 최근, 1~9 = emoji.tsv 의 분류번호 */
    static final String[] CAT_NAMES = {"최근 사용", "스마일", "사람", "동물·자연", "음식", "여행·장소", "활동", "사물", "기호", "깃발"};
    static final String[] CAT_ICONS = {null, "😀", "👋", "🐻", "🍔", "🚗", "⚽", "💡", "🔣", "🏳️"};
    static final int CATS = 10, MAX_RECENT = 32;

    static final class E {
        final int cat; final String s; final String[] names; final String[] jamo;
        E(int cat, String s, String[] names) {
            this.cat = cat; this.s = s; this.names = names;
            jamo = new String[names.length];
            for (int i = 0; i < names.length; i++) jamo[i] = jamoOf(names[i]);
        }
    }

    static final List<E> all = new ArrayList<>();
    static final List<String> recent = new ArrayList<>();
    private static volatile boolean loaded;

    static boolean ready() { return loaded; }

    /** emoji.tsv 읽기 (keep = 걸러낼지 묻는 함수, null 이면 모두) */
    static List<E> parse(InputStream in, Keep keep) throws Exception {
        List<E> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String line; (line = r.readLine()) != null; ) {
            String[] f = line.split("\t", -1);
            if (f.length < 3 || f[1].isEmpty()) continue;
            int cat;
            try { cat = Integer.parseInt(f[0]); } catch (NumberFormatException e) { continue; }
            if (cat < 1 || cat >= CATS) continue;
            if (keep != null && !keep.ok(f[1])) continue;
            out.add(new E(cat, f[1], f[2].isEmpty() ? new String[0] : f[2].split("\\|")));
        }
        return out;
    }

    interface Keep { boolean ok(String emoji); }

    /** 키보드가 켜질 때 한 번 (다른 스레드에서 불러도 됨) */
    static synchronized void load(Context c) {
        if (loaded || c == null) return;
        try {
            final android.graphics.Paint p = new android.graphics.Paint();
            List<E> l = parse(c.getAssets().open("emoji.tsv"), s -> { try { return p.hasGlyph(s); } catch (Throwable t) { return true; } });
            all.clear();
            all.addAll(l);
            loadRecent(c);
            loaded = true;
        } catch (Throwable t) { android.util.Log.w("ThockEmoji", "load", t); }
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("thock_ext", Context.MODE_PRIVATE); }

    static void loadRecent(Context c) {
        recent.clear();
        String s = sp(c).getString("emoji_recent", "");
        for (String e : s.split("\n")) if (!e.isEmpty()) recent.add(e);
    }

    /** 이모지를 썼을 때: 최근 사용 맨 앞으로 */
    static void use(Context c, String e) {
        recent.remove(e);
        recent.add(0, e);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        if (c != null) {
            StringBuilder b = new StringBuilder();
            for (String r : recent) { if (b.length() > 0) b.append('\n'); b.append(r); }
            sp(c).edit().putString("emoji_recent", b.toString()).apply();
        }
    }

    static List<String> ofCat(int cat) {
        List<String> out = new ArrayList<>();
        if (cat == 0) { out.addAll(recent); return out; }
        for (E e : all) if (e.cat == cat) out.add(e.s);
        return out;
    }

    /**
     * 한국어로 찾기. 치는 중인 글자도 맞게 자모 단위로 비교한다 ("스마이" → "스마일").
     * 순서: 이름이 검색어로 시작 → 검색어 중 하나가 검색어로 시작 → 어디든 들어 있음.
     */
    static List<String> search(String q, int max) {
        List<String> out = new ArrayList<>();
        String jq = jamoOf(q.trim());
        if (jq.isEmpty()) return out;
        List<String> b1 = new ArrayList<>(), b2 = new ArrayList<>();
        for (E e : all) {
            int rank = 9;
            for (int i = 0; i < e.jamo.length && rank > 0; i++) {
                String j = e.jamo[i];
                if (j.startsWith(jq)) rank = Math.min(rank, i == 0 ? 0 : 1);
                else if (j.contains(jq)) rank = Math.min(rank, 2);
            }
            if (rank == 0) out.add(e.s); else if (rank == 1) b1.add(e.s); else if (rank == 2) b2.add(e.s);
        }
        out.addAll(b1);
        out.addAll(b2);
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }

    /** 글 → 자모 (겹모음·겹받침은 나눠서), 띄어쓰기는 지움 */
    static String jamoOf(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : FixLearn.keys(s)) if (c != ' ') b.append(c);
        return b.toString();
    }
}
