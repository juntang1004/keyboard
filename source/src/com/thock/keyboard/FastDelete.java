package com.thock.keyboard;

/** Timed long-press deletion; word boundaries are whitespace. */
public final class FastDelete {
    public static int stage(long heldMillis) {
        return heldMillis < 1000 ? 0 : heldMillis < 3000 ? 1 : heldMillis < 5000 ? 2 : 3;
    }
    public static int precedingWordLength(CharSequence text) {
        if (text == null) return 0;
        int end = text.length(), i = end;
        while (i > 0) {
            int cp = Character.codePointBefore(text, i);
            if (!Character.isWhitespace(cp)) break;
            i -= Character.charCount(cp);
        }
        while (i > 0) {
            int cp = Character.codePointBefore(text, i);
            if (Character.isWhitespace(cp)) break;
            i -= Character.charCount(cp);
        }
        return end - i;
    }
}
