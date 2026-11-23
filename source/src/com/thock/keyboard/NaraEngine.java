package com.thock.keyboard;

import java.util.HashMap;
import java.util.Map;

/** 나랏글 Hangul automaton (Samsung 나랏글 behaviour, no Android deps). */
public final class NaraEngine {
    static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    static final String JONG = "\0ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";

    // keys
    public static final int AE = 1, OU = 2, I = 3, EU = 4, STROKE = 5, DOUBLE = 6, CONS = 10;

    private static final Map<String, Character> VCOMB = new HashMap<>();   // jung + v
    private static final Map<String, Character> COMP = new HashMap<>();    // jong + c
    private static final Map<Character, Character> STROKE_NEXT = new HashMap<>();
    private static final Map<Character, Character> DOUBLE_NEXT = new HashMap<>();
    private static final Map<Character, Character> VSPLIT = new HashMap<>(); // backspace on compound vowel
    static {
        String[][] v = {{"ㅗㅏ", "ㅘ"}, {"ㅗㅐ", "ㅙ"}, {"ㅗㅣ", "ㅚ"}, {"ㅜㅓ", "ㅝ"}, {"ㅜㅔ", "ㅞ"}, {"ㅜㅣ", "ㅟ"},
                {"ㅡㅣ", "ㅢ"}, {"ㅏㅣ", "ㅐ"}, {"ㅓㅣ", "ㅔ"}, {"ㅑㅣ", "ㅒ"}, {"ㅕㅣ", "ㅖ"}, {"ㅘㅣ", "ㅙ"}, {"ㅝㅣ", "ㅞ"}};
        for (String[] p : v) { VCOMB.put(p[0], p[1].charAt(0)); VSPLIT.put(p[1].charAt(0), p[0].charAt(0)); }
        String[][] c = {{"ㄱㅅ", "ㄳ"}, {"ㄴㅈ", "ㄵ"}, {"ㄴㅎ", "ㄶ"}, {"ㄹㄱ", "ㄺ"}, {"ㄹㅁ", "ㄻ"}, {"ㄹㅂ", "ㄼ"},
                {"ㄹㅅ", "ㄽ"}, {"ㄹㅌ", "ㄾ"}, {"ㄹㅍ", "ㄿ"}, {"ㄹㅎ", "ㅀ"}, {"ㅂㅅ", "ㅄ"}};
        for (String[] p : c) COMP.put(p[0], p[1].charAt(0));
        String[] cyc = {"ㄱㅋ", "ㄴㄷㅌ", "ㅁㅂㅍ", "ㅅㅈㅊ", "ㅇㅎ"};
        for (String s : cyc) for (int i = 0; i < s.length(); i++) STROKE_NEXT.put(s.charAt(i), s.charAt((i + 1) % s.length()));
        String[][] vs = {{"ㅏ", "ㅑ"}, {"ㅓ", "ㅕ"}, {"ㅗ", "ㅛ"}, {"ㅜ", "ㅠ"}};
        for (String[] p : vs) { STROKE_NEXT.put(p[0].charAt(0), p[1].charAt(0)); STROKE_NEXT.put(p[1].charAt(0), p[0].charAt(0)); }
        String[][] d = {{"ㄱ", "ㄲ"}, {"ㄷ", "ㄸ"}, {"ㅂ", "ㅃ"}, {"ㅅ", "ㅆ"}, {"ㅈ", "ㅉ"}};
        for (String[] p : d) { DOUBLE_NEXT.put(p[0].charAt(0), p[1].charAt(0)); DOUBLE_NEXT.put(p[1].charAt(0), p[0].charAt(0)); }
    }

    // ---- state
    private String prefix = "";
    private char cho, jung, jong;
    private int lastKey = -1;
    private char lastJamo;          // jamo produced by the last key (what 획추가/쌍자음 modify)
    private Object[] snap;          // state before that jamo went in

    private Object[] save() { return new Object[]{prefix, cho, jung, jong}; }
    private void restore(Object[] s) { prefix = (String) s[0]; cho = (Character) s[1]; jung = (Character) s[2]; jong = (Character) s[3]; }

    /** Reopen one already committed Hangul syllable. */
    public boolean reopen(char syllable) {
        if (syllable < 0xAC00 || syllable > 0xD7A3 || isComposing()) return false;
        int n = syllable - 0xAC00;
        cho = CHO.charAt(n / 588); jung = JUNG.charAt((n / 28) % 21);
        int t = n % 28; jong = t == 0 ? 0 : JONG.charAt(t);
        lastKey = -1; lastJamo = 0; snap = null; return true;
    }

    public boolean isComposing() { return !prefix.isEmpty() || cho != 0 || jung != 0; }
    public void reset() { prefix = ""; cho = jung = jong = 0; lastKey = -1; lastJamo = 0; snap = null; }
    public String composing() { return prefix + cur(); }
    public String finish() { String t = composing(); reset(); return t; }

