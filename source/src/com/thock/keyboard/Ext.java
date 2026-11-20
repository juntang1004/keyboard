package com.thock.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Patch helper injected into 도록키보드.
 *  1) Multi-touch aware key dispatch (fixes dropped keys when typing fast / rolling over).
 *  2) Toolbar: edit panel (arrows, select, copy/cut/paste, home/end), clipboard history, saved phrases.
 */
public final class Ext {
    private Ext() {}

    // ---------------------------------------------------------------- state
    private static InputMethodService svc;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static View toolbar;
    private static View editPanel;
    private static View phrasePanel;
    private static TextView selectBtn;
    private static boolean selectMode;
    private static int pendingDeleteIdx = -1;

    private static int cBg, cKey, cSpecial, cText, cPressed, cAccent, cHint;

    // ---------------------------------------------------------------- reflection
    private static Field fKeyViews, fPrefs;
    private static Method mGetFrame, mFinishComposing, mTypeText, mOpenClipboard, mDoBackspace, mGetHaptic;

    private static Field field(Class<?> c, String n) {
        try { Field f = c.getDeclaredField(n); f.setAccessible(true); return f; } catch (Throwable t) { return null; }
    }

    private static Method method(Class<?> c, String n, Class<?>... args) {
        try { Method m = c.getDeclaredMethod(n, args); m.setAccessible(true); return m; } catch (Throwable t) { return null; }
    }

    private static void initReflection(Class<?> c) {
        if (fKeyViews != null) return;
        fKeyViews = field(c, "keyViews");
        fPrefs = field(c, "prefs");
        mFinishComposing = method(c, "finishComposing");
        mTypeText = method(c, "typeText", String.class);
        mOpenClipboard = method(c, "openClipboard");
        mDoBackspace = method(c, "doBackspace");
        try {
            Class<?> kv = Class.forName("com.thock.keyboard.KeyboardService$KeyView");
            mGetFrame = method(kv, "getFrame");
        } catch (Throwable ignored) {}
        try { mGetHaptic = method(Class.forName("com.thock.keyboard.Prefs"), "getHaptic"); } catch (Throwable ignored) {}
    }

    private static int intField(String n, int def) {
        try { Field f = field(svc.getClass(), n); return f == null ? def : f.getInt(svc); } catch (Throwable t) { return def; }
    }

    private static void call(Method m, Object... a) {
        if (m == null || svc == null) return;
        try { m.invoke(svc, a); } catch (Throwable ignored) {}
    }

    private static boolean hapticOn() {
        try { return (Boolean) mGetHaptic.invoke(fPrefs.get(svc)); } catch (Throwable t) { return true; }
    }

    // ================================================================ 1) TOUCH
    private static final class Ptr {
        final View view; final long downTime; float rx, ry;
        Ptr(View v, long d, float x, float y) { view = v; downTime = d; rx = x; ry = y; }
    }

    private static final SparseArray<Ptr> ptrs = new SparseArray<>();
    private static View passthru;
    private static float touchRawX, touchRawY;
    private static final int[] tmp = new int[2];
    private static final int[] tmp2 = new int[2];

    /** Replaces RootInterceptorLayout.dispatchTouchEvent. */
    public static boolean dispatch(FrameLayout layout, MotionEvent ev) {
        try {
            return dispatchImpl(layout, ev);
        } catch (Throwable t) {
            android.util.Log.e("ThockExt", "dispatch", t);
            return true;
        }
    }

    private static boolean dispatchImpl(FrameLayout layout, MotionEvent ev) {
        final int am = ev.getActionMasked();
        final float offX = ev.getRawX() - ev.getX();
        final float offY = ev.getRawY() - ev.getY();

        if (am == MotionEvent.ACTION_DOWN) {
            releaseAll(ev.getEventTime());   // a lost UP from the last touch: still type that key
            passthru = null;
            trainSync();
            View extra = findExtra(ev.getRawX(), ev.getRawY());
            if (extra != null) passthru = extra;
        }

        if (passthru != null) {
            forward(layout, passthru, ev);
            if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_CANCEL) passthru = null;
            return true;
        }
        // A panel is covering the keys: don't route anything to keys.
        if (isVisible(editPanel) || isVisible(phrasePanel) || isVisible(phraseEditPanel)) return true;

