package com.thock.keyboard;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** 테스트용: Spell 의 패키지 내부 함수를 열어 준다 (dex에는 안 들어감). */
public final class SpellAccess {
    public static void parse(InputStream in) throws Exception { Spell.parse(in); }

    public static int itemCount() { return Spell.items.size(); }

    public static int formCount() { int n = 0; for (Spell.Item it : Spell.items) n += it.wrong.length; return n; }

    public static List<String[]> pairs() {
        List<String[]> l = new ArrayList<>();
        for (Spell.Item it : Spell.items) for (int i = 0; i < it.wrong.length; i++) l.add(new String[]{it.wrong[i], it.right[i], it.id});
        return l;
    }

    public static String fixWord(String w) {
        // 바른 형태에 띄어쓰기가 있으면 조각마다 본다 (fixWord 가 알아서 조각으로 나눈다)
        Spell.Fix f = Spell.fixWord(w);
        return f == null ? null : f.newWord;
    }

    /** {옛 글, 새 글, 첫 항목 id} */
    public static String[] check(String text) {
        Spell.Sugg g = Spell.check(text);
        return g == null ? null : new String[]{g.oldText, g.newText, g.ids.get(0)};
    }

    // ---------------------------------------------------------------- 키보드 흐름 (가짜 입력창)
    public static final StringBuilder text = new StringBuilder();
    public static String shown;
    public static int inputType = InputType.TYPE_CLASS_TEXT;

    private static final InputConnection ic = (InputConnection) Proxy.newProxyInstance(SpellAccess.class.getClassLoader(), new Class<?>[]{InputConnection.class}, (p, m, a) -> {
        switch (m.getName()) {
            case "getTextBeforeCursor": { int n = (Integer) a[0]; return text.substring(Math.max(0, text.length() - n)); }
            case "deleteSurroundingText": { int n = (Integer) a[0]; text.setLength(Math.max(0, text.length() - n)); return true; }
            case "commitText": text.append((CharSequence) a[0]); return true;
            default: return m.getReturnType() == boolean.class ? Boolean.TRUE : null;
        }
    });

    private static final Typo.Host host = new Typo.Host() {
        public InputConnection ic() { return ic; }
        public EditorInfo editor() { EditorInfo e = new EditorInfo(); e.inputType = inputType; return e; }
        public void finishComposing() {}
        public void show(String label) { shown = label; }
    };

    /** 키 하나 입력: 경계 문자면 맞춤법 검사까지 (Ext 의 순서와 같게) */
    public static void type(String s, boolean skip) {
        for (char c : s.toCharArray()) {
            boolean bd = Spell.isBoundary(c);
            Spell.beforeKey(host, bd);
            text.append(c);
            if (bd) Spell.afterBoundary(host, null, skip);
        }
    }

    public static void tap() { Spell.apply(host, null); }

    public static boolean backspace() {
        if (Spell.undo(host, null)) return true;
        Spell.beforeKey(host, false);
        if (text.length() > 0) text.setLength(text.length() - 1);
        return false;
    }

    public static boolean blocked(String id) { return Spell.block.contains(id); }

    public static void clearBlock() { Spell.block.clear(); Spell.off.clear(); }

    public static void setOff(String id) { Spell.off.add(id); }

    public static void reset() { text.setLength(0); shown = null; Spell.reset(host); inputType = InputType.TYPE_CLASS_TEXT; }
}
