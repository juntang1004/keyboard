package com.thock.keyboard;

import java.util.HashMap;
import java.util.Map;

/** 천지인 Hangul automaton (no Android deps). */
public final class CjEngine {
    public static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    public static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    public static final String JONG = "\0ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";

    /** consonant keys: ㄱㅋ ㄴㄹ ㄷㅌ ㅂㅍ ㅅㅎ ㅈㅊ ㅇㅁ */
    public static final String[] CYCLES = {"ㄱㅋㄲ", "ㄴㄹ", "ㄷㅌㄸ", "ㅂㅍㅃ", "ㅅㅎㅆ", "ㅈㅊㅉ", "ㅇㅁ"};
    public static final char I = 'ㅣ', A = 'ㆍ', EU = 'ㅡ';
    private static final char SSANG_A = 'ᆢ'; // ᆢ

    private static final Map<String, Character> V = new HashMap<>();
    private static final Map<String, Character> COMP = new HashMap<>();
    static {
        String[][] v = {
                {"ㅣ", "ㅣ"}, {"ㅡ", "ㅡ"},
                {"ㅣㆍ", "ㅏ"}, {"ㅣㆍㆍ", "ㅑ"}, {"ㆍㅣ", "ㅓ"}, {"ㆍㆍㅣ", "ㅕ"},
                {"ㆍㅡ", "ㅗ"}, {"ㆍㆍㅡ", "ㅛ"}, {"ㅡㆍ", "ㅜ"}, {"ㅡㆍㆍ", "ㅠ"}, {"ㅡㅣ", "ㅢ"},
                {"ㅣㆍㅣ", "ㅐ"}, {"ㅣㆍㆍㅣ", "ㅒ"}, {"ㆍㅣㅣ", "ㅔ"}, {"ㆍㆍㅣㅣ", "ㅖ"},
                {"ㆍㅡㅣ", "ㅚ"}, {"ㆍㅡㅣㆍ", "ㅘ"}, {"ㆍㅡㅣㆍㅣ", "ㅙ"},
                {"ㅡㆍㅣ", "ㅟ"}, {"ㅡㆍㆍㅣ", "ㅝ"}, {"ㅡㆍㆍㅣㅣ", "ㅞ"},
        };
        for (String[] p : v) V.put(p[0], p[1].charAt(0));
        String[][] c = {{"ㄱㅅ", "ㄳ"}, {"ㄴㅈ", "ㄵ"}, {"ㄴㅎ", "ㄶ"}, {"ㄹㄱ", "ㄺ"}, {"ㄹㅁ", "ㄻ"}, {"ㄹㅂ", "ㄼ"},
                {"ㄹㅅ", "ㄽ"}, {"ㄹㅌ", "ㄾ"}, {"ㄹㅍ", "ㄿ"}, {"ㄹㅎ", "ㅀ"}, {"ㅂㅅ", "ㅄ"}};
        for (String[] p : c) COMP.put(p[0], p[1].charAt(0));
    }

    // ---- state
    private String prefix = "";
    private char cho = 0, jong = 0;
    private String vs = "";
    private int lastKey = -1;      // 0..6 consonant keys, 10+ vowels
    private int cyclePos = 0;
    private Object[] snap;

    private Object[] save() { return new Object[]{prefix, cho, vs, jong}; }
    private void restore(Object[] s) { prefix = (String) s[0]; cho = (Character) s[1]; vs = (String) s[2]; jong = (Character) s[3]; }

    /** Reopen one already committed Hangul syllable. */
    public boolean reopen(char syllable) {
        if (syllable < 0xAC00 || syllable > 0xD7A3 || isComposing()) return false;
        int n = syllable - 0xAC00;
        char v = JUNG.charAt((n / 28) % 21);
        String strokes = null;
        for (Map.Entry<String, Character> e : V.entrySet()) if (e.getValue() == v) { strokes = e.getKey(); break; }
        if (strokes == null) return false;
        cho = CHO.charAt(n / 588); vs = strokes;
        int t = n % 28; jong = t == 0 ? 0 : JONG.charAt(t);
        lastKey = -1; snap = null; return true;
    }

