package com.thock.keyboard;

/** Timed long-press deletion; word boundaries are whitespace. */
public final class FastDelete {
    public static int stage(long heldMillis) {
        return heldMillis < 0 ? 0 : (int) Math.min(6, heldMillis / 2000);
    }
    /** Same three speeds for individual characters and whole words. */
    public static long delayMillis(int stage) {
        switch (Math.max(0, stage) % 3) {
            case 0: return 120;
            case 1: return 80;
            default: return 45;
        }
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