    /** Consonant key (ㄱ ㄴ ㄹ ㅁ ㅅ ㅇ). Returns text to commit before composing(). */
    public String consonant(char c) {
        String out = prefix; prefix = "";
        snap = save();
        insertConsonant(c);
        lastJamo = c; lastKey = CONS;
        return out;
    }

    /** AE, OU, I, EU, STROKE, DOUBLE */
    public String key(int k) {
        switch (k) {
            case AE: case OU: {
                char a = k == AE ? 'ㅏ' : 'ㅗ', b = k == AE ? 'ㅓ' : 'ㅜ';
                if (lastKey == k && snap != null && (lastJamo == a || lastJamo == b)) {   // toggle ㅏ↔ㅓ / ㅗ↔ㅜ
                    char t = lastJamo == a ? b : a;
                    if (!replaceLast(t, true)) return "";
                    lastKey = k;
                    return "";
                }
                String out = prefix; prefix = "";
                snap = save();
                char v = (k == AE && jong == 0 && jung == 'ㅜ') ? 'ㅓ' : a;              // ㅜ + ㅏㅓ = ㅝ
                insertVowel(v);
                lastJamo = v; lastKey = k;
                return out;
            }
            case I: case EU: {
                String out = prefix; prefix = "";
                snap = save();
                char v = k == I ? 'ㅣ' : 'ㅡ';
                insertVowel(v);
                lastJamo = v; lastKey = k;
                return out;
            }
            case STROKE: case DOUBLE: {
                if (snap == null || lastJamo == 0) return "";
                Character n = (k == STROKE ? STROKE_NEXT : DOUBLE_NEXT).get(lastJamo);
                if (n == null) return "";
                if (k == DOUBLE && JUNG.indexOf(lastJamo) >= 0) return "";
                replaceLast(n, JUNG.indexOf(n) >= 0);
                lastKey = k;
                return "";
            }
        }
        return "";
    }

    /** Re-insert the last jamo as {@code t}; a vowel that can't combine leaves things unchanged. */
    private boolean replaceLast(char t, boolean vowel) {
        Object[] now = save();
        restore(snap);
        if (vowel) {
            if (jong == 0 && jung != 0 && !VCOMB.containsKey("" + jung + t)) { restore(now); return false; }
            insertVowel(t);
        } else {
            insertConsonant(t);
        }
        lastJamo = t;
        return true;
    }

    public boolean backspace() {
        lastKey = -1; lastJamo = 0; snap = null;
        if (jong != 0) {
            char[] d = splitJong(jong);
            jong = d[1] != 0 ? d[0] : 0;
        } else if (jung != 0) {
            Character base = VSPLIT.get(jung);
            jung = base != null ? base : 0;
        } else if (cho != 0) {
            cho = 0;
        } else if (!prefix.isEmpty()) {
            prefix = prefix.substring(0, prefix.length() - 1);
        } else return false;
        return true;
    }

    // ---- internals
    private String cur() {
        if (cho != 0 && jung != 0) {
            int ci = CHO.indexOf(cho), ji = JUNG.indexOf(jung), ti = jong == 0 ? 0 : JONG.indexOf(jong);
            if (ci >= 0 && ji >= 0 && ti >= 0) return String.valueOf((char) (0xAC00 + (ci * 21 + ji) * 28 + ti));
        }
        StringBuilder sb = new StringBuilder();
        if (cho != 0) sb.append(cho);
        if (jung != 0) sb.append(jung);
        if (jong != 0) sb.append(jong);
        return sb.toString();
    }

    private void finishCur() { prefix += cur(); cho = jung = jong = 0; }

    private void insertConsonant(char c) {
        if (cho == 0 && jung == 0) cho = c;
        else if (cho == 0 || jung == 0) { finishCur(); cho = c; }
        else if (jong == 0) { if (JONG.indexOf(c) > 0) jong = c; else { finishCur(); cho = c; } }
        else {
            Character comp = splitJong(jong)[1] == 0 ? COMP.get("" + jong + c) : null;
            if (comp != null) jong = comp; else { finishCur(); cho = c; }
        }
    }

    private void insertVowel(char v) {
        if (jong != 0) {
            char[] d = splitJong(jong);
            char moved;
            if (d[1] != 0) { jong = d[0]; moved = d[1]; } else { moved = jong; jong = 0; }
            finishCur();
            cho = moved; jung = v;
        } else if (jung == 0) {
            jung = v;
        } else {
            Character c = VCOMB.get("" + jung + v);
            if (c != null) jung = c; else { finishCur(); jung = v; }
        }
    }

    private static char[] splitJong(char j) {
        for (Map.Entry<String, Character> e : COMP.entrySet())
            if (e.getValue() == j) return new char[]{e.getKey().charAt(0), e.getKey().charAt(1)};
        return new char[]{j, 0};
    }
}