    public boolean isComposing() { return !prefix.isEmpty() || cho != 0 || !vs.isEmpty(); }
    public boolean lastWasConsonant() { return lastKey >= 0 && lastKey < 10; }
    public void breakCycle() { lastKey = -1; }
    public void reset() { prefix = ""; cho = 0; jong = 0; vs = ""; lastKey = -1; }

    public String composing() { return prefix + cur(); }

    /** Commits everything; returns the text to commit. */
    public String finish() { String t = composing(); reset(); return t; }

    /** Returns text that must be committed before showing composing(). */
    public String consonant(int k) {
        String out = "";
        if (lastKey == k && snap != null) {
            restore(snap);
            cyclePos++;
        } else {
            out = prefix; prefix = "";
            snap = save();
            cyclePos = 0;
        }
        String cyc = CYCLES[k];
        insertConsonant(cyc.charAt(cyclePos % cyc.length()));
        lastKey = k;
        return out;
    }

    public String vowel(char stroke) {
        String out = prefix; prefix = "";
        insertVowel(stroke);
        lastKey = 10 + stroke;
        return out;
    }

    /** @return false if nothing was composing (caller should do a normal delete). */
    public boolean backspace() {
        lastKey = -1;
        if (jong != 0) {
            char[] d = splitJong(jong);
            jong = d[1] != 0 ? d[0] : 0;
        } else if (!vs.isEmpty()) {
            vs = vs.substring(0, vs.length() - 1);
        } else if (cho != 0) {
            cho = 0;
        } else if (!prefix.isEmpty()) {
            prefix = prefix.substring(0, prefix.length() - 1);
        } else {
            return false;
        }
        return true;
    }

    // ---- internals
    private boolean vowelComplete() { return V.containsKey(vs); }

    private static boolean validVs(String s) { return V.containsKey(s) || s.equals("ㆍ") || s.equals("ㆍㆍ"); }

    private String vowelDisplay() {
        if (vs.isEmpty()) return "";
        Character c = V.get(vs);
        if (c != null) return String.valueOf(c);
        if (vs.equals("ㆍ")) return "ㆍ";
        if (vs.equals("ㆍㆍ")) return String.valueOf(SSANG_A);
        return vs;
    }

    private String cur() {
        if (cho != 0 && vowelComplete()) {
            int ci = CHO.indexOf(cho), ji = JUNG.indexOf(V.get(vs)), ti = jong == 0 ? 0 : JONG.indexOf(jong);
            if (ci >= 0 && ji >= 0 && ti >= 0) return String.valueOf((char) (0xAC00 + (ci * 21 + ji) * 28 + ti));
        }
        StringBuilder sb = new StringBuilder();
        if (cho != 0) sb.append(cho);
        sb.append(vowelDisplay());
        if (jong != 0) sb.append(jong);
        return sb.toString();
    }

    private void finishCur() { prefix += cur(); cho = 0; jong = 0; vs = ""; }

    private void insertConsonant(char c) {
        if (cho == 0 && vs.isEmpty()) {
            cho = c;
        } else if (cho == 0 || !vowelComplete()) {
            finishCur(); cho = c;
        } else if (jong == 0) {
            if (JONG.indexOf(c) > 0) jong = c; else { finishCur(); cho = c; }
        } else {
            Character comp = splitJong(jong)[1] == 0 ? COMP.get("" + jong + c) : null;
            if (comp != null) jong = comp; else { finishCur(); cho = c; }
        }
    }

    private void insertVowel(char s) {
        if (jong != 0) {
            char[] d = splitJong(jong);
            char moved;
            if (d[1] != 0) { jong = d[0]; moved = d[1]; } else { moved = jong; jong = 0; }
            finishCur();
            cho = moved; vs = String.valueOf(s);
        } else if (vs.isEmpty()) {
            vs = String.valueOf(s);
        } else {
            String nv = vs + s;
            if (validVs(nv)) vs = nv; else { finishCur(); vs = String.valueOf(s); }
        }
    }

    private static char[] splitJong(char j) {
        for (Map.Entry<String, Character> e : COMP.entrySet())
            if (e.getValue() == j) return new char[]{e.getKey().charAt(0), e.getKey().charAt(1)};
        return new char[]{j, 0};
    }
}