        switch (am) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = ev.getActionIndex();
                int id = ev.getPointerId(idx);
                float rx = ev.getX(idx) + offX, ry = ev.getY(idx) + offY;
                // Rollover: a new finger landed while others are still down.
                // Commit the earlier keys now, in order, so nothing is lost or reordered.
                releaseAll(ev.getEventTime());
                View v = nearestKey(rx, ry);
                if (v != null) {
                    Ptr p = new Ptr(v, ev.getEventTime(), rx, ry);
                    ptrs.put(id, p);
                    send(p, MotionEvent.ACTION_DOWN, ev.getEventTime());
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    Ptr p = ptrs.get(ev.getPointerId(i));
                    if (p == null) continue;
                    p.rx = ev.getX(i) + offX;
                    p.ry = ev.getY(i) + offY;
                    send(p, MotionEvent.ACTION_MOVE, ev.getEventTime());
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                int idx = ev.getActionIndex();
                int id = ev.getPointerId(idx);
                Ptr p = ptrs.get(id);
                if (p != null) {
                    p.rx = ev.getX(idx) + offX;
                    p.ry = ev.getY(idx) + offY;
                    ptrs.remove(id);
                    send(p, MotionEvent.ACTION_UP, ev.getEventTime());
                }
                if (am == MotionEvent.ACTION_UP) releaseAll(ev.getEventTime());
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelAll(ev.getEventTime());
                break;
        }
        return true;
    }

    private static void releaseAll(long t) {
        if (ptrs.size() == 0) return;
        // oldest first
        List<Ptr> list = new ArrayList<>();
        for (int i = 0; i < ptrs.size(); i++) list.add(ptrs.valueAt(i));
        ptrs.clear();
        java.util.Collections.sort(list, (a, b) -> Long.compare(a.downTime, b.downTime));
        for (Ptr p : list) send(p, MotionEvent.ACTION_UP, t);
    }

    private static void cancelAll(long t) {
        for (int i = 0; i < ptrs.size(); i++) send(ptrs.valueAt(i), MotionEvent.ACTION_CANCEL, t);
        ptrs.clear();
    }

    private static void send(Ptr p, int action, long t) {
        MotionEvent e = MotionEvent.obtain(p.downTime, t, action, p.rx, p.ry, 0);
        e.setLocation(p.view.getWidth() / 2f, p.view.getHeight() / 2f);
        touchRawX = p.rx;
        touchRawY = p.ry;
        try { p.view.dispatchTouchEvent(e); } catch (Throwable ignored) {}
        e.recycle();
    }

    private static void forward(View layout, View target, MotionEvent ev) {
        layout.getLocationOnScreen(tmp);
        target.getLocationOnScreen(tmp2);
        MotionEvent c = MotionEvent.obtain(ev);
        c.offsetLocation(tmp[0] - tmp2[0], tmp[1] - tmp2[1]);
        target.dispatchTouchEvent(c);
        c.recycle();
    }

    private static boolean isVisible(View v) { return v != null && v.getVisibility() == View.VISIBLE && v.isShown(); }

    private static boolean contains(View v, float rx, float ry) {
        if (!isVisible(v) || v.getWidth() == 0) return false;
        v.getLocationOnScreen(tmp);
        return rx >= tmp[0] && rx <= tmp[0] + v.getWidth() && ry >= tmp[1] && ry <= tmp[1] + v.getHeight();
    }

    private static View findExtra(float rx, float ry) {
        if (contains(editPanel, rx, ry)) return editPanel;
        if (contains(phrasePanel, rx, ry)) return phrasePanel;
        if (contains(phraseEditPanel, rx, ry)) return phraseEditPanel;
        if (contains(toolbar, rx, ry)) return toolbar;
        return null;
    }

    /** 눌린 키 찾기. 쿼티 자판에서 터치 보정이 켜져 있으면 학습된 인식 범위를 쓴다. */
    private static View nearestKey(float rx, float ry) {
        View v = nearestGeo(rx, ry);
        if (v == null || svc == null) return v;
        try {
            if (isVisible(cjPanel) && (mode == M_KOQ || mode == M_EN) && Calib.active(svc)) {
                Calib.Geo g = calibGeo();
                int i = -1;
                if (Calib.painted > 0 && g.uw > 0 && g.uh > 0) {      // 직접 그린 범위: 표준 자판 좌표로 바꿔서 본다
                    int b = Calib.pick(g, rx, ry, null);
                    Calib.Geo std = Calib.standard();
                    int si = b < 0 ? -1 : std.indexOf(g.ids.get(b));
                    if (si >= 0) {
                        float[] r = g.rects.get(b), sr = std.rects.get(si);
                        int k = Calib.paintAt((sr[0] + sr[2]) / 2f + (rx - (r[0] + r[2]) / 2f) / g.uw,
                                (sr[1] + sr[3]) / 2f + (ry - (r[1] + r[3]) / 2f) / g.uh);
                        if (k >= 0) i = g.indexOf(std.ids.get(k));
                    }
                }
                if (i < 0) i = Calib.pick(g, rx, ry, Calib.params());
                if (i >= 0) return (View) g.refs.get(i);
            }
        } catch (Throwable t) { android.util.Log.w("ThockExt", "calib pick", t); }
        return v;
    }

    /** 자리 id: 한글 기본 자모(영어는 같은 자리의 자모), 기능키는 SP/BS/…, 그 밖(숫자줄 등)은 "#글자". */
    private static String calibId(KS k) {
        switch (k.code) {
            case K_SPACE: return Calib.SP;
            case K_BS: return Calib.BS;
            case K_SHIFT: return Calib.SH;
            case K_ENTER: return Calib.EN;
            case K_LANG: return Calib.LG;
            case K_TO_ENSYM: return Calib.SY;
            case K_EMOJI: return Calib.EM;
            case K_JAMO:
            case K_TEXT:
                if (k.style != 3) { String id = Calib.idOfLabel(k.label); if (id != null) return id; }
        }
        return "#" + k.label;
    }

    /** 지금 화면의 키 칸들 (키 사이 여백 포함, 화면 좌표). */
    private static Calib.Geo calibGeo() {
        Calib.Geo g = new Calib.Geo();
        boolean unit = false;
        for (View v : cjKeys) {
            if (v == null || !v.isShown() || v.getWidth() == 0 || v.getHeight() == 0 || !(v.getTag() instanceof KS)) continue;
            v.getLocationOnScreen(tmp);
            int ml = 0, mt = 0, mr = 0, mb = 0;
            if (v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                ml = lp.leftMargin; mt = lp.topMargin; mr = lp.rightMargin; mb = lp.bottomMargin;
            }
            String id = calibId((KS) v.getTag());
            float l = tmp[0] - ml, tp = tmp[1] - mt, r = tmp[0] + v.getWidth() + mr, b = tmp[1] + v.getHeight() + mb;
            g.add(id, l, tp, r, b, v);
            if (!unit && Calib.isLetter(id)) { g.uw = r - l; g.uh = b - tp; unit = true; }
        }
        if (unit && g.uw > 0) Calib.setAspect(svc, g.uh / g.uw);
        return g;
    }

    /** 설정의 "따라 쓰기" 화면에서 치는 중인가 (이 앱의 입력칸일 때만: 다른 앱에서는 절대 켜지지 않는다). */
    private static boolean calibTraining() {
        if (!Calib.training || Calib.trainer == null || svc == null) return false;
        try {
            android.view.inputmethod.EditorInfo ei = svc.getCurrentInputEditorInfo();
            return ei != null && svc.getPackageName().equals(ei.packageName);
        } catch (Throwable t) { return false; }
    }

    private static boolean trainForced;

    /** 따라 쓰기 중에는 쿼티 한글 자판으로 고정하고, 끝나면 원래 자판으로 되돌린다. */
    private static void trainSync() {
        if (svc == null || cjPanelInner == null) return;
        boolean tr = calibTraining();
        if (tr && mode != M_KOQ) { cjFinish(); mode = M_KOQ; shiftState = 0; trainForced = true; rebuildKeys(); }
        else if (tr && shiftState != 0) { shiftState = 0; refreshKeys(); }
        else if (!tr && trainForced) {
            trainForced = false;
            int m = "EN".equals(layerName()) ? M_EN : koMode();
            if (m != mode) { mode = m; shiftState = 0; rebuildKeys(); }
        }
    }

    private static View nearestGeo(float rx, float ry) {
        if (svc == null) return null;
        List<View> cands = new ArrayList<>();
        if (isVisible(cjPanel)) {
            cands.addAll(cjKeys);
        } else {
            if (fKeyViews == null || mGetFrame == null) return null;
            ArrayList<?> kvs;
            try { kvs = (ArrayList<?>) fKeyViews.get(svc); } catch (Throwable t) { return null; }
            if (kvs == null) return null;
            for (Object kv : kvs) {
                try { cands.add((View) mGetFrame.invoke(kv)); } catch (Throwable ignored) {}
            }
        }
        if (cands.isEmpty()) return null;
        View best = null;
        float bestD = Float.MAX_VALUE;
        float maxD = dp(28);
        for (View f : cands) {
            if (f == null || !f.isShown() || f.getWidth() == 0 || f.getHeight() == 0) continue;
            f.getLocationOnScreen(tmp);
            float l = tmp[0], tp = tmp[1], r = l + f.getWidth(), b = tp + f.getHeight();
            if (rx >= l && rx <= r && ry >= tp && ry <= b) return f;
            float dx = rx < l ? l - rx : (rx > r ? rx - r : 0);
            float dy = ry < tp ? tp - ry : (ry > b ? ry - b : 0);
            float d = (float) Math.hypot(dx, dy);
            if (d < bestD) { bestD = d; best = f; }
        }
        return bestD <= maxD ? best : null;
    }

    // ================================================================ 2) UI
    private static int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, svc.getResources().getDisplayMetrics());
    }

    /** Called from onCreateInputView with the board root; returns the new root. */
    public static View wrap(InputMethodService s, View root) {
        svc = s;
        initReflection(s.getClass());
        ptrs.clear();
        passthru = null;
        selectMode = false;
        pendingDeleteIdx = -1;

        cBg = intField("colorBg", Color.parseColor("#1B1C1F"));
        cKey = intField("colorKey", Color.parseColor("#3A3C42"));
        cSpecial = intField("colorSpecial", Color.parseColor("#2A2C31"));
        cText = intField("colorText", Color.WHITE);
        cPressed = intField("colorPressed", Color.GRAY);
        cAccent = intField("colorAccent", Color.parseColor("#4C8DFF"));
        cHint = intField("colorHint", Color.GRAY);

        Context c = s;
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(cBg);

        toolbar = buildToolbar(c);
        outer.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(38)));

        FrameLayout stack = new FrameLayout(c);
        stackRef = stack;
        // bottom-aligned: when the clipboard/emoji screen is shorter, it keeps the keyboard's height
        stack.addView(root, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        cjPanel = buildCj(c);
        cjPanel.setVisibility(View.GONE);
        stack.addView(cjPanel, new FrameLayout.LayoutParams(-1, -1));
        editPanel = buildEditPanel(c);
        editPanel.setVisibility(View.GONE);
        stack.addView(editPanel, new FrameLayout.LayoutParams(-1, -1));
        phrasePanel = buildPhrasePanel(c);
        phrasePanel.setVisibility(View.GONE);
        stack.addView(phrasePanel, new FrameLayout.LayoutParams(-1, -1));
        phraseEditPanel = buildPhraseEditor(c);
        phraseEditPanel.setVisibility(View.GONE);
        stack.addView(phraseEditPanel, new FrameLayout.LayoutParams(-1, -1));
        outer.addView(stack, new LinearLayout.LayoutParams(-1, -2));
        rootRef = root;
        cj.reset();
        lastNumRow = null;
        lastQNumRow = null;
        lastBottom = -1;
        lastNumGap = -1;
        watchClipboard();
        Calib.load(s);
        Typo.load(s);
        Typo.loadLm(s);
        Typo.reset();
        Spell.load(s);
        Spell.reset(spellHost);
        applyLayoutOptions();
        updateCj();
        deepNoFocus(outer);
        return outer;
    }

    private static View rootRef;
    private static FrameLayout stackRef;
    private static int lastCjH;

    private static GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(6));
        return d;
    }

    private static StateListDrawable bg(int normal) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, shape(cPressed));
        sl.addState(new int[]{android.R.attr.state_selected}, shape(cAccent));
        sl.addState(new int[0], shape(normal));
        return sl;
    }

    private static void tick(View v) {
        if (hapticOn()) v.performHapticFeedback(3 /* KEYBOARD_TAP */);
    }

    private static TextView btn(Context c, String label, float sp, int color, Runnable onTap) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(cText);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setBackground(bg(color));
        t.setSingleLine(true);
        t.setClickable(true);
        noFocus(t);
        t.setOnClickListener(v -> { tick(v); onTap.run(); });
        return t;
    }

    /** Whole subtree can never take focus -> no gray focus overlay when Enter/arrows leave touch mode. */
    private static void deepNoFocus(View v) {
        if (v == null) return;
        noFocus(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            g.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            for (int i = 0; i < g.getChildCount(); i++) deepNoFocus(g.getChildAt(i));
        }
    }

    private static void noFocus(View v) {
        v.setFocusable(false);
        v.setFocusableInTouchMode(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
    }

    /** Icon-only toolbar button (transparent, rounded press state). */
    private static View iconBtn(Context c, int icon, Runnable onTap, boolean repeat) {
        FrameLayout f = new FrameLayout(c);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, shape(cPressed));
        sl.addState(new int[]{android.R.attr.state_selected}, shape(Color.TRANSPARENT));
        sl.addState(new int[0], shape(Color.TRANSPARENT));
        f.setBackground(sl);
        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setImageDrawable(new LineIcon(icon, cText));
        f.addView(iv, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        noFocus(f);
        f.setClickable(true);
        if (!repeat) {
            f.setOnClickListener(v -> { tick(v); onTap.run(); });
        } else {
            final Runnable[] loop = new Runnable[1];
            loop[0] = () -> { onTap.run(); H.postDelayed(loop[0], 45); };
            f.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true); tick(v); onTap.run(); H.postDelayed(loop[0], 400); return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false); H.removeCallbacks(loop[0]); return true;
                }
                return true;
            });
        }
        return f;
    }

    private static LinearLayout.LayoutParams tbCell() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), -1);
        lp.setMargins(0, dp(3), 0, dp(3));
        return lp;
    }

    /** Button that auto-repeats while held (arrows, backspace). */
    private static TextView repeatBtn(Context c, String label, float sp, int color, Runnable action) {
        TextView t = btn(c, label, sp, color, () -> {});
        t.setOnClickListener(null);
        final Runnable[] loop = new Runnable[1];
        loop[0] = () -> { action.run(); H.postDelayed(loop[0], 45); };
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    tick(v);
                    action.run();
                    H.postDelayed(loop[0], 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    H.removeCallbacks(loop[0]);
                    return true;
            }
            return true;
        });
        return t;
    }

    private static LinearLayout.LayoutParams cell(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, w);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private static View buildToolbar(Context c) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(2), dp(6), 0);
        tbEdit = iconBtn(c, LineIcon.PENCIL, Ext::onToolEdit, false);
        tbClip = iconBtn(c, LineIcon.CLIPBOARD, Ext::onToolClip, false);
        tbMsg = iconBtn(c, LineIcon.MESSAGE, Ext::onToolMsg, false);
        bar.addView(tbEdit, tbCell());
        bar.addView(tbMsg, tbCell());
        bar.addView(tbClip, tbCell());     // 편집 · 문구 · 클립보드 [최근 복사]
        // flexible middle area: holds the "최근 복사" chip (left aligned, shrinks when the bar is full)
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        tbFix = new TextView(c);                        // 오타 제안: 누르면 그 말로 바꾼다
        tbFix.setTextColor(cText);
        tbFix.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tbFix.setSingleLine(true);
        tbFix.setEllipsize(TextUtils.TruncateAt.START);
        tbFix.setGravity(Gravity.CENTER);
        tbFix.setPadding(dp(8), 0, dp(8), 0);
        tbFix.setBackground(bg(toolFill()));
        tbFix.setClickable(true);
        noFocus(tbFix);
        tbFix.setOnClickListener(v -> {                 // 오타 제안이 떠 있으면 그것, 아니면 맞춤법 제안
            tick(v);
            if (typoLabel != null) { Spell.clearDone(); Typo.applyPending(typoHost, svc); }
            else Spell.apply(spellHost, svc);
        });
        tbFix.setVisibility(View.GONE);
        LinearLayout.LayoutParams fixLp = new LinearLayout.LayoutParams(-2, dp(26));
        fixLp.setMargins(dp(1), 0, dp(1), 0);
        mid.addView(tbFix, fixLp);
        tbChip = new TextView(c);
        tbChip.setTextColor(cText);
        tbChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tbChip.setSingleLine(true);
        tbChip.setEllipsize(TextUtils.TruncateAt.END);
        tbChip.setGravity(Gravity.CENTER);
        tbChip.setPadding(dp(7), 0, dp(7), 0);
        tbChip.setBackground(bg(cKey));
        tbChip.setClickable(true);
        noFocus(tbChip);
        tbChip.setOnClickListener(v -> { tick(v); pasteLatestClip(); });
        tbChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, dp(26));
        chipLp.setMargins(dp(1), 0, dp(1), 0);
        mid.addView(tbChip, chipLp);
        bar.addView(mid, new LinearLayout.LayoutParams(0, -1, 1f));
        tbMic = iconBtn(c, LineIcon.MIC, Ext::onToolMic, false);
        bar.addView(tbMic, tbCell());
        tbLeft = iconBtn(c, LineIcon.TRI_LEFT, () -> arrow(KeyEvent.KEYCODE_DPAD_LEFT), true);
        tbRight = iconBtn(c, LineIcon.TRI_RIGHT, () -> arrow(KeyEvent.KEYCODE_DPAD_RIGHT), true);
        tbUp = iconBtn(c, LineIcon.TRI_UP, () -> arrow(KeyEvent.KEYCODE_DPAD_UP), true);
        tbDown = iconBtn(c, LineIcon.TRI_DOWN, () -> arrow(KeyEvent.KEYCODE_DPAD_DOWN), true);
        bar.addView(tbLeft, tbCell());     // 좌 우 위 아래
        bar.addView(tbRight, tbCell());
        bar.addView(tbUp, tbCell());
        bar.addView(tbDown, tbCell());
        tbSettings = iconBtn(c, LineIcon.GEAR, Ext::openSettings, false);
        bar.addView(tbSettings, tbCell());
        return bar;
    }

    private static View tbEdit, tbClip, tbMsg, tbLeft, tbRight, tbUp, tbDown, tbSettings, tbMic;
    private static TextView tbChip, tbFix;

    // ---- 오타 제안 연결
    private static float typoX = Float.NaN, typoY = Float.NaN;
    private static String typoId;
    private static final Typo.Host typoHost = new Typo.Host() {
        public InputConnection ic() { return svc == null ? null : svc.getCurrentInputConnection(); }
        public android.view.inputmethod.EditorInfo editor() { return svc == null ? null : svc.getCurrentInputEditorInfo(); }
        public void finishComposing() { Ext.finishComposing(); }
        public void show(String label) { typoLabel = label; showFix(); }
    };

    // ---- 맞춤법 제안 연결: 같은 칩을 쓰고, 오타 제안이 있으면 오타 제안이 먼저
    private static String typoLabel, spellLabel;
    private static final Typo.Host spellHost = new Typo.Host() {
        public InputConnection ic() { return typoHost.ic(); }
        public android.view.inputmethod.EditorInfo editor() { return typoHost.editor(); }
        public void finishComposing() { Ext.finishComposing(); }
        public void show(String label) { spellLabel = label; showFix(); }
    };

    private static void showFix() {
        if (tbFix == null) return;
        String label = typoLabel != null ? typoLabel : spellLabel;
        if (label != null) tbFix.setText(label);
        tbFix.setVisibility(label != null ? View.VISIBLE : View.GONE);
        refreshClipChip();
    }

    /** 누른 위치를 표준 자판 좌표로 적어 둔다 (쿼티 한글에서만). */
    private static void typoTouch(KS kk, View v) {
        typoX = typoY = Float.NaN;
        typoId = null;
        if (mode != M_KOQ) return;
        try {
            typoId = calibId(kk);
            Calib.Geo std = Calib.standard();
            int si = std.indexOf(typoId);
            if (si < 0) return;
            Calib.Geo g = calibGeo();
            int gi = g.refs.indexOf(v);
            if (gi < 0 || g.uw <= 0 || g.uh <= 0) return;
            float[] r = g.rects.get(gi), sr = std.rects.get(si);
            typoX = (sr[0] + sr[2]) / 2f + (touchRawX - (r[0] + r[2]) / 2f) / g.uw;
            typoY = (sr[1] + sr[3]) / 2f + (touchRawY - (r[1] + r[3]) / 2f) / g.uh;
        } catch (Throwable ignored) {}
    }

    // ---- 평소 타자 기록 (터치 보정용): 누를 때 위치를 적어 두고, 실제로 입력됐을 때 넘긴다
    private static String noteId;
    private static float noteDx, noteDy;

    private static void calibNoteDown(KS kk, View v) {
        noteId = null;
        if (svc == null || (mode != M_KOQ && mode != M_EN) || !Typo.learn || !Typo.usable(typoHost)) return;
        try {
            Calib.Geo g = calibGeo();
            int gi = g.refs.indexOf(v);
            if (gi < 0 || g.uw <= 0 || g.uh <= 0) return;
            String id = g.ids.get(gi);
            if (Calib.standard().indexOf(id) < 0) return;            // 숫자줄 등은 빼고
            float[] r = g.rects.get(gi);
            noteDx = (touchRawX - (r[0] + r[2]) / 2f) / g.uw;
            noteDy = (touchRawY - (r[1] + r[3]) / 2f) / g.uh;
            noteId = id;
        } catch (Throwable ignored) {}
    }

    private static void calibNoteTyped(int code) {
        if (noteId == null || svc == null) return;
        try {
            if (code == K_BS) Calib.noteBackspace(svc, noteDx, noteDy);
            else if (Calib.isLetter(noteId) || Calib.SP.equals(noteId) || Calib.DT.equals(noteId)) Calib.noteKey(svc, noteId, noteDx, noteDy);
        } catch (Throwable ignored) {}
        noteId = null;
    }

    private static boolean typoBoundary(int code, String label) {
        return code == K_SPACE || code == K_ENTER || code == K_PUNCT || code == K_COMMA
                || (code == K_TEXT && label != null && label.length() == 1 && ".,?!".indexOf(label.charAt(0)) >= 0);
    }

    private static void typoBefore(int code, String label) {
        if (svc != null && typoBoundary(code, label)) Typo.beforeBoundary(typoHost, svc, mode == M_KOQ);
    }

    private static void typoAfter(int code, String label) {
        if (svc == null || mode != M_KOQ) { Typo.reset(); return; }
        switch (code) {
            case K_JAMO: Typo.push(label.charAt(0), typoId, typoX, typoY); break;
            case K_SPACE: Typo.push(' ', Calib.SP, typoX, typoY); break;
            case K_TEXT:
                if (label != null && label.length() == 1) Typo.push(label.charAt(0), "#", Float.NaN, Float.NaN);
                else { Typo.reset(); return; }
                break;
            case K_SHIFT: return;
            default: Typo.reset(); return;
        }
        Typo.schedule(typoHost);
    }
    private static String latestClip;
    private static android.content.ClipboardManager clipMgr;
    private static android.content.ClipboardManager.OnPrimaryClipChangedListener clipListener;

    /** Listen to the system clipboard so the chip always shows the newest copy. */
    private static void watchClipboard() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipMgr != null && clipListener != null) { try { clipMgr.removePrimaryClipChangedListener(clipListener); } catch (Throwable ignored) {} }
            clipMgr = cm;
            clipListener = () -> H.post(Ext::refreshClipChip);
            cm.addPrimaryClipChangedListener(clipListener);
        } catch (Throwable t) { android.util.Log.w("ThockExt", "watchClipboard", t); }
    }

    /** Chip right of the clipboard icon: first 5 characters of the newest copied text; tap = paste it. */
    private static void refreshClipChip() {
        if (tbChip == null || svc == null) return;
        String text = null;
        boolean sensitive = false;
        try {
            android.content.ClipData cd = clipMgr == null ? null : clipMgr.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence cs = cd.getItemAt(0).coerceToText(svc);
                if (cs != null && cs.length() > 0) text = cs.toString();
                android.content.ClipDescription d = cd.getDescription();
                if (d != null && android.os.Build.VERSION.SDK_INT >= 24 && d.getExtras() != null)
                    sensitive = d.getExtras().getBoolean("android.content.extra.IS_SENSITIVE", false);
            }
        } catch (Throwable ignored) {}
        latestClip = text;
        boolean show = text != null && opt("tb_clipchip") && opt("tb_clip") && !(tbFix != null && tbFix.getVisibility() == View.VISIBLE);
        if (show) {
            String one = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
            if (one.isEmpty()) one = "␣";
            int n = Math.min(one.length(), one.offsetByCodePoints(0, Math.min(5, one.codePointCount(0, one.length()))));
            String label = sensitive ? "•••••" : one.substring(0, n);   // 앞 5자만
            tbChip.setText(label);
        }
        tbChip.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static void pasteLatestClip() {
        refreshClipChip();
        final String text = latestClip;
        if (text == null) return;
        closeTools();
        cjFinish();
        call(mTypeText, text);
    }

    /** 툴바 마이크: jump straight to the voice-typing keyboard (Google 음성 입력) without the picker. */
    private static void onToolMic() {
        android.view.inputmethod.InputMethodManager imm = null;
        try {
            closeTools();
            cjFinish();
            finishComposing();
            imm = (android.view.inputmethod.InputMethodManager) svc.getSystemService(Context.INPUT_METHOD_SERVICE);
            String best = null;
            int bestScore = 0;
            for (android.view.inputmethod.InputMethodInfo imi : imm.getEnabledInputMethodList()) {
                if (svc.getPackageName().equals(imi.getPackageName())) continue;
                String id = imi.getId();
                String low = id.toLowerCase(java.util.Locale.ROOT);
                int score = 0;
                for (int i = 0; i < imi.getSubtypeCount(); i++) if ("voice".equals(imi.getSubtypeAt(i).getMode())) score = 2;
                if (low.contains("voice") || low.contains("asr") || low.contains("dictation")) score = Math.max(score, 2);
                if (score > 0 && low.contains("google")) score++;      // prefer Google 음성 입력
                if (score > bestScore) { bestScore = score; best = id; }
            }
            if (best != null) { svc.switchInputMethod(best); return; }
        } catch (Throwable t) { android.util.Log.w("ThockExt", "onToolMic", t); }
        try { if (imm != null) imm.showInputMethodPicker(); } catch (Throwable ignored) {}   // no voice keyboard found: let the user pick
    }

    /** 툴바 ⚙: open the settings app from anywhere. */
    private static void openSettings() {
        try {
            closeTools();
            finishComposing();
            android.content.Intent i = new android.content.Intent(svc, Class.forName("com.thock.keyboard.MainActivity"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            svc.startActivity(i);
            svc.requestHideSelf(0);
        } catch (Throwable t) { android.util.Log.e("ThockExt", "openSettings", t); }
    }
    private static Boolean lastNumRow;

    // layout options (설정 › 자판 · 크기)
    private static boolean opt(String k) { return svc == null || prefs().getBoolean(k, true); }
    private static boolean optOff(String k) { return svc != null && prefs().getBoolean(k, false); }
    private static boolean numRowOn() { return opt("numrow"); }
    private static boolean qNumRowOn() { return opt("qnumrow"); }
    /** extra space under the keys (dp); 8 = same as Samsung Keyboard */
    private static int bottomExtra() { return svc == null ? 8 : Math.max(0, Math.min(40, prefs().getInt("bottom_pad", 8))); }
    private static Boolean lastQNumRow;
    /** gap under the 천지인·나랏글 number bar / under the toolbar (dp, default 0) */
    private static int numGap() { return svc == null ? 0 : Math.max(0, Math.min(30, prefs().getInt("numbar_gap", 0))); }
    private static int tbGap() { return svc == null ? 0 : Math.max(0, Math.min(30, prefs().getInt("tb_gap", 0))); }
    private static int lastNumGap = -1;
    private static int lastBottom = -1;

    /** Apply toolbar on/off options; hide the whole bar when nothing is left. */
    private static void applyLayoutOptions() {
        if (toolbar == null) return;
        boolean e = opt("tb_edit"), c = opt("tb_clip"), m = opt("tb_msg"), ar = opt("tb_arrows"), ud = optOff("tb_updown");
        if (tbEdit != null) tbEdit.setVisibility(e ? View.VISIBLE : View.GONE);
        if (tbClip != null) tbClip.setVisibility(c ? View.VISIBLE : View.GONE);
        if (tbMsg != null) tbMsg.setVisibility(m ? View.VISIBLE : View.GONE);
        if (tbLeft != null) tbLeft.setVisibility(ar ? View.VISIBLE : View.GONE);
        if (tbRight != null) tbRight.setVisibility(ar ? View.VISIBLE : View.GONE);
        if (tbUp != null) tbUp.setVisibility(ud ? View.VISIBLE : View.GONE);
        if (tbDown != null) tbDown.setVisibility(ud ? View.VISIBLE : View.GONE);
        boolean st = opt("tb_settings");
        if (tbSettings != null) tbSettings.setVisibility(st ? View.VISIBLE : View.GONE);
        boolean mic = opt("tb_mic");
        if (tbMic != null) tbMic.setVisibility(mic ? View.VISIBLE : View.GONE);
        refreshClipChip();
        toolbar.setVisibility(e || c || m || ar || ud || st || mic ? View.VISIBLE : View.GONE);
        boolean nr = numRowOn();
        if (cjPanelInner != null && lastSplit != null && lastSplit != splitOn()) { rebuildKeys(); cjPanel.requestLayout(); }
        boolean qnr = qNumRowOn();
        int be = bottomExtra();
        if (lastNumRow == null || lastNumRow != nr || lastQNumRow == null || lastQNumRow != qnr) {
            lastNumRow = nr; lastQNumRow = qnr; rebuildKeys(); if (cjPanel != null) cjPanel.requestLayout();
        }
        int ng = numGap();
        if (ng != lastNumGap) { lastNumGap = ng; rebuildKeys(); if (cjPanel != null) cjPanel.requestLayout(); }
        ViewGroup.LayoutParams tlp = toolbar.getLayoutParams();
        float sc = Math.max(5, Math.min(10, prefs().getInt("tb_scale10", 10))) / 10f;   // 툴바 크기 0.5~1.0배
        int th = dp(38 * sc);
        if (tlp instanceof LinearLayout.LayoutParams && (((LinearLayout.LayoutParams) tlp).bottomMargin != dp(tbGap()) || tlp.height != th)) {
            ((LinearLayout.LayoutParams) tlp).bottomMargin = dp(tbGap());
            tlp.height = th;
            toolbar.setLayoutParams(tlp);
            toolbar.setPadding(dp(6), dp(2 * sc), dp(6), 0);
            int is = dp(22 * sc);
            ViewGroup bar = (ViewGroup) toolbar;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View ch = bar.getChildAt(i);
                if (ch instanceof FrameLayout && ((FrameLayout) ch).getChildCount() > 0) {
                    View iv = ((FrameLayout) ch).getChildAt(0);
                    ViewGroup.LayoutParams ilp = iv.getLayoutParams();
                    ilp.width = is; ilp.height = is;
                    iv.setLayoutParams(ilp);
                    if (ch.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams clp = (ViewGroup.MarginLayoutParams) ch.getLayoutParams();
                        clp.topMargin = clp.bottomMargin = dp(3 * sc);
                        ch.setLayoutParams(clp);
                    }
                }
            }
        }
        if (be != lastBottom) { lastBottom = be; if (cjPanel != null) { updateCjPadding(); cjPanel.requestLayout(); } }
    }

    /** Clipboard screen: hide its "가" (back to keyboard) key - the toolbar icon closes it instead. */
    private static void hideClipBackKey() {
        try {
            if (!"CLIP".equals(layerName())) return;
            ArrayList<?> kvs = (ArrayList<?>) fKeyViews.get(svc);
            Method gk = method(Class.forName("com.thock.keyboard.KeyboardService$KeyView"), "getKey");
            for (Object kv : kvs) {
                Object key = gk.invoke(kv);
                Object type = key.getClass().getMethod("getType").invoke(key);
                if ("SYMBOLS".equals(((Enum<?>) type).name())) ((View) mGetFrame.invoke(kv)).setVisibility(View.GONE);
            }
        } catch (Throwable t) { android.util.Log.w("ThockExt", "hideClipBackKey", t); }
    }

    private static boolean clipOpen() { return "CLIP".equals(layerName()); }
    private static boolean editOpen() { return isVisible(editPanel); }
    private static boolean msgOpen() { return isVisible(phrasePanel) || isVisible(phraseEditPanel); }

    /** Close every tool window (edit / phrases / clipboard) and go back to the keys. */
    private static void closeTools() {
        hidePanels();
        if (clipOpen()) call(method(svc.getClass(), "toggleSymbols"));
        updateToolbar();
    }

    private static void onToolEdit() {
        boolean was = editOpen();
        closeTools();
        if (!was) toggle(editPanel);
        updateToolbar();
    }

    private static void onToolClip() {
        boolean was = clipOpen();
        closeTools();
        if (!was) { finishComposing(); call(mOpenClipboard); }
        updateToolbar();
    }

    private static void onToolMsg() {
        boolean was = msgOpen();
        closeTools();
        if (!was) { refreshQuick(); toggle(phrasePanel); }
        updateToolbar();
    }

    /** Selected tool = orange icon on a soft orange chip. */
    private static void updateToolbar() {
        markTool(tbEdit, editOpen());
        markTool(tbClip, clipOpen());
        markTool(tbMsg, msgOpen());
    }

    private static void markTool(View b, boolean on) {
        if (b == null) return;
        b.setSelected(on);
        if (b instanceof FrameLayout && ((FrameLayout) b).getChildCount() > 0) {
            View iv = ((FrameLayout) b).getChildAt(0);
            if (iv instanceof android.widget.ImageView) {
                if (on) ((android.widget.ImageView) iv).setColorFilter(toolAccent());
                else ((android.widget.ImageView) iv).clearColorFilter();
            }
        }
    }

    private static void updateCjPadding() {
        if (rootRef == null || cjPanel == null) return;
        boolean sp = Boolean.TRUE.equals(lastSplit);      // split: margins are handled by the row layout
        cjPanel.setPadding(sp ? 0 : rootRef.getPaddingLeft(), rootRef.getPaddingTop(), sp ? 0 : rootRef.getPaddingRight(), rootRef.getPaddingBottom() + dp(bottomExtra()));
    }

    private static boolean darkBg() { return (Color.red(cBg) + Color.green(cBg) + Color.blue(cBg)) < 384; }
    private static int toolAccent() { return Color.parseColor(darkBg() ? "#E08A9B" : "#7D2F3E"); }
    private static int toolSoft() { return Color.parseColor(darkBg() ? "#4A2129" : "#F3DFE2"); }
    private static int toolFill() { return Color.parseColor(darkBg() ? "#9C4255" : "#E9C3CA"); }

    private static StateListDrawable toolBg(int normal) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, shape(cPressed));
        sl.addState(new int[]{android.R.attr.state_selected}, shape(toolFill()));
        sl.addState(new int[0], shape(normal));
        return sl;
    }

    private static void toggle(View panel) {
        boolean show = panel.getVisibility() != View.VISIBLE;
        hidePanels();
        if (show) {
            finishComposing();
            if (rootRef != null) panel.setPadding(dp(6), dp(4), dp(6), dp(4) + rootRef.getPaddingBottom() + dp(bottomExtra()));
            panel.setVisibility(View.VISIBLE);
        }
        updateToolbar();
    }

    private static void hidePanels() {
        if (editPanel != null) editPanel.setVisibility(View.GONE);
        if (phrasePanel != null) phrasePanel.setVisibility(View.GONE);
        if (phraseEditPanel != null) phraseEditPanel.setVisibility(View.GONE);
        setSelectMode(false);
        pendingDeleteIdx = -1;
        updateToolbar();
    }

    private static void openClips() {
        hidePanels();
        if ("CLIP".equals(layerName())) {
            call(method(svc.getClass(), "toggleSymbols")); // second tap = back to the keyboard
        } else {
            cjFinish();
            call(mOpenClipboard);
        }
    }

    // ---------------------------------------------------------------- edit panel
    private static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private static View buildEditPanel(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(cBg);
        p.setClickable(true);

        LinearLayout r1 = row(c);
        r1.addView(btn(c, "전체선택", 14, cSpecial, () -> ctx(android.R.id.selectAll)), cell(1));
        r1.addView(repeatBtn(c, "▲", 18, cKey, () -> arrow(KeyEvent.KEYCODE_DPAD_UP)), cell(1));
        r1.addView(btn(c, "복사", 14, cSpecial, () -> { ctx(android.R.id.copy); setSelectMode(false); }), cell(1));
        r1.addView(repeatBtn(c, "⌫", 18, cSpecial, () -> call(mDoBackspace)), cell(1));

        LinearLayout r2 = row(c);
        r2.addView(repeatBtn(c, "◀", 18, cKey, () -> arrow(KeyEvent.KEYCODE_DPAD_LEFT)), cell(1));
        selectBtn = btn(c, "선택", 14, cSpecial, () -> setSelectMode(!selectMode));
        selectBtn.setBackground(toolBg(cSpecial));
        r2.addView(selectBtn, cell(1));
        r2.addView(repeatBtn(c, "▶", 18, cKey, () -> arrow(KeyEvent.KEYCODE_DPAD_RIGHT)), cell(1));
        r2.addView(btn(c, "붙여넣기", 14, cSpecial, () -> { ctx(android.R.id.paste); setSelectMode(false); }), cell(1));

        LinearLayout r3 = row(c);
        r3.addView(btn(c, "처음", 14, cSpecial, () -> arrow(KeyEvent.KEYCODE_MOVE_HOME)), cell(1));
        r3.addView(repeatBtn(c, "▼", 18, cKey, () -> arrow(KeyEvent.KEYCODE_DPAD_DOWN)), cell(1));
        r3.addView(btn(c, "끝", 14, cSpecial, () -> arrow(KeyEvent.KEYCODE_MOVE_END)), cell(1));
        r3.addView(btn(c, "잘라내기", 14, cSpecial, () -> { ctx(android.R.id.cut); setSelectMode(false); }), cell(1));

        for (LinearLayout r : new LinearLayout[]{r1, r2, r3}) p.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        return p;
    }

    private static void setSelectMode(boolean on) {
        selectMode = on;
        if (selectBtn != null) selectBtn.setSelected(on);
    }

    private static void finishComposing() { cjFinish(); call(mFinishComposing); }

    private static void ctx(int id) {
        InputConnection ic = svc == null ? null : svc.getCurrentInputConnection();
        if (ic == null) return;
        finishComposing();
        ic.performContextMenuAction(id);
    }

    private static void arrow(int code) {
        InputConnection ic = svc == null ? null : svc.getCurrentInputConnection();
        if (ic == null) return;
        finishComposing();
        // Move the cursor ourselves so it stops at the start/end of the text instead of
        // the key event escaping the field and moving focus to another view.
        ExtractedText et = null;
        try { et = ic.getExtractedText(new ExtractedTextRequest(), 0); } catch (Throwable ignored) {}
        if (et != null && et.text != null && et.selectionStart >= 0) {
            CharSequence txt = et.text;
            int len = txt.length();
            int anchor = et.startOffset + et.selectionStart;
            int cur = et.startOffset + et.selectionEnd;
            int pos = cur - et.startOffset;
            int np = pos;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (!selectMode && et.selectionStart != et.selectionEnd) np = Math.min(et.selectionStart, et.selectionEnd);
                    else if (pos > 0) { np = pos - 1; if (np > 0 && Character.isLowSurrogate(txt.charAt(np)) && Character.isHighSurrogate(txt.charAt(np - 1))) np--; }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!selectMode && et.selectionStart != et.selectionEnd) np = Math.max(et.selectionStart, et.selectionEnd);
                    else if (pos < len) { np = pos + 1; if (np < len && Character.isLowSurrogate(txt.charAt(np)) && Character.isHighSurrogate(txt.charAt(np - 1))) np++; }
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (lastIndexOfNl(txt, pos) < 0) np = 0;            // first line: go to start, don't leave the field
                    else { sendArrowKey(ic, code); return; }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (indexOfNl(txt, pos) < 0) np = len;             // last line: go to end
                    else { sendArrowKey(ic, code); return; }
                    break;
                case KeyEvent.KEYCODE_MOVE_HOME: {
                    int nl = lastIndexOfNl(txt, pos); np = nl + 1; break;
                }
                case KeyEvent.KEYCODE_MOVE_END: {
                    int nl = indexOfNl(txt, pos); np = nl < 0 ? len : nl; break;
                }
                default:
                    sendArrowKey(ic, code); return;
            }
            int abs = et.startOffset + np;
            if (selectMode) ic.setSelection(anchor, abs);
            else ic.setSelection(abs, abs);
            return;
        }
        sendArrowKey(ic, code);
    }

    private static int lastIndexOfNl(CharSequence t, int pos) {
        for (int i = Math.min(pos, t.length()) - 1; i >= 0; i--) if (t.charAt(i) == '\n') return i;
        return -1;
    }

    private static int indexOfNl(CharSequence t, int pos) {
        for (int i = Math.max(0, pos); i < t.length(); i++) if (t.charAt(i) == '\n') return i;
        return -1;
    }

    private static void sendArrowKey(InputConnection ic, int code) {
        long t = SystemClock.uptimeMillis();
        int meta = selectMode ? (KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON) : 0;
        if (selectMode) ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta));
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta));
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta));
        if (selectMode) ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0));
    }

    // ---------------------------------------------------------------- phrases
    private static SharedPreferences sp() { return svc.getSharedPreferences("thock_phrases", Context.MODE_PRIVATE); }

    private static List<String> loadPhrases() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Throwable ignored) {}
        return out;
    }

    private static void savePhrases(List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        sp().edit().putString("list", a.toString()).apply();
    }


    // ---- quick panel: up to 4 phrases, tap = insert immediately; 편집 opens the manager
    private static final int QUICK = 4;
    private static LinearLayout quickGrid;

    private static View buildPhrasePanel(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(cBg);
        p.setClickable(true);
        quickGrid = new LinearLayout(c);
        quickGrid.setOrientation(LinearLayout.VERTICAL);
        p.addView(quickGrid, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout bottom = row(c);
        bottom.addView(btn(c, "문구 편집", 14, cSpecial, () -> { refreshEditor(); toggle(phraseEditPanel); }), cell(1));
        p.addView(bottom, new LinearLayout.LayoutParams(-1, dp(44)));
        return p;
    }

    private static void refreshQuick() {
        if (quickGrid == null) return;
        Context c = svc;
        quickGrid.removeAllViews();
        List<String> items = loadPhrases();
        for (int r = 0; r < 2; r++) {
            LinearLayout row = row(c);
            for (int k = 0; k < 2; k++) {
                int idx = r * 2 + k;
                TextView t;
                if (idx < items.size() && idx < QUICK) {
                    final String text = items.get(idx);
                    t = btn(c, oneLine(text), 15, cKey, () -> { hidePanels(); cjFinish(); call(mTypeText, text); });
                    t.setSingleLine(false);
                    t.setMaxLines(3);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    t.setPadding(dp(10), dp(4), dp(10), dp(4));
                } else {
                    t = btn(c, "비어있음", 14, cSpecial, Ext::askSaveCurrent);
                    t.setTextColor(cHint);
                }
                row.addView(t, cell(1));
            }
            quickGrid.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
    }

    /** Empty slot tapped: show what is in the field and ask before saving. */
    private static void askSaveCurrent() {
        Context c = svc;
        String text = currentFieldText();
        quickGrid.removeAllViews();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView q = new TextView(c);
        q.setTextColor(cText);
        q.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        q.setGravity(Gravity.CENTER);
        box.addView(q, new LinearLayout.LayoutParams(-1, -2));
        if (text.trim().isEmpty()) {
            q.setText("입력창이 비어 있어요");
            TextView h = new TextView(c);
            h.setText("저장할 글을 먼저 입력해 주세요");
            h.setTextColor(cHint);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            h.setGravity(Gravity.CENTER);
            box.addView(h, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout br = row(c);
            br.addView(btn(c, "확인", 14, cSpecial, Ext::refreshQuick), cell(1));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(46));
            blp.topMargin = dp(12);
            box.addView(br, blp);
        } else {
            q.setText("현재 입력된 문구를 저장할까요?");
            TextView pv = new TextView(c);
            pv.setText(oneLine(text));
            pv.setTextColor(cText);
            pv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            pv.setMaxLines(2);
            pv.setEllipsize(TextUtils.TruncateAt.END);
            pv.setGravity(Gravity.CENTER);
            pv.setBackground(shape(cKey));
            pv.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            plp.topMargin = dp(10);
            box.addView(pv, plp);
            LinearLayout br = row(c);
            br.addView(btn(c, "취소", 14, cSpecial, Ext::refreshQuick), cell(1));
            br.addView(btn(c, "저장", 14, toolFill(), () -> { saveText(text); refreshQuick(); }), cell(1));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(46));
            blp.topMargin = dp(12);
            box.addView(br, blp);
        }
        quickGrid.addView(box, new LinearLayout.LayoutParams(-1, -1));
    }

    /** Selection if any, otherwise the whole field. */
    private static String currentFieldText() {
        InputConnection ic = svc == null ? null : svc.getCurrentInputConnection();
        if (ic == null) return "";
        finishComposing();
        CharSequence sel = ic.getSelectedText(0);
        String text = sel == null ? "" : sel.toString();
        if (text.trim().isEmpty()) {
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null) text = et.text.toString();
        }
        if (text.trim().isEmpty()) {
            CharSequence b = ic.getTextBeforeCursor(5000, 0), a = ic.getTextAfterCursor(5000, 0);
            text = (b == null ? "" : b.toString()) + (a == null ? "" : a.toString());
        }
        return text;
    }

    private static void saveText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        List<String> l = loadPhrases();
        l.remove(text);
        l.add(0, text);
        while (l.size() > 100) l.remove(l.size() - 1);
        savePhrases(l);
        pendingDeleteIdx = -1;
    }

    // ---- editor: full list, add / delete / move up (top 4 = quick slots)
    private static View phraseEditPanel;
    private static LinearLayout editList;

    private static View buildPhraseEditor(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(cBg);
        p.setClickable(true);
        LinearLayout top = row(c);
        top.addView(btn(c, "입력한 글 저장", 14, cSpecial, () -> { if (saveCurrent()) refreshEditor(); }), cell(2));
        top.addView(btn(c, "완료", 14, toolFill(), () -> { refreshQuick(); toggle(phrasePanel); }), cell(1));
        p.addView(top, new LinearLayout.LayoutParams(-1, dp(44)));
        ScrollView sv = new ScrollView(c);
        editList = new LinearLayout(c);
        editList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(editList, new FrameLayout.LayoutParams(-1, -2));
        p.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        return p;
    }

    private static void refreshEditor() {
        if (editList == null) return;
        Context c = svc;
        editList.removeAllViews();
        List<String> items = loadPhrases();
        if (items.isEmpty()) {
            TextView e = new TextView(c);
            e.setText("저장된 문구가 없어요.\n입력창에 글을 쓰고(또는 일부를 선택하고)\n'입력한 글 저장'을 누르세요.\n\n맨 위 4개가 빠른 문구 칸에 보여요. ▲로 순서를 올릴 수 있어요.");
            e.setTextColor(cHint);
            e.setGravity(Gravity.CENTER);
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            e.setPadding(dp(8), dp(20), dp(8), dp(8));
            editList.addView(e, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            LinearLayout r = row(c);
            TextView t = new TextView(c);
            t.setText(oneLine(items.get(i)));
            t.setTextColor(cText);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(12), dp(4), dp(8), dp(4));
            t.setBackground(shape(cKey));
            r.addView(t, cell(1));
            TextView up = btn(c, "▲", 14, cSpecial, () -> {
                List<String> l = loadPhrases();
                if (idx > 0 && idx < l.size()) { l.add(idx - 1, l.remove(idx)); savePhrases(l); }
                pendingDeleteIdx = -1;
                refreshEditor();
            });
            up.setEnabled(idx > 0);
            up.setAlpha(idx > 0 ? 1f : 0.35f);
            r.addView(up, fixed(44));
            final boolean armed = pendingDeleteIdx == idx;
            TextView del = btn(c, armed ? "삭제?" : "✕", armed ? 12 : 14, armed ? Color.parseColor("#D9534F") : cSpecial, () -> {
                if (pendingDeleteIdx == idx) {
                    List<String> l = loadPhrases();
                    if (idx < l.size()) l.remove(idx);
                    savePhrases(l);
                    pendingDeleteIdx = -1;
                } else {
                    pendingDeleteIdx = idx;
                }
                refreshEditor();
            });
            r.addView(del, fixed(48));
            editList.addView(r, new LinearLayout.LayoutParams(-1, dp(52)));
        }
    }

    private static LinearLayout.LayoutParams fixed(int wDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(wDp), -1);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private static String oneLine(String s) { return s.trim().replaceAll("\\s+", " "); }

    /** Saves selection (or whole field) as a phrase at the top. Returns true if saved. */
    private static boolean saveCurrent() {
        InputConnection ic = svc == null ? null : svc.getCurrentInputConnection();
        if (ic == null) return false;
        finishComposing();
        CharSequence sel = ic.getSelectedText(0);
        String text = sel == null ? "" : sel.toString();
        if (text.trim().isEmpty()) {
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null) text = et.text.toString();
        }
        if (text.trim().isEmpty()) {
            CharSequence b = ic.getTextBeforeCursor(5000, 0), a = ic.getTextAfterCursor(5000, 0);
            text = (b == null ? "" : b.toString()) + (a == null ? "" : a.toString());
        }
        if (text.trim().isEmpty()) return false;
        List<String> l = loadPhrases();
        l.remove(text);
        l.add(0, text);
        while (l.size() > 100) l.remove(l.size() - 1);
        savePhrases(l);
        pendingDeleteIdx = -1;
        return true;
    }

    // ================================================================ 3) 천지인
    private static final CjEngine cj = new CjEngine();
    private static View cjPanel;
    private static final List<View> cjKeys = new ArrayList<>();
    private static TextView layoutBtn;
    private static long cjLastEdit;
    private static boolean lastPunct;
    private static int punctIdx;
    private static final String PUNCT = ".,?!";

    private static SharedPreferences prefs() { return svc.getSharedPreferences("thock_ext", Context.MODE_PRIVATE); }
    private static boolean cjOn() { return svc != null && prefs().getBoolean("cj", true); }

    private static String layerName() {
        try {
            Field f = field(svc.getClass(), "layer");
            Object o = f == null ? null : f.get(svc);
            return o == null ? "" : ((Enum<?>) o).name();
        } catch (Throwable t) { return ""; }
    }

    private static void updateCj() {
        if (cjPanel == null || svc == null) return;
        String layer = layerName();
        boolean show = !"EMOJI".equals(layer) && !"CLIP".equals(layer);
        if (show && !layer.equals(lastLayerSeen)) {
            // the app changed layer (new field / language toggle / back from emoji): follow it
            int m = "EN".equals(layer) ? M_EN : ("SYM1".equals(layer) || "SYM2".equals(layer)) ? M_NUM : koMode();
            if (m != mode) { cjFinish(); mode = m; shiftState = 0; rebuildKeys(); }
        }
        lastLayerSeen = layer;
        if (show) {
            updateCjPadding();
            cjPanel.setVisibility(View.VISIBLE);
            if (stackRef != null) stackRef.setMinimumHeight(0);
        } else {
            if (stackRef != null && lastCjH > 0) stackRef.setMinimumHeight(lastCjH);   // 클립보드/이모지도 같은 높이
            if (cjPanel.getVisibility() == View.VISIBLE) cjFinish();
            cjPanel.setVisibility(View.GONE);
        }
    }

    /** Hook: start of KeyboardService.renderRows(). */
    public static void onRender(InputMethodService s) {
        try {
            if (s == svc) {
                applyLayoutOptions();
                updateCj();
                trainSync();
                updateToolbar();
                if ("CLIP".equals(layerName())) H.post(Ext::hideClipBackKey); // runs after renderRows finished
            }
        } catch (Throwable t) { android.util.Log.e("ThockExt", "onRender", t); }
    }

    /** Hook: start of KeyboardService.onFinishInput(). */
    public static void onFinish(InputMethodService s) {
        try { Typo.reset(); Spell.reset(spellHost); Typo.flush(s); Calib.settle(s, true); } catch (Throwable ignored) {}
        cj.reset();
        nara.reset();
        lastPunct = false;
        lastLayerSeen = "";
    }

    /** Hook: start of KeyboardService.onUpdateSelection(). */
    public static void onSel(InputMethodService s, int oss, int ose, int nss, int nse, int cs, int ce) {
        if (cs < 0 || nss != nse) needsSync = true;     // the editor dropped/moved our composing text: verify at next key
        if (!(cj.isComposing() || nara.isComposing()) || SystemClock.uptimeMillis() - cjLastEdit < 150) return;
        if (cs < 0 || ce < 0 || nss != nse || nss < cs || nss > ce) {
            cj.reset();
            nara.reset();
            InputConnection ic = s.getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        }
    }

    private static final NaraEngine nara = new NaraEngine();

    private static void naraApply(String commit) {
        InputConnection ic = svc.getCurrentInputConnection();
        if (ic == null) { nara.reset(); return; }
        ic.beginBatchEdit();
        if (!commit.isEmpty()) ic.commitText(commit, 1);
        String comp = nara.composing();
        if (comp.isEmpty()) { ic.setComposingText("", 1); ic.finishComposingText(); }
        else ic.setComposingText(comp, 1);
        ic.endBatchEdit();
        cjLastEdit = SystemClock.uptimeMillis();
    }

    private static void cjFinish() {
        lastPunct = false;
        if (nara.isComposing()) {
            InputConnection nic = svc == null ? null : svc.getCurrentInputConnection();
            String nt = nara.finish();
            if (nic != null) nic.commitText(nt, 1);
            cjLastEdit = SystemClock.uptimeMillis();
        } else nara.reset();
        if (!cj.isComposing()) { cj.reset(); return; }
        InputConnection ic = svc == null ? null : svc.getCurrentInputConnection();
        String t = cj.finish();
        if (ic != null) { ic.commitText(t, 1); }
        cjLastEdit = SystemClock.uptimeMillis();
    }

    private static void cjApply(String commit) {
        InputConnection ic = svc.getCurrentInputConnection();
        if (ic == null) { cj.reset(); return; }
        ic.beginBatchEdit();
        if (!commit.isEmpty()) ic.commitText(commit, 1);
        String comp = cj.composing();
        if (comp.isEmpty()) { ic.setComposingText("", 1); ic.finishComposingText(); }
        else ic.setComposingText(comp, 1);
        ic.endBatchEdit();
        cjLastEdit = SystemClock.uptimeMillis();
    }

    private static void cjCommitRaw(String s) {
        InputConnection ic = svc.getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(s, 1);
        cjLastEdit = SystemClock.uptimeMillis();
    }

    // key actions --------------------------------------------------------
    // modes (Samsung layout set)
    private static final int M_KO = 0, M_NUM = 1, M_SYM = 2, M_EN = 3, M_ENSYM = 4, M_KOQ = 5, M_NARA = 6;
    private static int symReturn = M_EN;
    /** Korean layout chosen in settings: 천지인 or 쿼티. */
    /** 한글 자판 종류: cj(천지인) / nara(나랏글) / qwerty */
    private static String koType() {
        if (svc == null) return "cj";
        String t = prefs().getString("ko", null);
        return t != null ? t : (prefs().getBoolean("cj", true) ? "cj" : "qwerty");
    }
    private static int koMode() { String t = koType(); return "nara".equals(t) ? M_NARA : "qwerty".equals(t) ? M_KOQ : M_KO; }
    private static Method mTypeJamo;
    private static int mode = M_KO, symPage = 0, enSymPage = 0;
    private static int shiftState = 0; // 0 off, 1 once, 2 lock
    private static long lastShiftTap;
    private static String lastLayerSeen = "";

    private static final int K_I = 100, K_A = 101, K_EU = 102, K_BS = 103, K_SPACE = 104, K_PUNCT = 105,
            K_LANG = 107, K_ENTER = 108, K_EMOJI = 109, K_TO_NUM = 110, K_TO_SYM = 111, K_TO_KO = 112,
            K_PAGE = 113, K_SHIFT = 114, K_TO_ENSYM = 115, K_TO_EN = 116, K_TEXT = 200, K_JAMO = 201,
            K_N_CONS = 120, K_N_AE = 121, K_N_OU = 122, K_N_I = 123, K_N_EU = 124, K_N_STROKE = 125, K_N_DOUBLE = 126, K_COMMA = 127;

    private static String punctSet() { return mode == M_NUM ? ".,-/" : ".,?!"; }

    private static void setMode(int m) {
        finishComposing();
        mode = m;
        rebuildKeys();
    }

    /** The app may clear/replace the text by itself (e.g. 카톡 보내기) without us noticing.
     *  If the text before the cursor no longer ends with what we think is composing, drop the state. */
    private static boolean needsSync;

    private static void syncComposing() {
        if (!needsSync && SystemClock.uptimeMillis() - cjLastEdit < 500) return;   // typing fast: trust our state
        needsSync = false;
        try {
            InputConnection ic = svc == null ? null : svc.getCurrentInputConnection();
            if (ic == null) return;
            if (nara.isComposing() && !before(ic, nara.composing())) nara.reset();
            if (cj.isComposing() && !before(ic, cj.composing())) cj.reset();
            Field f = field(svc.getClass(), "composer");
            Object hc = f == null ? null : f.get(svc);
            if (hc != null) {
                if ((Boolean) hc.getClass().getMethod("isComposing").invoke(hc)) {
                    String c = (String) hc.getClass().getMethod("composing").invoke(hc);
                    if (c != null && !c.isEmpty() && !before(ic, c)) hc.getClass().getMethod("reset").invoke(hc);
                }
            }
        } catch (Throwable t) { android.util.Log.e("ThockExt", "sync", t); }
    }

    private static boolean before(InputConnection ic, String comp) {
        if (comp.isEmpty()) return true;
        CharSequence b = ic.getTextBeforeCursor(comp.length(), 0);
        if (b == null) return true;              // editor can't tell: keep state
        return b.toString().equals(comp);
    }

    private static void cjPress(int k, String text) {
        syncComposing();
        if (k != K_PUNCT) lastPunct = false;
        switch (k) {
            case K_I: cjApply(cj.vowel(CjEngine.I)); break;
            case K_A: cjApply(cj.vowel(CjEngine.A)); break;
            case K_EU: cjApply(cj.vowel(CjEngine.EU)); break;
            case K_BS:
                if (mode == M_NARA && nara.backspace()) naraApply("");
                else if (cj.backspace()) cjApply("");
                else call(mDoBackspace);
                break;
            case K_N_CONS: naraApply(nara.consonant(text.charAt(0))); break;
            case K_N_AE: naraApply(nara.key(NaraEngine.AE)); break;
            case K_N_OU: naraApply(nara.key(NaraEngine.OU)); break;
            case K_N_I: naraApply(nara.key(NaraEngine.I)); break;
            case K_N_EU: naraApply(nara.key(NaraEngine.EU)); break;
            case K_N_STROKE: naraApply(nara.key(NaraEngine.STROKE)); break;
            case K_N_DOUBLE: naraApply(nara.key(NaraEngine.DOUBLE)); break;
            case K_COMMA: finishComposing(); cjCommitRaw(","); break;
            case K_SPACE:
                if (mode == M_KO && cj.isComposing() && cj.lastWasConsonant()) {
                    cjFinish();           // 1st space right after a consonant = separator (삼성 천지인 방식)
                } else {
                    finishComposing();
                    cjCommitRaw(" ");
                }
                break;
            case K_PUNCT: {
                String set = punctSet();
                if (lastPunct) {
                    InputConnection ic = svc.getCurrentInputConnection();
                    if (ic != null) ic.deleteSurroundingText(1, 0);
                    punctIdx = (punctIdx + 1) % set.length();
                } else {
                    finishComposing();
                    punctIdx = 0;
                }
                cjCommitRaw(String.valueOf(set.charAt(punctIdx)));
                lastPunct = true;
                break;
            }
            case K_TEXT: {
                finishComposing();
                String t = text;
                cjCommitRaw(t);
                if ((mode == M_EN || mode == M_KOQ) && shiftState == 1) { shiftState = 0; refreshKeys(); }
                break;
            }
            case K_JAMO: {
                // 쿼티 한글: the app's own Hangul composer does the composition
                if (mTypeJamo == null) mTypeJamo = method(svc.getClass(), "typeJamo", char.class);
                call(mTypeJamo, text.charAt(0));
                if (shiftState == 1) { shiftState = 0; refreshKeys(); }
                break;
            }
            case K_SHIFT: {
                long now = SystemClock.uptimeMillis();
                if (shiftState == 0) shiftState = 1;
                else if (shiftState == 1) shiftState = now - lastShiftTap < 350 ? 2 : 0;
                else shiftState = 0;
                lastShiftTap = now;
                refreshKeys();
                break;
            }
            case K_TO_NUM: setMode(M_NUM); break;
            case K_TO_SYM: symPage = 0; setMode(M_SYM); break;
            case K_TO_KO: shiftState = 0; setMode(koMode()); break;
            case K_TO_EN: setMode(M_EN); break;
            case K_TO_ENSYM: symReturn = mode == M_KOQ ? M_KOQ : M_EN; enSymPage = 0; setMode(M_ENSYM); break;
            case K_PAGE:
                if (mode == M_SYM) symPage = (symPage + 1) % SYM_PAGES.length;
                else enSymPage = (enSymPage + 1) % EN_SYM_PAGES.length;
                rebuildKeys();
                break;
            case K_LANG:
                // goes through the app so its own language state stays in sync (renderRows -> onRender -> mode)
                finishComposing();
                shiftState = 0;
                call(method(svc.getClass(), "toggleLanguage"));
                break;
            case K_EMOJI: finishComposing(); call(method(svc.getClass(), "openEmoji")); break;
            case K_ENTER: finishComposing(); call(method(svc.getClass(), "pressEnter")); break;
            default:
                if (k >= 0 && k < CjEngine.CYCLES.length) cjApply(cj.consonant(k));
        }
    }

    private static final String POPUP = "\u0001popup";

    private static String longPressText(int k, String text) {
        if (k == K_COMMA) return POPUP;
        if (k >= K_N_CONS && k <= K_N_EU && text != null) {
            int i = java.util.Arrays.asList("ㄱ", "ㄴ", "ㅏㅓ", "ㄹ", "ㅁ", "ㅗㅜ", "ㅅ", "ㅇ", "ㅣ", "ㅡ").indexOf(text);
            if (i >= 0) return String.valueOf((i + 1) % 10);
        }
        switch (k) {
            case K_I: return "1";
            case K_A: return "2";
            case K_EU: return "3";
            case 0: return "4";
            case 1: return "5";
            case 2: return "6";
            case 3: return "7";
            case 4: return "8";
            case 5: return "9";
            case 6: return "0";
        }
        return null;
    }

    // feedback (reuses the keyboard's own switch sounds + haptics) ---------
    private static Object keyObj(String type) {
        try {
            Class<?> kt = Class.forName("com.thock.keyboard.KeyType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object t = Enum.valueOf((Class) kt, type);
            Class<?> kc = Class.forName("com.thock.keyboard.Key");
            return kc.getConstructor(kt, String.class, String.class, float.class, String.class, int.class)
                    .newInstance(t, "", "", 1f, "", 0);
        } catch (Throwable e) { return null; }
    }

    private static Object kChar, kSpace, kEnter, kSpecial;
    private static Method mPlaySound;

    private static boolean isLetterCode(int k) {
        return (k >= 0 && k < 7) || k == K_I || k == K_A || k == K_EU || k == K_TEXT || k == K_PUNCT
                || (k >= K_N_CONS && k <= K_N_EU) || k == K_COMMA || k == K_JAMO;
    }

    /** Sound + vibration run on their own thread so the UI thread only handles touches and text. */
    private static Handler FX;
    private static Field fHaptics;
    private static Method mHapOn, mHapMode, mPressAmp, mRelAmp, mPress, mRelease;

    private static Handler fx() {
        if (FX == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("thock-fx", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            t.start();
            FX = new Handler(t.getLooper());
        }
        return FX;
    }

    // ---- 매너모드에서도 키 소리 (설정 › 소리): play through the media stream while the phone is silent/vibrate
    private static boolean forcedMedia;

    private static void silentRoute(InputMethodService s) {
        try {
            boolean want = s.getSharedPreferences("thock_ext", Context.MODE_PRIVATE).getBoolean("silent_sound", false);
            if (!want && !forcedMedia) return;
            android.media.AudioManager am = (android.media.AudioManager) s.getSystemService(Context.AUDIO_SERVICE);
            boolean silent = am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL;
            Object eng = field(s.getClass(), "sound").get(s);
            Class<?> ec = eng.getClass();
            Field fUsage = field(ec, "currentUsage");
            int cur = fUsage.getInt(eng);
            if (want && silent) {
                if (cur == 1) { forcedMedia = true; return; }          // already media (e.g. earphones)
                Method build = ec.getDeclaredMethod("buildPool", int.class);
                build.setAccessible(true);
                Field fPool = field(ec, "pool");
                Object old = fPool.get(eng);
                fPool.set(eng, build.invoke(eng, 1));                 // USAGE_MEDIA: not muted by 매너모드
                fUsage.setInt(eng, 1);
                if (old != null) ((android.media.SoundPool) old).release();
                ((java.util.Map<?, ?>) field(ec, "ids").get(eng)).clear();
                ((java.util.Set<?>) field(ec, "loadedSets").get(eng)).clear();
                ((java.util.Set<?>) field(ec, "ready").get(eng)).clear();
                String sw = (String) field(ec, "lastSwitch").get(eng);
                boolean foam = field(ec, "lastFoam").getBoolean(eng);
                ec.getMethod("preload", String.class, boolean.class).invoke(eng, sw, foam);
                forcedMedia = true;
            } else if (forcedMedia) {
                forcedMedia = false;
                ec.getMethod("refreshRoute").invoke(eng);             // back to the app's normal routing
            }
        } catch (Throwable t) { android.util.Log.w("ThockExt", "silentRoute", t); }
    }

    private static void feedback(View v, int k, boolean down) {
        try {
            if (mPlaySound == null) {
                Class<?> kc = Class.forName("com.thock.keyboard.Key");
                mPlaySound = method(svc.getClass(), "playSound", kc, boolean.class);
                kChar = keyObj("CHAR"); kSpace = keyObj("SPACE"); kEnter = keyObj("ENTER"); kSpecial = keyObj("BACKSPACE");
                Class<?> pc = fPrefs.get(svc).getClass();
                mHapOn = pc.getMethod("getHaptic");
                mHapMode = pc.getMethod("getHapticMode");
                mPressAmp = pc.getMethod("getHapticPressAmp");
                mRelAmp = pc.getMethod("getHapticReleaseAmp");
                fHaptics = field(svc.getClass(), "haptics");
                Object h = fHaptics.get(svc);
                mPress = h.getClass().getMethod("press", int.class, int.class);
                mRelease = h.getClass().getMethod("release", int.class, int.class);
            }
        } catch (Throwable ignored) {}
        final Object key = isLetterCode(k) ? kChar : k == K_SPACE ? kSpace : k == K_ENTER ? kEnter : kSpecial;
        final InputMethodService s = svc;
        // mode 2 (system haptic) must be done on the view, on the UI thread
        try {
            Object p = fPrefs.get(s);
            if (down && (Boolean) mHapOn.invoke(p) && (Integer) mHapMode.invoke(p) == 2) v.performHapticFeedback(3);
        } catch (Throwable ignored) {}
        fx().post(() -> {
            try {
                if (down) silentRoute(s);
                if (mPlaySound != null && key != null) mPlaySound.invoke(s, key, down);
                Object p = fPrefs.get(s);
                if (!(Boolean) mHapOn.invoke(p)) return;
                int hm = (Integer) mHapMode.invoke(p);
                if (hm == 2) return;
                Object h = fHaptics.get(s);
                if (down) mPress.invoke(h, hm, (Integer) mPressAmp.invoke(p));
                else mRelease.invoke(h, hm, (Integer) mRelAmp.invoke(p));
            } catch (Throwable ignored) {}
        });
    }

    // ---------------------------------------------------------------- layouts (삼성 키보드 그대로)
    /** Key spec: label, code, weight, style(0 letter,1 function), hint, icon(-1 none). */
    private static final class KS {
        final String label; final int code; final float w; final int style; final String hint; final int icon;
        KS(String label, int code, float w, int style, String hint, int icon) {
            this.label = label; this.code = code; this.w = w; this.style = style; this.hint = hint; this.icon = icon;
        }
    }

    private static KS L(String label, int code, String hint) { return new KS(label, code, 1, 0, hint, -1); }
    private static KS T(String s) { return new KS(s, K_TEXT, 1, 0, null, -1); }
    private static KS F(String label, int code, float w) { return new KS(label, code, w, 1, null, -1); }
    private static KS I(int icon, int code, float w, int style) { return new KS("", code, w, style, null, icon); }
    private static KS SP() { return new KS("", 0, 0, -1, null, -1); } // half spacer

    private static final String[][][] SYM_PAGES = {
            {{"!", "?", ".", ",", "(", ")"}, {"@", ":", ";", "/", "-", "♡"}, {"*", "_", "%", "~", "^", "#"}},
            {{"+", "×", "÷", "=", "\"", "'"}, {"&", "♤", "☆", "♧", "\\", "₩"}, {"<", ">", "{", "}", "[", "]"}},
            {{"`", "|", "$", "€", "£", "¥"}, {"°", "○", "●", "□", "■", "◇"}, {"※", "《", "》", "¤", "¡", "¿"}},
    };
    private static final String[][][] EN_SYM_PAGES = {
            {{"+", "×", "÷", "=", "/", "_", "<", ">", "[", "]"}, {"!", "@", "#", "$", "%", "^", "&", "*", "(", ")"}, {"-", "'", "\"", ":", ";", ",", "?"}},
            {{"`", "~", "\\", "|", "{", "}", "€", "£", "¥", "₩"}, {"°", "•", "○", "●", "□", "■", "♤", "♡", "◇", "♧"}, {"☆", "▪", "¤", "《", "》", "¡", "¿"}},
    };

    private static List<KS[]> layout() {
        List<KS[]> rows = new ArrayList<>();
        switch (mode) {
            case M_KO:
                if (numRowOn()) rows.add(numberRow());
                rows.add(new KS[]{L("ㅣ", K_I, "1"), L("ㆍ", K_A, "2"), L("ㅡ", K_EU, "3"), I(LineIcon.BKSP, K_BS, 1, 1)});
                rows.add(new KS[]{L("ㄱㅋ", 0, "4"), L("ㄴㄹ", 1, "5"), L("ㄷㅌ", 2, "6"), I(LineIcon.ENTER, K_ENTER, 1, 1)});
                rows.add(new KS[]{L("ㅂㅍ", 3, "7"), L("ㅅㅎ", 4, "8"), L("ㅈㅊ", 5, "9"), F(".,?!", K_PUNCT, 1)});
                rows.add(new KS[]{F("!#1", K_TO_NUM, 0.5f), F("한/영", K_LANG, 0.5f), L("ㅇㅁ", 6, "0"), I(LineIcon.SPACEBAR, K_SPACE, 1, 1), I(LineIcon.SMILE, K_EMOJI, 1, 1)});
                break;
            case M_NARA:
                if (numRowOn()) rows.add(numberRow());
                rows.add(new KS[]{L("ㄱ", K_N_CONS, "1"), L("ㄴ", K_N_CONS, "2"), L("ㅏㅓ", K_N_AE, "3"), I(LineIcon.BKSP, K_BS, 1, 1)});
                rows.add(new KS[]{L("ㄹ", K_N_CONS, "4"), L("ㅁ", K_N_CONS, "5"), L("ㅗㅜ", K_N_OU, "6"), I(LineIcon.SPACEBAR, K_SPACE, 1, 1)});
                rows.add(new KS[]{L("ㅅ", K_N_CONS, "7"), L("ㅇ", K_N_CONS, "8"), L("ㅣ", K_N_I, "9"), F(",", K_COMMA, 0.5f), I(LineIcon.ENTER, K_ENTER, 0.5f, 1)});
                rows.add(new KS[]{F("획추가", K_N_STROKE, 1), L("ㅡ", K_N_EU, "0"), F("쌍자음", K_N_DOUBLE, 1), F("!#1", K_TO_NUM, 0.5f), F("한/영", K_LANG, 0.5f)});
                break;
            case M_NUM:
                rows.add(new KS[]{T("1"), T("2"), T("3"), I(LineIcon.BKSP, K_BS, 1, 1)});
                rows.add(new KS[]{T("4"), T("5"), T("6"), I(LineIcon.ENTER, K_ENTER, 1, 1)});
                rows.add(new KS[]{T("7"), T("8"), T("9"), F(".,-/", K_PUNCT, 1)});
                rows.add(new KS[]{F("!@#", K_TO_SYM, 0.5f), F("가", K_TO_KO, 0.5f), T("0"), I(LineIcon.SPACEBAR, K_SPACE, 1, 1), lastRightKey()});
                break;
            case M_SYM: {
                String[][] pg = SYM_PAGES[symPage];
                int[] right = {K_BS, K_ENTER, K_PUNCT};
                for (int r = 0; r < 3; r++) {
                    KS[] row = new KS[7];
                    for (int i = 0; i < 6; i++) row[i] = T(pg[r][i]);
                    row[6] = r == 0 ? I(LineIcon.BKSP, K_BS, 1, 1) : r == 1 ? I(LineIcon.ENTER, K_ENTER, 1, 1) : F(".,?!", K_PUNCT, 1);
                    rows.add(row);
                }
                rows.add(new KS[]{F("123", K_TO_NUM, 1), F("가", K_TO_KO, 1), new KS((symPage + 1) + "/" + SYM_PAGES.length, K_PAGE, 2, 0, null, -1),
                        I(LineIcon.SPACEBAR, K_SPACE, 2, 0), lastRightKey()});
                break;
            }
            case M_EN: {
                boolean up = shiftState > 0;
                if (qNumRowOn()) rows.add(numberRow());
                rows.add(digitHints(texts("qwertyuiop", up)));
                KS[] r2 = new KS[11];
                r2[0] = SP();
                String a = "asdfghjkl";
                for (int i = 0; i < 9; i++) r2[i + 1] = T(up ? a.substring(i, i + 1).toUpperCase() : a.substring(i, i + 1));
                r2[10] = SP();
                rows.add(r2);
                KS[] r3 = new KS[9];
                r3[0] = new KS("", K_SHIFT, 1.5f, shiftState > 0 ? 2 : 1, null, LineIcon.SHIFT);
                String z = "zxcvbnm";
                for (int i = 0; i < 7; i++) r3[i + 1] = T(up ? z.substring(i, i + 1).toUpperCase() : z.substring(i, i + 1));
                r3[8] = I(LineIcon.BKSP, K_BS, 1.5f, 1);
                rows.add(r3);
                rows.add(new KS[]{F("!#1", K_TO_ENSYM, 1.25f), F("한/영", K_LANG, 1), I(LineIcon.SMILE, K_EMOJI, 1, 1),
                        I(LineIcon.SPACEBAR, K_SPACE, 4.25f, 0), T("."), I(LineIcon.ENTER, K_ENTER, 1.5f, 1)});
                break;
            }
            case M_KOQ: {
                boolean up = shiftState > 0;
                if (qNumRowOn()) rows.add(numberRow());
                rows.add(digitHints(jamos(up ? "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖ" : "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ")));
                KS[] r2 = new KS[11];
                r2[0] = SP();
                String a = "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ";
                for (int i = 0; i < 9; i++) r2[i + 1] = new KS(a.substring(i, i + 1), K_JAMO, 1, 0, null, -1);
                r2[10] = SP();
                rows.add(r2);
                KS[] r3 = new KS[9];
                r3[0] = new KS("", K_SHIFT, 1.5f, shiftState > 0 ? 2 : 1, null, LineIcon.SHIFT);
                String z = "ㅋㅌㅊㅍㅠㅜㅡ";
                for (int i = 0; i < 7; i++) r3[i + 1] = new KS(z.substring(i, i + 1), K_JAMO, 1, 0, null, -1);
                r3[8] = I(LineIcon.BKSP, K_BS, 1.5f, 1);
                rows.add(r3);
                rows.add(new KS[]{F("!#1", K_TO_ENSYM, 1.25f), F("한/영", K_LANG, 1), I(LineIcon.SMILE, K_EMOJI, 1, 1),
                        I(LineIcon.SPACEBAR, K_SPACE, 4.25f, 0), T("."), I(LineIcon.ENTER, K_ENTER, 1.5f, 1)});
                break;
            }
            case M_ENSYM: {
                String[][] pg = EN_SYM_PAGES[enSymPage];
                rows.add(numberRow());
                rows.add(arr(pg[0]));
                rows.add(arr(pg[1]));
                KS[] r3 = new KS[9];
                r3[0] = F((enSymPage + 1) + "/" + EN_SYM_PAGES.length, K_PAGE, 1.5f);
                for (int i = 0; i < 7; i++) r3[i + 1] = T(pg[2][i]);
                r3[8] = I(LineIcon.BKSP, K_BS, 1.5f, 1);
                rows.add(r3);
                rows.add(new KS[]{symReturn == M_KOQ ? F("가", K_TO_KO, 1.25f) : F("ABC", K_TO_EN, 1.25f), F("한/영", K_LANG, 1), I(LineIcon.SMILE, K_EMOJI, 1, 1),
                        I(LineIcon.SPACEBAR, K_SPACE, 4.25f, 0), T("."), I(LineIcon.ENTER, K_ENTER, 1.5f, 1)});
                break;
            }
        }
        return rows;
    }

    private static final float NUM_ROW_H = 0.72f;   // number row height vs. a normal row
    private static final int K_NUMROW = 202;        // style marker (behaves like K_TEXT)

    private static KS[] numberRow() {
        KS[] r = new KS[10];
        String d = "1234567890";
        for (int i = 0; i < 10; i++) r[i] = new KS(d.substring(i, i + 1), K_TEXT, 1, 3, null, -1);
        return r;
    }

    private static boolean layoutHasNumRow;

    /** 숫자줄을 끈 쿼티: 맨 윗줄에 1~0 힌트, 길게 누르면 숫자 (삼성과 동일) */
    private static KS[] digitHints(KS[] r) {
        if (qNumRowOn()) return r;
        String d = "1234567890";
        for (int i = 0; i < r.length && i < 10; i++) r[i] = new KS(r[i].label, r[i].code, r[i].w, r[i].style, d.substring(i, i + 1), r[i].icon);
        return r;
    }

    private static KS[] texts(String s, boolean upper) {
        KS[] r = new KS[s.length()];
        for (int i = 0; i < s.length(); i++) { String c = s.substring(i, i + 1); r[i] = T(upper ? c.toUpperCase() : c); }
        return r;
    }

    private static KS[] jamos(String s) {
        KS[] r = new KS[s.length()];
        for (int i = 0; i < s.length(); i++) r[i] = new KS(s.substring(i, i + 1), K_JAMO, 1, 0, null, -1);
        return r;
    }

    /** 숫자판·특수문자 오른쪽 아래: 나랏글이면 ",", 아니면 이모지 (삼성과 동일) */
    private static KS lastRightKey() {
        return koMode() == M_NARA ? F(",", K_COMMA, 1) : I(LineIcon.SMILE, K_EMOJI, 1, 1);
    }

    private static KS[] arr(String[] a) {
        KS[] r = new KS[a.length];
        for (int i = 0; i < a.length; i++) r[i] = T(a[i]);
        return r;
    }

    private static View buildCj(Context c) {
        // Always exactly as tall as the original keyboard board (measured just before us in the same FrameLayout).
        LinearLayout p = new LinearLayout(c) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                int rh = rootRef != null ? rootRef.getMeasuredHeight() : 0;
                if (rh > 0) {
                    // number row = one extra row on top; the other rows keep their original size
                    if (layoutHasNumRow) rh += Math.round((rh - rootRef.getPaddingTop() - rootRef.getPaddingBottom()) / 4f * NUM_ROW_H) + dp(numGap());
                    rh += dp(bottomExtra());
                    hSpec = MeasureSpec.makeMeasureSpec(rh, MeasureSpec.EXACTLY);
                }
                super.onMeasure(wSpec, hSpec);
                if (getMeasuredHeight() > 0) lastCjH = getMeasuredHeight();
            }

            @Override
            protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                if (Boolean.TRUE.equals(lastSplit) && w != builtW) H.post(Ext::rebuildKeys);   // split rows depend on the real width
            }
        };
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(cBg);
        p.setClickable(true);
        cjPanelInner = p;
        rebuildKeys();
        return p;
    }

    private static LinearLayout cjPanelInner;

    private static Boolean lastSplit;
    private static int builtW = -1;

    /** 좌우 분리형 배열: same rule as the app (option on + screen 600dp or wider, e.g. Fold inner screen). */
    private static boolean splitOn() {
        try {
            Object pr = fPrefs.get(svc);
            boolean on = (Boolean) pr.getClass().getMethod("getSplitWhenWide").invoke(pr);
            return on && svc.getResources().getConfiguration().screenWidthDp >= 600;
        } catch (Throwable t) { return false; }
    }

    private static float wOf(KS k) { return k.style == -1 ? 0.5f : k.w; }
    private static KS gapKs(float w) { return new KS("", 0, w, -2, null, -1); }

    /**
     * 좌우 분리 (원본 Thock 방식): keys keep a fixed pitch, every row's left/right half is padded to the same
     * width on the gap side, and the centre gap takes the rest. A normal key stays whole on the side of its
     * centre; a wide key across the middle (space bar) becomes two keys that fill each half.
     */
    private static List<KS[]> splitRows(List<KS[]> rows, int panelW) {
        float density = svc.getResources().getDisplayMetrics().density;
        boolean land = svc.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        float pitch = (land ? 52f : 49.2f) * density;                 // 삼성 분할 키보드와 같은 키 간격
        int n = rows.size();
        List<List<KS>> L = new ArrayList<>(), R = new ArrayList<>();
        KS[] mids = new KS[n];
        float[] lw = new float[n], rw = new float[n], unit = new float[n];
        float halfPx = 0;
        for (int r = 0; r < n; r++) {
            KS[] row = rows.get(r);
            if (row.length > 0 && row[0].style == 1 && Math.abs(row[0].w - 1.25f) < 0.01f) {   // 삼성 분할: !#1 / 가 / ABC = 1칸
                row = row.clone();
                KS f = row[0];
                row[0] = new KS(f.label, f.code, 1f, f.style, f.hint, f.icon);
            }
            float total = 0;
            for (KS k : row) total += wOf(k);
            float mid = total / 2f, x = 0;
            List<KS> l = new ArrayList<>(), rr = new ArrayList<>();
            for (KS k : row) {
                float w = wOf(k), a = x, b = x + w;
                x = b;
                if (a < mid - 0.01f && b > mid + 0.01f && w >= 2f && k.style != -1) { mids[r] = k; continue; }
                if ((a + b) / 2f <= mid) l.add(k); else rr.add(k);
            }
            float sl = 0, sr = 0;
            for (KS k : l) sl += wOf(k);
            for (KS k : rr) sr += wOf(k);
            if (mids[r] != null) { sl += 1; sr += 1; }                   // each half of a split space ≥ 1 key
            L.add(l); R.add(rr); lw[r] = sl; rw[r] = sr;
            unit[r] = pitch * 10f / Math.max(1f, total);               // 10-wide rows = 1 pitch; 4-wide pads = 2.5
            halfPx = Math.max(halfPx, Math.max(sl, sr) * unit[r]);
        }
        float minGap = 16 * density * 2;
        if (panelW > 0 && halfPx * 2 + minGap > panelW) {               // too narrow: shrink everything evenly
            float f = (panelW - minGap) / (halfPx * 2);
            halfPx *= f;
            for (int r = 0; r < n; r++) unit[r] *= f;
        }
        // outer margin like the original (31.2dp portrait / 58.7dp landscape), never eating the minimum gap
        float side = Math.max(0, Math.min(32f * density, (panelW - halfPx * 2 - minGap) / 2f));   // 삼성: 양끝 약 32dp
        List<KS[]> out = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            float u = unit[r], half = halfPx / u, sd = side / u;
            float gap = Math.max(0.01f, (panelW > 0 ? panelW / u : half * 2 + 2) - half * 2 - sd * 2);
            List<KS> o = new ArrayList<>();
            if (sd > 0.01f) o.add(gapKs(sd));
            o.addAll(L.get(r));
            float sl = lw[r] - (mids[r] != null ? 1 : 0), sr = rw[r] - (mids[r] != null ? 1 : 0);
            KS m = mids[r];
            if (m != null) o.add(new KS(m.label, m.code, half - sl, m.style, m.hint, m.icon));
            else if (half - sl > 0.01f) o.add(gapKs(half - sl));
            o.add(gapKs(gap));
            if (m != null) o.add(new KS(m.label, m.code, half - sr, m.style, m.hint, m.icon));
            else if (half - sr > 0.01f) o.add(gapKs(half - sr));
            o.addAll(R.get(r));
            if (sd > 0.01f) o.add(gapKs(sd));
            out.add(o.toArray(new KS[0]));
        }
        return out;
    }

    private static void rebuildKeys() {
        LinearLayout p = cjPanelInner;
        if (p == null || svc == null) return;
        Context c = svc;
        p.removeAllViews();
        cjKeys.clear();
        boolean qwerty = mode == M_EN || mode == M_ENSYM || mode == M_KOQ;
        boolean hadNum = layoutHasNumRow;
        layoutHasNumRow = false;
        boolean split = splitOn();
        lastSplit = split;
        List<KS[]> src = layout();
        int panelW = panelWidth(p, split);
        builtW = p.getWidth();
        if (split && (p.getPaddingLeft() != 0 || p.getPaddingRight() != 0)) p.setPadding(0, p.getPaddingTop(), 0, p.getPaddingBottom());
        List<KS[]> built = split ? splitRows(src, panelW) : src;
        for (int ri = 0; ri < src.size(); ri++) {
            KS[] row = built.get(ri);
            LinearLayout r = row(c);
            boolean isNum = src.get(ri).length > 0 && src.get(ri)[0].style == 3;
            boolean shortBar = isNum && (mode == M_KO || mode == M_NARA);      // 천지인: extra short bar on top
            if (shortBar) layoutHasNumRow = true;
            for (KS k : row) {
                if (k.style == -1) { r.addView(new View(c), new LinearLayout.LayoutParams(0, -1, 0.5f)); continue; }
                if (k.style == -2) { r.addView(new View(c), new LinearLayout.LayoutParams(0, -1, k.w)); continue; }
                View v = keyView(c, k, qwerty);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, k.w);
                int m = (qwerty || isNum) ? dp(2.5f) : dp(3.5f);
                int mv = isNum ? dp(3f) : dp(3.5f);
                lp.setMargins(m, mv, m, mv);
                r.addView(v, lp);
                cjKeys.add(v);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, shortBar ? NUM_ROW_H : 1f);
            if (shortBar) rlp.bottomMargin = dp(numGap());
            p.addView(r, rlp);
        }
        if (hadNum != layoutHasNumRow) p.requestLayout();
        else if (p.getWidth() > 0 && p.getHeight() > 0) {
            // lay the new keys out now, so a key pressed before the next frame still finds its view
            p.measure(View.MeasureSpec.makeMeasureSpec(p.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(p.getHeight(), View.MeasureSpec.EXACTLY));
            p.layout(p.getLeft(), p.getTop(), p.getRight(), p.getBottom());
        }
        deepNoFocus(p);
        if (mode == M_KOQ || mode == M_EN) H.post(() -> { try { if (!cjKeys.isEmpty() && cjKeys.get(0).getWidth() > 0) calibGeo(); } catch (Throwable ignored) {} });   // 키 비율 갱신
    }

    private static int panelWidth(LinearLayout p, boolean split) {
        int panelW = p.getWidth() > 0 ? p.getWidth() : (stackRef != null && stackRef.getWidth() > 0 ? stackRef.getWidth() : svc.getResources().getDisplayMetrics().widthPixels);
        if (!split) panelW -= p.getPaddingLeft() + p.getPaddingRight();
        return panelW;
    }

    /**
     * Shift on/off: same keys, new labels. Update the existing views instead of rebuilding them -
     * fresh views have no size until the next layout pass, so a key pressed right after would be lost.
     */
    private static void refreshKeys() {
        LinearLayout p = cjPanelInner;
        if (p == null || svc == null) return;
        try {
            boolean split = Boolean.TRUE.equals(lastSplit);
            List<KS[]> src = layout();
            List<KS[]> built = split ? splitRows(src, panelWidth(p, split)) : src;
            List<KS> flat = new ArrayList<>();
            for (KS[] row : built) for (KS k : row) if (k.style != -1 && k.style != -2) flat.add(k);
            if (flat.size() != cjKeys.size()) { rebuildKeys(); return; }
            for (int i = 0; i < flat.size(); i++) {
                Object t = cjKeys.get(i).getTag();
                if (!(t instanceof KS) || ((KS) t).code != flat.get(i).code || (((KS) t).icon >= 0) != (flat.get(i).icon >= 0)) { rebuildKeys(); return; }
            }
            for (int i = 0; i < flat.size(); i++) {
                View v = cjKeys.get(i);
                KS o = (KS) v.getTag(), n = flat.get(i);
                if (v instanceof FrameLayout && ((FrameLayout) v).getChildCount() > 0) {
                    View c0 = ((FrameLayout) v).getChildAt(0);
                    if (c0 instanceof TextView && !String.valueOf(n.label).equals(o.label)) ((TextView) c0).setText(n.label);
                    if (c0 instanceof android.widget.ImageView && n.icon == LineIcon.SHIFT)
                        ((android.widget.ImageView) c0).setImageDrawable(new LineIcon(shiftState == 2 ? LineIcon.SHIFT_LOCK : LineIcon.SHIFT, cText));
                }
                v.setSelected(n.style == 2);
                v.setTag(n);
            }
        } catch (Throwable t) {
            android.util.Log.w("ThockExt", "refreshKeys", t);
            rebuildKeys();
        }
    }

    private static android.graphics.Typeface faceLight, faceNormal;

    /** Samsung keys use a light weight for letters and regular for function labels. */
    private static android.graphics.Typeface keyFace(boolean letter) {
        if (faceLight == null) {
            // Same key font as Samsung Keyboard (SECKeypad), read from the installed Samsung Keyboard app.
            try {
                Context sc = svc.createPackageContext("com.samsung.android.honeyboard", 0);
                android.graphics.Typeface sec = android.graphics.Typeface.createFromAsset(sc.getAssets(), "fonts/SECKeypad-Regular.ttf");
                if (sec != null) { faceLight = sec; faceNormal = sec; return sec; }
            } catch (Throwable t) { android.util.Log.w("ThockExt", "SECKeypad font", t); }
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                faceLight = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 300, false);
                faceNormal = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400, false);
            } else {
                faceLight = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL);
                faceNormal = android.graphics.Typeface.DEFAULT;
            }
        }
        return letter ? faceLight : faceNormal;
    }

    private static View keyView(Context c, KS k, boolean qwerty) {
        FrameLayout f = new FrameLayout(c);
        int base = (k.style == 0 || k.style == 3) ? cKey : cSpecial;
        f.setBackground(bg(base));
        if (k.style == 2) f.setSelected(true); // shift on
        if (intField("keyElevationDp", 0) > 0) f.setElevation(dp(1));
        if (k.icon >= 0) {
            android.widget.ImageView iv = new android.widget.ImageView(c);
            iv.setImageDrawable(new LineIcon(k.icon, cText));
            if (k.icon == LineIcon.SHIFT && shiftState == 2) iv.setImageDrawable(new LineIcon(LineIcon.SHIFT_LOCK, cText));
            int sz = dp(qwerty ? 25 : 28);
            f.addView(iv, new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER));
        } else {
            TextView t = new TextView(c);
            t.setText(k.label);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(cText);
            t.setIncludeFontPadding(false);
            float sp;
            if (k.style == 3) sp = 19;
            else if (k.code == K_A) sp = 30;
            else if (k.style == 1) sp = k.label.length() >= 3 ? 16 : 17;
            else if (qwerty) sp = 22;
            else if (mode == M_KO || mode == M_NARA) sp = 23;
            else sp = k.label.length() > 1 ? 18 : 24;
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTypeface(keyFace(k.style == 0 || k.style == 3));
            if ("획추가".equals(k.label) || "쌍자음".equals(k.label)) t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            if ("한/영".equals(k.label)) { t.setTextSize(TypedValue.COMPLEX_UNIT_SP, qwerty ? 12 : 14); t.setSingleLine(true); }
            f.addView(t, new FrameLayout.LayoutParams(-1, -1));
        }
        if (k.hint != null) {
            TextView h = new TextView(c);
            h.setText(k.hint);
            h.setTextColor(cHint);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            hl.setMargins(0, dp(3), dp(7), 0);
            f.addView(h, hl);
        }
        attachKey(f, k);
        return f;
    }

    private static android.widget.PopupWindow commaPopup;

    private static void showCommaPopup(View anchor) {
        try {
            if (commaPopup != null) commaPopup.dismiss();
            Context c = svc;
            String[][] rows = {{"-", "/", ":", "."}, {",", "?", "!", "'", "$"}};
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(6), dp(6), dp(6), dp(6));
            GradientDrawable bgd = shape(cKey);
            bgd.setStroke(dp(1), cSpecial);
            box.setBackground(bgd);
            box.setElevation(dp(6));
            for (String[] r : rows) {
                LinearLayout rr = row(c);
                for (String sym : r) {
                    TextView t = btn(c, sym, 20, cKey, () -> {
                        finishComposing();
                        cjCommitRaw(sym);
                        if (commaPopup != null) commaPopup.dismiss();
                    });
                    rr.addView(t, new LinearLayout.LayoutParams(dp(52), dp(50)));
                }
                box.addView(rr);
            }
            commaPopup = new android.widget.PopupWindow(box, -2, -2, true);
            commaPopup.setOutsideTouchable(true);
            commaPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            int x = Math.max(dp(4), loc[0] + anchor.getWidth() - box.getMeasuredWidth());
            int y = loc[1] - box.getMeasuredHeight() - dp(6);
            commaPopup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, Math.max(0, y));
        } catch (Throwable t) { android.util.Log.w("ThockExt", "commaPopup", t); }
    }

    private static String lpText(KS k) {
        if (k.hint != null && (k.code == K_TEXT || k.code == K_JAMO)) return k.hint;   // 쿼티 윗줄 숫자
        return longPressText(k.code, k.label);
    }

    private static boolean spaceCursor;
    private static float spaceX0, spaceDx, spaceY0, spaceDy;
    /** 상하 이동도 켬 (설정 › 커서 이동) */
    private static boolean spaceVertical() { return optOff("space_vertical"); }

    /** 스페이스바 커서 이동 on/off, 계속 이동(아이폰식) on/off */
    private static boolean spaceCursorOn() { return opt("space_cursor"); }
    /** 기본은 삼성식(끈 거리만큼). 예전 기본값(계속 이동)이 너무 빨라서 새 키로 바꿈 */
    private static boolean spaceJoystick() { return optOff("space_joystick2"); }
    /** 글자 키 위에서 좌우로 밀어 커서 이동 (삼성 키보드처럼) */
    private static boolean keyCursorOn() { return opt("key_cursor"); }
    /** 키보드 폭 (커서 이동 거리 기준) */
    private static float kbW() { return svc == null ? 1080 : svc.getResources().getDisplayMetrics().widthPixels; }
    /** 커서 이동 속도 배율 (1.0 = 기본) */
    private static float cursorSpeed() { return svc == null ? 1f : Math.max(1, Math.min(20, prefs().getInt("cursor_speed10", 10))) / 10f; }

    /**
     * 아이폰식: 누른 채로 있으면 계속 움직임. 처음 누른 곳에서 조금 벗어나면 천천히,
     * 멀리 벗어날수록 빠르게 (손을 뗄 때까지).
     */
    private static final Runnable cursorRepeat = new Runnable() {
        @Override public void run() {
            if (!spaceCursor) return;
            float dz = dp(14), dzv = dp(20);
            float a = Math.abs(spaceDx), av = spaceVertical() ? Math.abs(spaceDy) : 0;
            float dens = svc.getResources().getDisplayMetrics().density;
            if (av > dzv && av - dzv > a - dz) {            // 상하
                cursorStep(spaceDy > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
                float over = (av - dzv) / dens;
                long iv = (long) Math.max(70, Math.min(320, 320 - over * 5f));    // lines: slower than characters
                H.postDelayed(this, Math.max(15, (long) (iv / cursorSpeed())));
            } else if (a > dz) {
                cursorStep(spaceDx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                float over = (a - dz) / dens;   // dp past the dead zone
                long iv = (long) Math.max(25, Math.min(180, 180 - over * 3.2f));
                H.postDelayed(this, Math.max(8, (long) (iv / cursorSpeed())));
            } else {
                H.postDelayed(this, 30);
            }
        }
    };

    /** 스페이스바 커서 이동 한 칸 + 짧은 진동 (한 칸씩 알기 쉽게) */
    private static android.os.Vibrator vib;
    private static void cursorStep(int code) {
        arrow(code);
        if (!hapticOn()) return;
        try {
            if (vib == null) vib = (android.os.Vibrator) svc.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null) vib.vibrate(android.os.VibrationEffect.createOneShot(14, 170));
        } catch (Throwable ignored) {}
    }

    private static void stopSpaceCursor() {
        spaceCursor = false;
        spaceDx = 0;
        spaceDy = 0;
        H.removeCallbacks(cursorRepeat);
    }

    private static void attachKey(View f, KS k) {
        f.setTag(k);
        final boolean[] fired = {false};
        final boolean[] keyCursor = {false};       // 글자 키를 밀어서 커서 이동 중
        final float[] downY = {0};
        final CursorDrag drag = new CursorDrag();
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> { cjPress(K_BS, null); Typo.reset(); Spell.beforeKey(spellHost, false); Calib.noteBackspaceRepeat(); H.postDelayed(repeat[0], 45); };
        final Runnable longPress = () -> {
            Typo.reset();
            Spell.beforeKey(spellHost, false);
            KS kk = (KS) f.getTag();
            if (kk.code == K_SPACE) {            // 스페이스 길게: 커서 이동 모드
                fired[0] = true;
                spaceCursor = true;
                drag.activate(touchRawX);
                finishComposing();
                if (spaceJoystick()) { H.removeCallbacks(cursorRepeat); H.post(cursorRepeat); }
                if (hapticOn()) f.performHapticFeedback(0);
                return;
            }
            String lt = lpText(kk);
            if (lt == null) return;
            fired[0] = true;
            if (POPUP.equals(lt)) { showCommaPopup(f); return; }
            finishComposing();
            cjCommitRaw(lt);
            if ((kk.code == K_TEXT || kk.code == K_JAMO) && shiftState == 1) { shiftState = 0; refreshKeys(); }
            if (hapticOn()) f.performHapticFeedback(0);
        };
        f.setOnTouchListener((v, e) -> {
            KS kk = (KS) f.getTag();
            final int code = kk.code;
            if (calibTraining()) {
                // 따라 쓰기: 글자를 넣지 않고 "어느 키가 어디서 눌렸는지"만 설정 화면에 알려준다
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        feedback(v, code, true);
                        try { Calib.trainer.onKey(calibId(kk), touchRawX, touchRawY, calibGeo()); }
                        catch (Throwable t) { android.util.Log.w("ThockExt", "train key", t); }
                        break;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        feedback(v, code, false);
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        break;
                }
                return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    fired[0] = false;
                    keyCursor[0] = false;
                    drag.start(touchRawX, e.getEventTime());
                    downY[0] = touchRawY;
                    v.setPressed(true);
                    feedback(v, code, true);
                    typoTouch(kk, v);
                    calibNoteDown(kk, v);
                    if (code == K_BS) {
                        if (Spell.undo(spellHost, svc)) Calib.discardPending();
                        else if (Typo.undo(typoHost, svc)) Calib.discardPending();
                        else {          // 고친 직후의 지우기 = 되돌리기, 아니면 보통 지우기
                            Spell.beforeKey(spellHost, false);
                            calibNoteTyped(K_BS);
                            cjPress(K_BS, null);
                            Typo.backspace();
                            if (mode == M_KOQ) Typo.schedule(typoHost); else Typo.reset();
                        }
                        fired[0] = true;
                        H.postDelayed(repeat[0], 400);
                    } else if (code == K_SHIFT) {
                        cjPress(K_SHIFT, null);
                        fired[0] = true;
                    } else if (code == K_SPACE) {
                        stopSpaceCursor();
                        spaceX0 = touchRawX;
                        spaceY0 = touchRawY;
                        if (spaceCursorOn()) H.postDelayed(longPress, 400);
                    } else if (lpText(kk) != null) {
                        H.postDelayed(longPress, 400);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (code == K_SPACE && spaceCursorOn()) {
                        float dx = touchRawX - spaceX0;
                        boolean vert = spaceVertical();
                        float dy = vert ? touchRawY - spaceY0 : 0;
                        boolean joy = spaceJoystick();
                        if (!spaceCursor && (Math.abs(dx) > dp(14) || Math.abs(dy) > dp(20))) {
                            H.removeCallbacks(longPress);
                            spaceCursor = true;
                            fired[0] = true;
                            finishComposing();
                            if (joy) { H.removeCallbacks(cursorRepeat); H.post(cursorRepeat); }
                            else { spaceX0 = touchRawX; spaceY0 = touchRawY; dx = 0; dy = 0; drag.activate(touchRawX); }
                        }
                        if (spaceCursor && joy) {
                            spaceDx = dx;                 // 처음 누른 곳 기준 거리 → 속도
                            spaceDy = dy;
                        } else if (spaceCursor) {
                            float w = kbW();
                            int n = drag.moveActive(touchRawX, w, cursorSpeed());
                            for (; n > 0; n--) cursorStep(KeyEvent.KEYCODE_DPAD_RIGHT);
                            for (; n < 0; n++) cursorStep(KeyEvent.KEYCODE_DPAD_LEFT);
                            if (vert) {
                                float vs = dp(28) / cursorSpeed();      // one line per ~28dp
                                while (dy >= vs) { cursorStep(KeyEvent.KEYCODE_DPAD_DOWN); spaceY0 += vs; dy -= vs; }
                                while (dy <= -vs) { cursorStep(KeyEvent.KEYCODE_DPAD_UP); spaceY0 -= vs; dy += vs; }
                            }
                        }
                    } else if (isLetterCode(code) && keyCursorOn() && (!fired[0] || keyCursor[0])) {
                        // 글자 키 위에서 좌우로 밀기: 충분히 밀면 글자는 안 넣고 커서 이동 (삼성 키보드처럼)
                        float w = kbW(), ax = Math.abs(touchRawX - drag.origin()), ay = Math.abs(touchRawY - downY[0]);
                        if (!keyCursor[0] && ax > dp(12)) H.removeCallbacks(longPress);   // 밀기 시작하면 길게 누르기 취소
                        if (!keyCursor[0] && ay > ax) return true;                        // 위아래로 더 많이 움직이면 시작 안 함
                        int n = drag.moveKey(touchRawX, e.getEventTime(), w, cursorSpeed());
                        if (!keyCursor[0] && drag.active()) {
                            keyCursor[0] = true;
                            fired[0] = true;
                            H.removeCallbacks(longPress);
                            v.setPressed(false);
                            Typo.reset();
                            Spell.beforeKey(spellHost, false);
                            Calib.discardPending();
                            finishComposing();
                            if (hapticOn()) v.performHapticFeedback(0);
                        }
                        for (; n > 0; n--) cursorStep(KeyEvent.KEYCODE_DPAD_RIGHT);
                        for (; n < 0; n++) cursorStep(KeyEvent.KEYCODE_DPAD_LEFT);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    H.removeCallbacks(longPress);
                    H.removeCallbacks(repeat[0]);
                    if (!fired[0]) {
                        boolean bd = typoBoundary(code, kk.label);
                        Spell.beforeKey(spellHost, bd);
                        int seq = Typo.fixSeq;
                        typoBefore(code, kk.label);
                        cjPress(code, kk.label);
                        // 맞춤법: 경계 문자가 실제로 들어간 뒤, 모든 한글 자판에서 (고정 바꾸기·오타 교정이 방금 바꿨으면 생략)
                        if (bd && svc != null) Spell.afterBoundary(spellHost, svc, Typo.fixSeq != seq);
                        typoAfter(code, kk.label);
                        calibNoteTyped(code);
                    }
                    else if (code != K_BS && code != K_SHIFT) Typo.reset();
                    if (code == K_SPACE) stopSpaceCursor();
                    feedback(v, code, false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    H.removeCallbacks(longPress);
                    H.removeCallbacks(repeat[0]);
                    if (code == K_SPACE) stopSpaceCursor();
                    return true;
            }
            return true;
        });
    }
}
