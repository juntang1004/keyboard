package com.thock.keyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Redesigned settings screen (replaces MainActivity.buildUi). Orange accent, one page per category. */
public final class SettingsUi {
    private final Activity a;
    private final Prefs prefs;
    private final SoundEngine sound;
    private final Haptics haptics;
    private final boolean dark;

    private final int ACCENT;
    private static final int BURGUNDY = Color.parseColor("#7D2F3E");
    private static final int CREAM = Color.parseColor("#F6E6CF");
    private final int bg, card, text, sub, line, soft, chip;

    private final FrameLayout root;
    private final List<String> stack = new ArrayList<>();
    private Object backCallback;
    private String lastImeStatus = "";
    private String switchCat;

    // ------------------------------------------------------------------ entry
    public static View build(Activity act) {
        try {
            return new SettingsUi(act).root;
        } catch (Throwable t) {
            android.util.Log.e("ThockSettings", "build", t);
            TextView tv = new TextView(act);
            tv.setText("설정 화면 오류: " + t);
            return tv;
        }
    }

    private SettingsUi(Activity act) {
        a = act;
        prefs = (Prefs) fieldOf(act, "prefs", () -> new Prefs(act));
        sound = (SoundEngine) fieldOf(act, "sound", () -> new SoundEngine(act));
        haptics = new Haptics(act);
        dark = (act.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ACCENT = Color.parseColor(dark ? "#C4566B" : "#7D2F3E");
        bg = Color.parseColor(dark ? "#121011" : "#F5F2F0");
        card = Color.parseColor(dark ? "#1E1A1B" : "#FFFFFF");
        text = Color.parseColor(dark ? "#F4EFEE" : "#1E1A1B");
        sub = Color.parseColor(dark ? "#A39A9B" : "#7A7173");
        line = Color.parseColor(dark ? "#2D2728" : "#EEE8E6");
        soft = Color.parseColor(dark ? "#3A1F26" : "#F8ECDF");
        chip = Color.parseColor(dark ? "#2A2425" : "#ECE6E3");

        root = new FrameLayout(a);
        root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top = in.getSystemWindowInsetTop(), bottom = in.getSystemWindowInsetBottom();
            v.setPadding(in.getSystemWindowInsetLeft(), top, in.getSystemWindowInsetRight(), bottom);
            return in.consumeSystemWindowInsets();
        });
        styleWindow();
        root.getViewTreeObserver().addOnWindowFocusChangeListener(f -> {
            // only refresh when the keyboard on/selected status really changed (e.g. back from system settings)
            if (!f || !current().equals("home")) return;
            String st = imeEnabled() + "/" + imeDefault();
            if (!st.equals(lastImeStatus)) { lastImeStatus = st; show("home", false); }
        });
        lastImeStatus = imeEnabled() + "/" + imeDefault();
        show("home", false);
        updateBack();
    }

    interface Maker { Object make(); }

    private static Object fieldOf(Object o, String name, Maker fallback) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(o);
            if (v != null) return v;
        } catch (Throwable ignored) {}
        return fallback.make();
    }

    private void styleWindow() {
        try {
            if (a.getActionBar() != null) a.getActionBar().hide();
            Window w = a.getWindow();
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
            int flags = w.getDecorView().getSystemUiVisibility();
            if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ navigation
    private String current() { return stack.isEmpty() ? "home" : stack.get(stack.size() - 1); }

    private void open(String page) { stack.add(page); show(page, true); updateBack(); }

    private void back() {
        if (!stack.isEmpty()) stack.remove(stack.size() - 1);
        show(current(), true);
        updateBack();
    }

    /** Back: sub page -> previous page, home -> ask before closing. Always registered. */
    private void updateBack() {
        if (Build.VERSION.SDK_INT < 33 || backCallback != null) return;
        try {
            android.window.OnBackInvokedCallback cb = () -> { if (!stack.isEmpty()) back(); else confirmExit(); };
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        } catch (Throwable ignored) {}
    }

    private android.app.AlertDialog exitDialog;

    private void confirmExit() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        exitDialog = new android.app.AlertDialog.Builder(a, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("설정을 닫을까요?")
                .setMessage("바꾼 설정은 이미 저장돼 있어요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("닫기", (d, w) -> a.finish())
                .create();
        exitDialog.setOnShowListener(d -> {
            exitDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(ACCENT);
            exitDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(sub);
        });
        exitDialog.show();
    }

    private void show(String page, boolean animate) {
        // In-place refresh (animate=false): keep scroll position, applied before the first frame is drawn.
        final int keepY = !animate && pageScroll != null && page.equals(current()) ? pageScroll.getScrollY() : 0;
        if (!"calib_train".equals(page)) Calib.stopTraining(a);   // 따라 쓰기 화면을 벗어나면 키보드를 바로 원래대로
        View v;
        switch (page) {
            case "typo": v = pageTypo(); break;
            case "typo_words": v = pageTypoWords(); break;
            case "typo_rules": v = pageTypoRules(); break;
            case "typo_learned": v = pageTypoLearned(); break;
            case "spell": v = pageSpell(); break;
            case "calib": v = pageCalib(); break;
            case "calib_train": v = pageCalibTrain(); break;
            case "calib_result": v = pageCalibResult(); break;
            case "calib_detail": v = pageCalibDetail(); break;
            case "switch": v = pageSwitch(); break;
            case "sound": v = pageSound(); break;
            case "haptic": v = pageHaptic(); break;
            case "layout": v = pageLayout(); break;
            case "theme": v = pageTheme(); break;
            case "clip": v = pageClip(); break;
            default: v = pageHome();
        }
        root.removeAllViews();
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        if (keepY > 0 && pageScroll != null) {
            final ScrollView sv = pageScroll;
            sv.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    sv.getViewTreeObserver().removeOnPreDrawListener(this);
                    sv.scrollTo(0, keepY);
                    return true;
                }
            });
        }
        if (animate) { v.setAlpha(0f); v.setTranslationX(dp(16)); v.animate().alpha(1f).translationX(0).setDuration(160).start(); }
    }

    // ------------------------------------------------------------------ primitives
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics()); }

    private GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(r));
        return d;
    }

    private GradientDrawable stroke(int fill, int strokeColor, float r, float w) {
        GradientDrawable d = round(fill, r);
        d.setStroke(dp(w), strokeColor);
        return d;
    }

    private void ripple(View v, android.graphics.drawable.Drawable base, float r) {
        int rc = dark ? Color.parseColor("#33FFFFFF") : Color.parseColor("#1A000000");
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(rc), base, round(Color.BLACK, r)));
        v.setClickable(true);
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private LinearLayout col() { LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }

    private LinearLayout.LayoutParams mlp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    /** Page scaffold: header (with back if sub page) + scrolling body. */
    private LinearLayout scaffold(String title, String subtitle, boolean sub_) {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout body = col();
        body.setPadding(dp(18), dp(sub_ ? 6 : 28), dp(18), dp(32));
        sv.addView(body);
        if (sub_) {
            android.widget.ImageView b = icon(LineIcon.BACK, text, 10);
            ripple(b, round(Color.TRANSPARENT, 22), 22);
            b.setOnClickListener(v -> back());
            body.addView(b, mlp(dp(44), dp(44), -8, 0, 0, 4));
        }
        TextView h = tv(title, sub_ ? 26 : 30, text, true);
        body.addView(h, mlp(-1, -2, 4, 0, 4, 0));
        if (subtitle != null) body.addView(tv(subtitle, 14, sub, false), mlp(-1, -2, 4, 4, 4, 0));
        root.setTag(sv);
        pageScroll = sv;
        return body;
    }

    private ScrollView pageScroll;

    private LinearLayout card(LinearLayout parent, String label) {
        if (label != null) {
            TextView l = tv(label, 13, sub, true);
            parent.addView(l, mlp(-1, -2, 8, 22, 8, 8));
        }
        LinearLayout c = col();
        c.setBackground(round(card, 22));
        c.setPadding(dp(6), dp(6), dp(6), dp(6));
        parent.addView(c, mlp(-1, -2, 0, label == null ? 18 : 0, 0, 0));
        return c;
    }

    private void divider(LinearLayout c) {
        View v = new View(a);
        v.setBackgroundColor(line);
        c.addView(v, mlp(-1, 1, 16, 0, 16, 0));
    }

    private android.widget.ImageView icon(int type, int color, float padDp) {
        android.widget.ImageView iv = new android.widget.ImageView(a);
        iv.setImageDrawable(new LineIcon(type, color));
        iv.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        return iv;
    }

    private View iconBubble(int type) {
        android.widget.ImageView i = icon(type, ACCENT, 10);
        i.setBackground(round(soft, 14));
        return i;
    }

    /** Menu row: icon, title, summary, chevron. */
    private View navRow(LinearLayout c, int iconType, String title, String summary, Runnable go) {
        LinearLayout r = row();
        r.setPadding(dp(12), dp(12), dp(12), dp(12));
        ripple(r, round(Color.TRANSPARENT, 18), 18);
        r.addView(iconBubble(iconType), mlp(dp(42), dp(42), 0, 0, 14, 0));
        LinearLayout t = col();
        t.addView(tv(title, 16, text, true));
        if (summary != null && !summary.isEmpty()) {
            TextView s = tv(summary, 13, sub, false);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(s, mlp(-1, -2, 0, 2, 0, 0));
        }
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(icon(LineIcon.CHEVRON, sub, 0), mlp(dp(20), dp(20), 8, 0, 4, 0));
        r.setOnClickListener(v -> go.run());
        c.addView(r);
        return r;
    }

    private Switch toggle(boolean on) {
        Switch s = new Switch(a);
        s.setChecked(on);
        // Own drawables: the platform track is drawn semi-transparent, which made "on" look washed out.
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        GradientDrawable on_ = round(ACCENT, 14);
        on_.setSize(dp(46), dp(28));
        GradientDrawable off_ = round(Color.parseColor(dark ? "#4D4547" : "#D9D1CF"), 14);
        off_.setSize(dp(46), dp(28));
        track.addState(new int[]{android.R.attr.state_checked}, on_);
        track.addState(new int[0], off_);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setStroke(dp(3), Color.TRANSPARENT);
        thumb.setSize(dp(28), dp(28));
        s.setTrackDrawable(track);
        s.setThumbDrawable(thumb);
        s.setTrackTintList(null);
        s.setThumbTintList(null);
        s.setSwitchMinWidth(dp(46));
        s.setBackground(null);
        return s;
    }

    interface Bool { void set(boolean b); }

    private Switch switchRow(LinearLayout c, String title, String desc, boolean on, Bool onChange) {
        LinearLayout r = row();
        r.setPadding(dp(14), dp(14), dp(10), dp(14));
        ripple(r, round(Color.TRANSPARENT, 18), 18);
        LinearLayout t = col();
        t.addView(tv(title, 16, text, true));
        if (desc != null) t.addView(tv(desc, 13, sub, false), mlp(-1, -2, 0, 3, 0, 0));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch s = toggle(on);
        r.addView(s, mlp(-2, -2, 12, 0, 0, 0));
        s.setOnCheckedChangeListener((b, v) -> onChange.set(v));
        r.setOnClickListener(v -> s.toggle());
        c.addView(r);
        return s;
    }

    /** Radio-like option row with orange check. */
    private View optionRow(LinearLayout c, String title, String desc, boolean selected, Runnable pick) {
        LinearLayout r = row();
        r.setPadding(dp(14), dp(13), dp(14), dp(13));
        ripple(r, round(selected ? soft : Color.TRANSPARENT, 18), 18);
        LinearLayout t = col();
        t.addView(tv(title, 15, selected ? (dark ? Color.parseColor("#EDA6B4") : BURGUNDY) : text, true));
        if (desc != null) t.addView(tv(desc, 13, sub, false), mlp(-1, -2, 0, 3, 0, 0));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(check(selected), mlp(dp(24), dp(24), 12, 0, 0, 0));
        r.setOnClickListener(v -> pick.run());
        c.addView(r, mlp(-1, -2, 0, 2, 0, 2));
        return r;
    }

    private TextView check(boolean on) {
        TextView k = tv(on ? "✓" : "", 14, Color.WHITE, true);
        k.setGravity(Gravity.CENTER);
        GradientDrawable d = on ? round(ACCENT, 12) : stroke(Color.TRANSPARENT, Color.parseColor(dark ? "#5A5153" : "#CBC2C0"), 12, 2);
        k.setBackground(d);
        return k;
    }

    interface IntSet { void set(int v); }

    private SeekBar slider(LinearLayout c, String title, int min, int max, int value, String unit, IntSet onChange, Runnable onDone) {
        LinearLayout box = col();
        box.setPadding(dp(14), dp(12), dp(14), dp(10));
        LinearLayout head = row();
        head.addView(tv(title, 15, text, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView val = tv(value + unit, 15, ACCENT, true);
        head.addView(val);
        box.addView(head);
        SeekBar sb = new SeekBar(a);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        // own round thumb: the system one is clipped to the track at 0 / max on some phones
        GradientDrawable th = new GradientDrawable();
        th.setShape(GradientDrawable.OVAL);
        th.setColor(ACCENT);
        th.setSize(dp(22), dp(22));
        sb.setThumb(th);
        sb.setThumbOffset(dp(11));
        sb.setSplitTrack(false);
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor(dark ? "#3D3638" : "#E2DAD8")));
        sb.setPadding(dp(14), dp(10), dp(14), dp(6));   // room for the thumb at 0 and max
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { val.setText((p + min) + unit); onChange.set(p + min); } }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { if (onDone != null) onDone.run(); }
        });
        box.addView(sb, mlp(-1, -2, -12, 4, -12, 0));
        // the bar reaches into the box padding: don't clip the thumb there
        box.setClipToPadding(false);
        box.setClipChildren(false);
        c.setClipToPadding(false);
        c.setClipChildren(false);
        c.addView(box);
        return sb;
    }

    interface Fmt { String f(int v); }

    /** Slider whose value is shown through {@code fmt} (e.g. 10 -> "1.0배"). */
    private SeekBar sliderF(LinearLayout c, String title, int min, int max, int value, Fmt fmt, IntSet onChange) {
        SeekBar sb = slider(c, title, min, max, value, "", onChange, null);
        LinearLayout box = (LinearLayout) sb.getParent();
        TextView val = (TextView) ((LinearLayout) box.getChildAt(0)).getChildAt(1);
        val.setText(fmt.f(value));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { val.setText(fmt.f(p + min)); onChange.set(p + min); } }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        return sb;
    }

    /** Segmented control. */
    private void segmented(LinearLayout c, String[] labels, int selected, IntSet pick) {
        LinearLayout seg = row();
        seg.setBackground(round(chip, 14));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = tv(labels[i], 14, i == selected ? Color.WHITE : text, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            if (i == selected) t.setBackground(round(ACCENT, 11));
            else ripple(t, round(Color.TRANSPARENT, 11), 11);
            t.setOnClickListener(v -> pick.set(idx));
            seg.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        c.addView(seg, mlp(-1, -2, 10, 8, 10, 10));
    }

    private void note(LinearLayout parent, String s) {
        parent.addView(tv(s, 12.5f, sub, false), mlp(-1, -2, 10, 10, 10, 0));
    }

    private TextView primaryBtn(String label, Runnable go) {
        TextView b = tv(label, 15, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        ripple(b, round(ACCENT, 16), 16);
        b.setOnClickListener(v -> go.run());
        return b;
    }

    private TextView ghostBtn(String label, int color, Runnable go) {
        TextView b = tv(label, 15, color, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        ripple(b, round(chip, 16), 16);
        b.setOnClickListener(v -> go.run());
        return b;
    }

    // ------------------------------------------------------------------ state helpers
    private SharedPreferences ext() { return a.getSharedPreferences("thock_ext", Context.MODE_PRIVATE); }

    private String koType() {
        String t = ext().getString("ko", null);
        return t != null ? t : (ext().getBoolean("cj", true) ? "cj" : "qwerty");
    }

    private String[] currentSwitch() {
        String k = prefs.getSwitchName();
        for (String[] s : SwitchList.ALL) if (s[0].equals(k)) return s;
        return SwitchList.ALL[0];
    }

    private boolean imeEnabled() {
        try {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : imm.getEnabledInputMethodList()) if (i.getPackageName().equals(a.getPackageName())) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean imeDefault() {
        String d = Settings.Secure.getString(a.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return d != null && d.startsWith(a.getPackageName() + "/");
    }

    private void preview() {
        try {
            sound.play(prefs.getSwitchName(), SoundKind.KEY, true, prefs.getVolume(), prefs.getFoam());
            root.postDelayed(() -> sound.play(prefs.getSwitchName(), SoundKind.KEY, false, prefs.getVolume(), prefs.getFoam()), 90);
        } catch (Throwable ignored) {}
    }

    private void hapticDemo() {
        try {
            if (prefs.getHapticMode() == 2) { root.performHapticFeedback(3); return; }
            haptics.press(prefs.getHapticMode(), prefs.getHapticPressAmp());
            root.postDelayed(() -> haptics.release(prefs.getHapticMode(), prefs.getHapticReleaseAmp()), 120);
        } catch (Throwable ignored) {}
    }

    private static final String[] HAPTIC_NAMES = {"Prohaptic 1 (섬세한 진동)", "누를 때 강하게 / 뗄 때 약하게 (세기 직접 조절)", "단일 진동", "Prohaptic 2 (묵직한 진동)"};

    // ================================================================== pages
    private View pageHome() {
        LinearLayout b = scaffold("", null, false);
        b.removeAllViews();

        // logo header
        LinearLayout logo = row();
        FrameLayout mark = new FrameLayout(a);
        GradientDrawable mg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#9A4254"), Color.parseColor("#62212D")});
        mg.setCornerRadius(dp(16));
        mark.setBackground(mg);
        mark.setElevation(dp(3));
        TextView mt = tv("도", 26, CREAM, true);
        mt.setGravity(Gravity.CENTER);
        mt.setIncludeFontPadding(false);
        mark.addView(mt, new FrameLayout.LayoutParams(-1, -1));
        try {   // the app icon itself
            android.graphics.drawable.Drawable appIcon = a.getPackageManager().getApplicationIcon(a.getApplicationInfo());
            android.widget.ImageView iv = new android.widget.ImageView(a);
            iv.setImageDrawable(appIcon);
            mark.removeAllViews();
            mark.setBackground(null);
            mark.setElevation(0);
            mark.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable ignored) {}
        logo.addView(mark, mlp(dp(54), dp(54), 0, 0, 14, 0));
        LinearLayout words = col();
        android.text.SpannableString ws = new android.text.SpannableString("도록.");
        ws.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 2, 3, 0);
        TextView wt = tv("", 30, text, true);
        wt.setText(ws);
        wt.setLetterSpacing(-0.02f);
        wt.setIncludeFontPadding(false);
        words.addView(wt);
        words.addView(tv("기계식 타건감 키보드", 13, sub, false), mlp(-1, -2, 1, 4, 0, 0));
        logo.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        b.addView(logo, mlp(-1, -2, 2, 0, 0, 0));

        // keyboard switch (+ enable step only if still needed)
        boolean en = imeEnabled(), def = imeDefault();
        LinearLayout ks = card(b, null);
        if (!en) {
            navRow(ks, LineIcon.KEYBOARD, "키보드 켜기", "설정에서 도록 키보드를 켜 주세요",
                    () -> a.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
            divider(ks);
        }
        navRow(ks, LineIcon.SWAP, "키보드 전환", def ? "도록 키보드 사용 중" : "눌러서 도록 키보드로 바꾸기", this::pickIme);

        // test
        LinearLayout t = card(b, "타자 테스트");
        EditText et = new EditText(a);
        et.setHint("여기를 눌러서 쳐 보세요");
        et.setHintTextColor(sub);
        et.setTextColor(text);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setBackground(null);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.addView(et);

        // hero: current switch
        String[] cur = currentSwitch();
        LinearLayout hero = col();
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                dark ? new int[]{Color.parseColor("#8E3646"), Color.parseColor("#561B27")}
                     : new int[]{Color.parseColor("#9A4254"), Color.parseColor("#62212D")});
        hg.setCornerRadius(dp(26));
        ripple(hero, hg, 26);
        hero.setPadding(dp(20), dp(18), dp(16), dp(18));
        hero.setOnClickListener(v -> open("switch"));
        TextView tag = tv("현재 스위치 · " + cur[4], 12.5f, CREAM, true);
        hero.addView(tag);
        LinearLayout hr = row();
        TextView name = tv(cur[2], 21, Color.WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        hr.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.ImageView play = icon(LineIcon.PLAY, BURGUNDY, 14);
        ripple(play, round(CREAM, 24), 24);
        play.setOnClickListener(v -> preview());
        hr.addView(play, mlp(dp(48), dp(48), 12, 0, 0, 0));
        hero.addView(hr, mlp(-1, -2, 0, 6, 0, 0));
        if (!cur[3].isEmpty()) {
            TextView d = tv(cur[3], 13, CREAM, false);
            d.setMaxLines(2);
            d.setEllipsize(TextUtils.TruncateAt.END);
            hero.addView(d, mlp(-1, -2, 0, 6, 0, 0));
        }
        LinearLayout more = row();
        more.addView(tv("스위치 바꾸기", 13, Color.WHITE, true));
        more.addView(icon(LineIcon.CHEVRON, Color.WHITE, 0), mlp(dp(16), dp(16), 2, 1, 0, 0));
        hero.addView(more, mlp(-1, -2, 0, 12, 0, 0));
        b.addView(hero, mlp(-1, -2, 0, 18, 0, 0));

        // menu
        LinearLayout m = card(b, "설정");
        navRow(m, LineIcon.SOUND, "소리", "볼륨 " + prefs.getVolume() + "% · 흡음재 " + (prefs.getFoam() ? "켬" : "끔"), () -> open("sound"));
        divider(m);
        navRow(m, LineIcon.HAPTIC, "진동", prefs.getHaptic() ? HAPTIC_NAMES[Math.max(0, Math.min(3, prefs.getHapticMode()))] : "꺼짐", () -> open("haptic"));
        divider(m);
        navRow(m, LineIcon.KEYBOARD, "자판 · 크기", ("nara".equals(koType()) ? "나랏글" : "qwerty".equals(koType()) ? "쿼티" : "천지인") + " · 높이 " + prefs.getHeightPct() + "% · 너비 " + prefs.getWidthPct() + "%", () -> open("layout"));
        divider(m);
        Typo.load(a);
        Spell.load(a);
        navRow(m, LineIcon.MESSAGE, "오타 제안 · 자동 교정", (Typo.on ? "제안 켜짐" : "제안 꺼짐") + " · 자동 교정 " + (Typo.auto ? "모두" : Typo.autoUU ? "ㅠ·ㅜ만" : "꺼짐")
                + " · 맞춤법 " + (Spell.on ? "켜짐" : "꺼짐"), () -> open("typo"));
        divider(m);
        Calib.load(a);
        navRow(m, LineIcon.ROBOT, "터치 범위 자동 학습 · 수동 보정", Calib.isOn(a) ? "보정 켜짐 · 기록 " + Calib.samples.size() + "번"
                : Calib.samples.isEmpty() ? "따라 쓰기로 내 터치 습관 학습" : "보정 꺼짐 · 기록 " + Calib.samples.size() + "번", () -> open("calib"));
        divider(m);
        navRow(m, LineIcon.THEME, "테마", prefs.getLightTheme() ? "흰색" : "어두운 색", () -> open("theme"));
        divider(m);
        navRow(m, LineIcon.CLIPBOARD, "클립보드", prefs.getClipboard() ? "복사 기록 켜짐" : "꺼짐", () -> open("clip"));

        // 신버전(이 수정판) / 구버전(원작자 기본 화면) 전환 - 원작자 v5.5.0
        LinearLayout vs = card(b, "버전");
        switchRow(vs, "신버전 사용", "툴바·삼성식 자판·문구가 있는 수정판. 끄면 구버전으로 돌아가요", true, on -> {
            if (on) return;
            prefs.setUseNewVersion(false);
            root.post(a::recreate);
        });

        String ver = "?";
        try { ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        TextView vv = tv("버전 " + ver, 12, sub, false);
        vv.setGravity(Gravity.CENTER);
        b.addView(vv, mlp(-1, -2, 0, 24, 0, 0));
        return (View) b.getParent();
    }

    private void pickIme() {
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showInputMethodPicker();
    }

    // ---- switch sounds: category chips + 2-column cards
    private static final String[] CATS = {"청축", "갈축", "적축", "딥 톡", "리마스터", "영상 원음", "ASMR"};

    private LinearLayout switchChips, switchGrid;
    private final java.util.Map<String, LinearLayout> switchCells = new java.util.HashMap<>();

    private View pageSwitch() {
        LinearLayout b = scaffold("스위치 소리", "축을 고르고, 눌러서 들어 보세요", true);
        if (switchCat == null) switchCat = currentSwitch()[4];

        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        switchChips = row();
        hs.addView(switchChips);
        b.addView(hs, mlp(-1, -2, 0, 18, -18, 6));
        fillChips();
        hs.post(() -> {
            for (int i = 0; i < switchChips.getChildCount(); i++)
                if (CATS[i].equals(switchCat)) { hs.scrollTo(Math.max(0, switchChips.getChildAt(i).getLeft() - dp(24)), 0); break; }
        });

        switchGrid = col();
        b.addView(switchGrid, new LinearLayout.LayoutParams(-1, -2));
        fillGrid();
        return (View) b.getParent();
    }

    /** Chips are restyled in place; no page rebuild. */
    private void fillChips() {
        switchChips.removeAllViews();
        String curCat = currentSwitch()[4];
        for (String c : CATS) {
            int n = 0;
            for (String[] s : SwitchList.ALL) if (s[4].equals(c)) n++;
            boolean sel = c.equals(switchCat);
            TextView t = tv(c + (c.equals(curCat) ? " •" : "") + "  " + n, 14, sel ? Color.WHITE : text, true);
            t.setPadding(dp(16), dp(10), dp(16), dp(10));
            if (sel) t.setBackground(round(ACCENT, 20)); else ripple(t, round(chip, 20), 20);
            t.setOnClickListener(v -> {
                if (c.equals(switchCat)) return;
                switchCat = c;
                fillChips();
                fillGrid();
                pageScroll.scrollTo(0, 0);
            });
            switchChips.addView(t, mlp(-2, -2, 0, 0, 8, 0));
        }
    }

    private void fillGrid() {
        switchGrid.removeAllViews();
        switchCells.clear();
        List<String[]> items = new ArrayList<>();
        for (String[] s : SwitchList.ALL) if (s[4].equals(switchCat)) items.add(s);
        String curKey = prefs.getSwitchName();
        LinearLayout r = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 2 == 0) { r = row(); r.setGravity(Gravity.TOP); switchGrid.addView(r, mlp(-1, -2, 0, 10, 0, 0)); }
            String[] s = items.get(i);
            LinearLayout cell = col();
            cell.setPadding(dp(14), dp(14), dp(14), dp(14));
            cell.setMinimumHeight(dp(118));
            styleCell(cell, s, s[0].equals(curKey));
            cell.setOnClickListener(v -> selectSwitch(s));
            switchCells.put(s[0], cell);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(i % 2 == 0 ? 0 : dp(5), 0, i % 2 == 0 ? dp(5) : 0, 0);
            r.addView(cell, lp);
        }
        if (items.size() % 2 == 1 && r != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMargins(dp(5), 0, 0, 0);
            r.addView(new View(a), lp);
        }
    }

    private void selectSwitch(String[] s) {
        String old = prefs.getSwitchName();
        String oldCat = currentSwitch()[4];
        if (!s[0].equals(old)) {
            prefs.setSwitchName(s[0]);
            try { sound.preload(s[0], prefs.getFoam()); } catch (Throwable ignored) {}
            LinearLayout prev = switchCells.get(old);
            if (prev != null) for (String[] x : SwitchList.ALL) if (x[0].equals(old)) styleCell(prev, x, false);
            LinearLayout now = switchCells.get(s[0]);
            if (now != null) styleCell(now, s, true);
            if (!oldCat.equals(s[4])) fillChips();
            root.postDelayed(this::preview, 200);
        } else {
            preview();
        }
    }

    /** (Re)draws one card's content in place. */
    private void styleCell(LinearLayout cell, String[] s, boolean sel) {
        cell.removeAllViews();
        ripple(cell, sel ? stroke(soft, ACCENT, 20, 2) : stroke(card, card, 20, 2), 20);
        LinearLayout top = row();
        TextView num = tv(s[1], 11, sel ? Color.WHITE : sub, true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(round(sel ? ACCENT : chip, 9));
        num.setPadding(dp(7), dp(2), dp(7), dp(2));
        top.addView(num);
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView using = tv("사용 중", 11, ACCENT, true);
        using.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        top.addView(using);
        cell.addView(top);
        TextView title = tv(s[2], 15, text, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(title, mlp(-1, -2, 0, 10, 0, 0));
        if (!s[3].isEmpty()) {
            TextView d = tv(s[3], 12, sub, false);
            d.setMaxLines(3);
            d.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(d, mlp(-1, -2, 0, 4, 0, 0));
        }
    }

    // ---- sound
    private View pageSound() {
        LinearLayout b = scaffold("소리", null, true);
        LinearLayout v = card(b, "볼륨");
        slider(v, "키 소리 크기", 0, 100, prefs.getVolume(), "%", prefs::setVolume, this::preview);
        note(b, "키 소리는 휴대폰의 벨소리 볼륨을 따라가요. 무음·진동 모드이면 소리가 나지 않아요.");

        LinearLayout sm = card(b, "매너모드");
        switchRow(sm, "매너모드에서도 키 소리", "무음·진동 모드일 때도 키보드 소리만 나요 (미디어 볼륨을 따라가요)",
                ext().getBoolean("silent_sound", false), on -> ext().edit().putBoolean("silent_sound", on).apply());

        LinearLayout f = card(b, "흡음재");
        switchRow(f, "흡음재 넣기", "울림과 고음 여운을 줄여 짧고 먹먹한 소리", prefs.getFoam(), on -> {
            prefs.setFoam(on);
            try { sound.preload(prefs.getSwitchName(), on); } catch (Throwable ignored) {}
            root.postDelayed(this::preview, 400);
        });

        LinearLayout o = card(b, "이어폰 · 블루투스 연결 시");
        String[][] outs = {{"0", "이어폰으로만", "연결돼 있으면 이어폰으로만 (미디어 볼륨)"}, {"1", "타건음 끄기", "이어폰 연결 중에는 소리 없음"}, {"2", "스피커 + 이어폰", "둘 다 동시에 (예전 방식)"}};
        for (String[] x : outs) {
            int mode = Integer.parseInt(x[0]);
            optionRow(o, x[1], x[2], prefs.getHeadsetOutput() == mode, () -> {
                prefs.setHeadsetOutput(mode);
                try { sound.refreshRoute(); } catch (Throwable ignored) {}
                show("sound", false);
                root.postDelayed(this::preview, 400);
            });
        }
        return (View) b.getParent();
    }

    // ---- haptic
    private View pageHaptic() {
        LinearLayout b = scaffold("진동", null, true);
        LinearLayout s = card(b, null);
        switchRow(s, "키 진동", "키를 누를 때 진동으로 알려줘요", prefs.getHaptic(), on -> {
            prefs.setHaptic(on);
            if (on) hapticDemo();
            show("haptic", false);
        });
        if (!prefs.getHaptic()) return (View) b.getParent();

        LinearLayout m = card(b, "진동 방식");
        int[] order = {2, 0, 3, 1};
        String[] desc = {"누를 때 한 번만, 시스템 기본 키 진동", "살짝 스치는 예고 뒤 가벼운 한 톡. 손끝에 또각 걸리는 손맛이 나요.", "길게 눌리는 예고 뒤 센 한 방. 키가 바닥까지 닿는 손맛이 나요.", "세기를 직접 조절해요"};
        for (int i = 0; i < order.length; i++) {
            int mode = order[i];
            optionRow(m, HAPTIC_NAMES[mode] + (mode == 2 ? " (기본)" : ""), desc[i], prefs.getHapticMode() == mode, () -> {
                prefs.setHapticMode(mode);
                hapticDemo();
                show("haptic", false);
            });
        }
        if (prefs.getHapticMode() == 1) {
            LinearLayout w = card(b, "세기");
            slider(w, "누를 때", 1, 255, prefs.getHapticPressAmp(), "", prefs::setHapticPressAmp, this::hapticDemo);
            slider(w, "뗄 때", 1, 255, prefs.getHapticReleaseAmp(), "", prefs::setHapticReleaseAmp, this::hapticDemo);
        }
        note(b, "Prohaptic은 도록이 직접 만든 진동이에요. 실제 키보드를 치듯 손끝에 닿는 순간을 하나하나 맞춰 가며 진동의 세기와 길이를 잡았어요.");
        note(b, "진동이 약하면 휴대폰 설정 › 소리 및 진동 › 진동 세기(터치 상호작용)를 확인해 보세요.");
        return (View) b.getParent();
    }

    // ---- layout & size
    private View pageLayout() {
        LinearLayout b = scaffold("자판 · 크기", null, true);
        LinearLayout k = card(b, "한글 자판");
        String ko = koType();
        int sel = "nara".equals(ko) ? 1 : "qwerty".equals(ko) ? 2 : 0;
        segmented(k, new String[]{"천지인", "나랏글", "쿼티"}, sel, i -> {
            String v = i == 0 ? "cj" : i == 1 ? "nara" : "qwerty";
            ext().edit().putString("ko", v).putBoolean("cj", i == 0).apply();
            show("layout", false);
        });
        String desc = sel == 0 ? "삼성 키보드와 같은 천지인 배열이에요. 같은 자음을 연달아 쓰려면 띄어쓰기를 한 번 누른 뒤 입력하세요. 키를 길게 누르면 숫자가 나와요."
                : sel == 1 ? "삼성 키보드와 같은 나랏글 배열이에요. 획추가로 ㄱ→ㅋ, ㄴ→ㄷ→ㅌ, ㅏ→ㅑ, 쌍자음으로 ㄱ→ㄲ. 쉼표를 길게 누르면 기호가 나와요."
                : "삼성 키보드와 같은 쿼티(두벌식) 배열이에요. 위에 숫자줄이 있고, ⇧로 쌍자음(ㅃㅉㄸㄲㅆ)과 ㅒ·ㅖ를 써요.";
        k.addView(tv(desc, 13, sub, false), mlp(-1, -2, 14, 0, 14, 10));

        LinearLayout s = card(b, "키보드 크기");
        slider(s, "키 높이", 80, 150, prefs.getHeightPct(), "%", prefs::setHeightPct, null);
        slider(s, "키 너비", 50, Prefs.WIDTH_MAX, prefs.getWidthPct(), "%", prefs::setWidthPct, null);
        slider(s, "아래 여백", 0, 30, ext().getInt("bottom_pad", 8), "dp", v -> ext().edit().putInt("bottom_pad", v).apply(), null);
        s.addView(tv("슬라이드로 0~30dp까지 조절해요. 8dp가 삼성 키보드와 같아요.", 13, sub, false), mlp(-1, -2, 14, 0, 14, 10));
        TextView al = tv("너비를 줄였을 때 위치", 15, text, true);
        s.addView(al, mlp(-1, -2, 14, 10, 14, 0));
        segmented(s, new String[]{"왼쪽", "가운데", "오른쪽"}, prefs.getWidthAlign(), i -> { prefs.setWidthAlign(i); show("layout", false); });

        LinearLayout nr = card(b, "숫자줄");
        switchRow(nr, "숫자 바 (천지인·나랏글)", "천지인·나랏글 위에 1~0 숫자 바를 붙여요", ext().getBoolean("numrow", true),
                on -> ext().edit().putBoolean("numrow", on).apply());
        slider(nr, "숫자 바 아래 간격", 0, 30, ext().getInt("numbar_gap", 0), "dp", v -> ext().edit().putInt("numbar_gap", v).apply(), null);
        divider(nr);
        switchRow(nr, "숫자줄 (쿼티·영어)", "끄면 윗줄 키에 1~0이 작게 표시되고, 길게 누르면 숫자가 나와요", ext().getBoolean("qnumrow", true),
                on -> ext().edit().putBoolean("qnumrow", on).apply());

        LinearLayout cm = card(b, "커서 이동");
        switchRow(cm, "자판 위에서 밀어 커서 이동", "글자 키 위에서 손가락을 좌우로 길게 밀면 글자 대신 커서가 움직여요 (삼성 키보드처럼)",
                ext().getBoolean("key_cursor", true), on -> ext().edit().putBoolean("key_cursor", on).apply());
        divider(cm);
        LinearLayout cmx = col();          // options shown only while 커서 이동 is on
        switchRow(cm, "스페이스바로 커서 이동", "스페이스바를 꾹 누르거나 좌우로 끌어서 커서를 움직여요", ext().getBoolean("space_cursor", true),
                on -> { ext().edit().putBoolean("space_cursor", on).apply(); cmx.setVisibility(on ? View.VISIBLE : View.GONE); });
        cm.addView(cmx);
        cmx.setVisibility(ext().getBoolean("space_cursor", true) ? View.VISIBLE : View.GONE);
        divider(cmx);
        switchRow(cmx, "누르고 있으면 계속 이동 (아이폰식)", "손을 뗄 때까지 계속 움직여요. 조금 끌면 천천히, 많이 끌면 빠르게. 끄면 삼성 키보드처럼 끈 거리만큼만 움직여요",
                ext().getBoolean("space_joystick2", false), on -> ext().edit().putBoolean("space_joystick2", on).apply());
        divider(cmx);
        switchRow(cmx, "상하 이동", "위아래로 끌면 윗줄·아랫줄로 커서가 움직여요", ext().getBoolean("space_vertical", false),
                on -> ext().edit().putBoolean("space_vertical", on).apply());
        divider(cmx);
        sliderF(cmx, "커서 이동 속도", 1, 20, ext().getInt("cursor_speed10", 10), v -> (v / 10) + "." + (v % 10) + "배",
                v -> ext().edit().putInt("cursor_speed10", v).apply());
        cmx.addView(tv("1.0배가 삼성 키보드와 같은 속도예요 (손가락을 화면 폭의 약 4.4% 움직이면 한 칸). 낮추면 느리게, 올리면 빠르게 움직여요.", 13, sub, false), mlp(-1, -2, 14, 0, 14, 10));

        LinearLayout tb = card(b, "툴바 버튼");
        sliderF(tb, "툴바 크기", 5, 10, ext().getInt("tb_scale10", 10), v -> (v / 10) + "." + (v % 10) + "배",
                v -> ext().edit().putInt("tb_scale10", v).apply());
        divider(tb);
        slider(tb, "툴바 아래 간격", 0, 30, ext().getInt("tb_gap", 0), "dp", v -> ext().edit().putInt("tb_gap", v).apply(), null);
        divider(tb);
        switchRow(tb, "편집", "방향키·전체선택·복사·붙여넣기 화면", ext().getBoolean("tb_edit", true), on -> ext().edit().putBoolean("tb_edit", on).apply());
        divider(tb);
        switchRow(tb, "문구", "저장한 문구 빠르게 넣기", ext().getBoolean("tb_msg", true), on -> ext().edit().putBoolean("tb_msg", on).apply());
        divider(tb);
        switchRow(tb, "클립보드", "복사한 글 기록", ext().getBoolean("tb_clip", true), on -> ext().edit().putBoolean("tb_clip", on).apply());
        divider(tb);
        switchRow(tb, "최근 복사 바로 붙여넣기", "클립보드 아이콘 옆에 방금 복사한 글 앞 5자를 보여주고, 누르면 바로 붙여넣어요", ext().getBoolean("tb_clipchip", true), on -> ext().edit().putBoolean("tb_clipchip", on).apply());
        divider(tb);
        switchRow(tb, "음성 입력", "마이크 버튼을 누르면 고르는 창 없이 바로 Google 음성 입력으로 바뀌어요", ext().getBoolean("tb_mic", true), on -> ext().edit().putBoolean("tb_mic", on).apply());
        divider(tb);
        switchRow(tb, "커서 이동 ◀ ▶", "툴바 오른쪽 좌우 이동 버튼", ext().getBoolean("tb_arrows", true), on -> ext().edit().putBoolean("tb_arrows", on).apply());
        divider(tb);
        switchRow(tb, "커서 이동 ▲ ▼", "툴바 오른쪽 위아래 이동 버튼 (좌우와 함께면 ◀ ▶ ▲ ▼ 순서)", ext().getBoolean("tb_updown", false), on -> ext().edit().putBoolean("tb_updown", on).apply());
        divider(tb);
        switchRow(tb, "설정", "툴바 맨 오른쪽 ⚙ 버튼으로 언제든 설정 열기", ext().getBoolean("tb_settings", true), on -> ext().edit().putBoolean("tb_settings", on).apply());
        note(b, "모두 끄면 툴바 줄이 사라져요. 바꾼 설정은 키보드를 다시 열면 적용돼요.");

        LinearLayout sp = card(b, "넓은 화면");
        switchRow(sp, "좌우 분리형 배열", "폴드 펼침·가로 화면(600dp 이상)에서 양손 쪽으로 나눠요", prefs.getSplitWhenWide(), prefs::setSplitWhenWide);
        note(b, "크기 설정은 키보드를 다시 열 때 적용돼요. 100%가 삼성 키보드와 같은 크기예요.");
        return (View) b.getParent();
    }

    // ---- theme
    private View pageTheme() {
        LinearLayout b = scaffold("테마", null, true);
        LinearLayout r = row();
        b.addView(r, mlp(-1, -2, 0, 20, 0, 0));
        r.addView(themeTile("어두운 색", false), weight(0, 6));
        r.addView(themeTile("흰색", true), weight(6, 0));
        note(b, "키보드를 다시 열면 바뀐 테마가 보여요.");
        return (View) b.getParent();
    }

    private LinearLayout.LayoutParams weight(int l, int r) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(dp(l), 0, dp(r), 0);
        return p;
    }

    private View themeTile(String label, boolean light) {
        boolean sel = prefs.getLightTheme() == light;
        LinearLayout t = col();
        t.setPadding(dp(12), dp(12), dp(12), dp(14));
        ripple(t, sel ? stroke(card, ACCENT, 20, 2.5f) : round(card, 20), 20);
        LinearLayout kb = col();
        kb.setBackground(round(Color.parseColor(light ? "#E4E5E7" : "#1B1C1F"), 12));
        kb.setPadding(dp(6), dp(8), dp(6), dp(8));
        int keyC = Color.parseColor(light ? "#FDFDFE" : "#3A3C42");
        for (int i = 0; i < 4; i++) {
            LinearLayout kr = row();
            int n = i == 3 ? 3 : (i == 1 ? 9 : 10);
            for (int j = 0; j < n; j++) {
                View key = new View(a);
                boolean wide = i == 3 && j == 1;
                key.setBackground(round(i == 3 && j != 1 ? Color.parseColor(light ? "#C9CBCF" : "#2A2C31") : keyC, 3));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(14), wide ? 6f : 1f);
                lp.setMargins(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));
                kr.addView(key, lp);
            }
            kb.addView(kr);
        }
        t.addView(kb);
        LinearLayout lr = row();
        lr.addView(tv(label, 15, text, true), new LinearLayout.LayoutParams(0, -2, 1f));
        lr.addView(check(sel), new LinearLayout.LayoutParams(dp(22), dp(22)));
        t.addView(lr, mlp(-1, -2, 2, 12, 2, 0));
        t.setOnClickListener(v -> { prefs.setLightTheme(light); show("theme", false); });
        return t;
    }

    // ---- clipboard
    private View pageClip() {
        LinearLayout b = scaffold("클립보드", null, true);
        LinearLayout c = card(b, null);
        switchRow(c, "복사한 글 기록", "이 키보드를 쓰는 동안 복사한 글을 최근 30개까지 저장해요", prefs.getClipboard(), prefs::setClipboard);
        int n = 0;
        try { n = new ClipHistory(a).all().size(); } catch (Throwable ignored) {}
        note(b, "키보드 위 📋 클립 버튼이나 !#1 키를 길게 누르면 기록이 열려요. 비밀번호처럼 '민감'으로 표시된 복사는 저장하지 않아요.");
        final int count = n;
        TextView clr = ghostBtn("복사 기록 모두 지우기" + (count > 0 ? " (" + count + "개)" : ""), Color.parseColor("#E5484D"), () -> {
            try { new ClipHistory(a).clear(); } catch (Throwable ignored) {}
            Toast.makeText(a, "복사 기록을 지웠어요", Toast.LENGTH_SHORT).show();
            show("clip", false);
        });
        b.addView(clr, mlp(-1, -2, 0, 20, 0, 0));
        return (View) b.getParent();
    }

    // ================================================================== 오타 학습 · 터치 보정
    private static final int RED = Color.parseColor("#E5484D");
    private int trainIdx;
    private String calibSel;
    private TextView trainProg, trainTarget, trainTyped;
    private EditText trainEt;

    private View pageCalib() {
        Calib.load(a);
        LinearLayout b = scaffold("터치 범위 자동 학습 · 수동 보정", "쿼티 자판(한글·영어)에서 내 손가락 습관에 맞게 키 인식 범위를 옮겨요", true);
        LinearLayout c = card(b, null);
        switchRow(c, "터치 보정 사용", "학습했거나 직접 맞춘 범위로 키를 인식해요", Calib.isOn(a), on -> Calib.setOn(a, on));
        int n = Calib.samples.size(), typos = 0;
        for (Calib.Sample s : Calib.samples) if (!s.ok()) typos++;
        LinearLayout m = card(b, "학습");
        navRow(m, LineIcon.PENCIL, "따라 쓰기로 학습", "문장 " + Calib.SENTENCES.length + "개 · 3분쯤 걸려요", () -> { trainIdx = 0; open("calib_train"); });
        divider(m);
        navRow(m, LineIcon.THEME, "학습 결과 보기", n == 0 ? "아직 기록이 없어요" : "기록된 터치 " + n + "번 · 오타 " + typos + "번", () -> { calibSel = null; open("calib_result"); });
        divider(m);
        navRow(m, LineIcon.KEYBOARD, "터치 범위 상세 설정", Calib.params().isEmpty() ? "모든 키가 기본 범위예요" : "키 " + Calib.params().size() + "개를 조정했어요", () -> { calibSel = null; open("calib_detail"); });
        if (!"qwerty".equals(koType()))
            note(b, "지금 한글 자판이 " + ("nara".equals(koType()) ? "나랏글" : "천지인") + "이에요. 따라 쓰기는 쿼티 자판으로 진행되고, 보정도 쿼티 한글·영어 자판에만 적용돼요.");
        note(b, "따라 쓰기에서 모은 터치로 키마다 실제로 누르는 위치를 계산해서, 그쪽으로 인식 범위를 옮겨요. 여러 번 하면 기록이 쌓여서 더 정확해져요.");
        return (View) b.getParent();
    }

    private void showKeyboard() {
        if (trainEt == null) return;
        try {
            trainEt.requestFocus();
            ((InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(trainEt, 0);
        } catch (Throwable ignored) {}
    }

    private View pageCalibTrain() {
        Calib.load(a);
        if (trainIdx >= Calib.SENTENCES.length) trainIdx = 0;
        LinearLayout b = scaffold("따라 쓰기", "아래 문장을 평소 속도로 그대로 쳐 주세요", true);
        if (!imeDefault()) {
            LinearLayout w = card(b, null);
            navRow(w, LineIcon.SWAP, "도록 키보드로 바꾸기", "지금 다른 키보드가 선택돼 있어서 기록할 수 없어요", this::pickIme);
        }
        trainProg = tv("", 13, sub, true);
        b.addView(trainProg, mlp(-1, -2, 8, 20, 8, 8));
        LinearLayout c = col();
        c.setBackground(round(card, 22));
        c.setPadding(dp(18), dp(18), dp(18), dp(16));
        trainTarget = tv("", 21, text, true);
        trainTarget.setLineSpacing(0, 1.3f);
        c.addView(trainTarget);
        View ln = new View(a);
        ln.setBackgroundColor(line);
        c.addView(ln, mlp(-1, 1, 0, 14, 0, 14));
        trainTyped = tv("", 21, text, false);
        trainTyped.setLineSpacing(0, 1.3f);
        trainTyped.setMinLines(2);
        c.addView(trainTyped);
        trainEt = new EditText(a);                       // 키보드를 띄우기 위한 안 보이는 입력칸
        trainEt.setBackground(null);
        trainEt.setCursorVisible(false);
        trainEt.setAlpha(0f);
        trainEt.setPadding(0, 0, 0, 0);
        trainEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 1);
        trainEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        trainEt.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        c.addView(trainEt, new LinearLayout.LayoutParams(-1, dp(1)));
        c.setOnClickListener(v -> showKeyboard());
        b.addView(c, mlp(-1, -2, 0, 0, 0, 0));
        note(b, "틀린 글자는 빨갛게 보여요. 지우기 키로 지우고 다시 쳐서 문장을 똑같이 만들면 다음 문장으로 넘어가요. 조심해서 치지 말고 평소처럼 쳐야 정확해요.");
        LinearLayout btns = row();
        btns.addView(ghostBtn("이 문장 건너뛰기", text, this::trainNext), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(10);
        btns.addView(primaryBtn("끝내고 결과 보기", this::trainFinish), lp);
        b.addView(btns, mlp(-1, -2, 0, 18, 0, 0));
        trainLoad();
        trainEt.postDelayed(this::showKeyboard, 250);
        return (View) b.getParent();
    }

    private void trainLoad() {
        final Calib.Trainer t = new Calib.Trainer(Calib.SENTENCES[trainIdx]);
        trainProg.setText((trainIdx + 1) + " / " + Calib.SENTENCES.length + " 문장");
        trainTarget.setText(t.sentence);
        t.onChange = () -> {
            renderTyped(t);
            if (t.done()) root.postDelayed(() -> { if (Calib.trainer == t) trainNext(); }, 450);
        };
        Calib.trainer = t;
        Calib.training = true;
        renderTyped(t);
    }

    private void renderTyped(Calib.Trainer t) {
        Calib.Composed c = Calib.compose(t.stack);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(c.text);
        for (int i = 0; i < c.span.size(); i++) {
            boolean bad = false;
            for (int j : c.span.get(i)) if (t.badAt(j)) bad = true;
            if (!bad) continue;
            sb.setSpan(new android.text.style.ForegroundColorSpan(RED), i, i + 1, 0);
            sb.setSpan(new android.text.style.BackgroundColorSpan(dark ? 0x40E5484D : 0x26E5484D), i, i + 1, 0);   // 잘못 친 띄어쓰기도 보이게
        }
        int n = sb.length();
        sb.append(t.done() ? " ✓" : "▏");
        sb.setSpan(new android.text.style.ForegroundColorSpan(t.done() ? Color.parseColor("#2EAD5B") : ACCENT), n, sb.length(), 0);
        trainTyped.setText(sb);
    }

    private void trainNext() {
        if (!"calib_train".equals(current())) return;
        trainIdx++;
        if (trainIdx >= Calib.SENTENCES.length) trainFinish();
        else { Calib.saveSamples(a); trainLoad(); }
    }

    private void trainFinish() {
        Calib.stopTraining(a);
        try { ((InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0); } catch (Throwable ignored) {}
        if (!stack.isEmpty() && "calib_train".equals(current())) stack.remove(stack.size() - 1);
        calibSel = null;
        open("calib_result");
    }

    private static String pct(float v) { return Math.round(Math.abs(v) * 100) + "%"; }

    private String keyInfo(String id) {
        if (id == null) return "자판의 키를 누르면 그 키를 누르려던 터치만 볼 수 있어요.";
        int[] st = Calib.stats().get(id);
        if (st == null) return Calib.nameOf(id) + " 키를 누르려던 기록이 없어요.";
        float[] mean = Calib.meanOf(id);
        StringBuilder sb = new StringBuilder();
        sb.append(st[0]).append("번 중 오타 ").append(st[1]).append("번");
        if (st[1] > 0) sb.append("\n대신 눌린 키: ").append(Calib.confusions(id));
        sb.append("\n평균적으로 키 가운데에서 ");
        boolean any = false;
        if (Math.abs(mean[0]) >= 0.03f && !Calib.SP.equals(id)) { sb.append(mean[0] > 0 ? "오른쪽으로 " : "왼쪽으로 ").append(pct(mean[0])); any = true; }
        if (Math.abs(mean[1]) >= 0.03f) { if (any) sb.append(", "); sb.append(mean[1] > 0 ? "아래로 " : "위로 ").append(pct(mean[1])); any = true; }
        sb.append(any ? " 치우쳐 눌러요. (키 크기 기준)" : "거의 벗어나지 않아요.");
        return sb.toString();
    }

    private View pageCalibResult() {
        Calib.load(a);
        LinearLayout b = scaffold("학습 결과", null, true);
        int n = Calib.samples.size(), typos = 0;
        for (Calib.Sample s : Calib.samples) if (!s.ok()) typos++;
        if (n == 0) {
            note(b, "아직 기록이 없어요. 따라 쓰기를 먼저 해 주세요.");
            b.addView(primaryBtn("따라 쓰기 시작", () -> { trainIdx = 0; if (!stack.isEmpty()) stack.remove(stack.size() - 1); open("calib_train"); }), mlp(-1, -2, 0, 18, 0, 0));
            return (View) b.getParent();
        }
        LinearLayout s = card(b, null);
        s.addView(tv("기록된 터치 " + n + "번 · 오타 " + typos + "번 (" + Math.round(typos * 100f / n) + "%)", 16, text, true), mlp(-1, -2, 14, 12, 14, 4));
        s.addView(tv("초록 점은 제대로 눌린 터치, 빨간 점은 다른 키가 눌린 터치예요. 점은 그 키를 누르려던 위치에 찍혀요. 점 색은 기록할 때의 범위 기준이에요.", 13, sub, false), mlp(-1, -2, 14, 0, 14, 4));
        int nT = Calib.countSrc('T'), nN = Calib.countSrc('N'), nF = Calib.countSrc('F'), nOld = Calib.countSrc('?');
        s.addView(tv("따라 쓰기 " + nT + " · 평소 타자 " + nN + " · 오타 교정 " + nF + (nOld > 0 ? " · 예전 기록 " + nOld : ""), 13, text, false), mlp(-1, -2, 14, 4, 14, 12));
        if (nOld > 0) {
            s.addView(tv("예전 기록은 출처를 구분하기 전에 모은 것이라, 오타 교정 때만 쌓인 기록이 섞여 있을 수 있어요.", 12.5f, sub, false), mlp(-1, -2, 14, 0, 14, 4));
            TextView rm = tv("예전 기록 " + nOld + "개 지우기", 14, RED, true);
            rm.setPadding(dp(14), dp(8), dp(14), dp(12));
            rm.setOnClickListener(v -> { Calib.removeSrc(a, '?'); Toast.makeText(a, "예전 기록을 지웠어요", Toast.LENGTH_SHORT).show(); show("calib_result", false); });
            s.addView(rm);
        }

        LinearLayout mc = card(b, calibSel == null ? "전체 터치" : Calib.nameOf(calibSel) + " 키를 누르려던 터치");
        Calib.MapView mv = new Calib.MapView(a, false, text, dark ? Color.parseColor("#5A5153") : Color.parseColor("#CBC2C0"), ACCENT, dark);
        mv.sel = calibSel;
        mv.pick = id -> root.post(() -> { calibSel = (id == null || id.equals(calibSel)) ? null : id; show("calib_result", false); });
        mc.addView(mv, new LinearLayout.LayoutParams(-1, -2));
        mc.addView(tv(keyInfo(calibSel), 14, text, false), mlp(-1, -2, 12, 8, 12, 10));

        if (calibSel != null) {
            mc.addView(tv("색칠된 곳은 지금 " + Calib.nameOf(calibSel) + " 키로 인식되는 범위예요" + (Calib.isOn(a) ? "." : " (터치 보정이 꺼져 있어서 실제로는 기본 범위로 인식해요)."), 12.5f, sub, false), mlp(-1, -2, 12, 0, 12, 10));
            int into = 0;
            for (Calib.Sample sm : Calib.samples) if (!sm.t.equals(calibSel) && sm.p.equals(calibSel)) into++;
            if (into > 0) mc.addView(tv("○ 파랑: 다른 키를 누르려다 " + Calib.nameOf(calibSel) + " 키로 눌린 터치 " + into + "번", 12.5f, sub, false), mlp(-1, -2, 12, 0, 12, 10));
        }

        // 키별 결과: 오타가 많은 순, "모든 키 보기"로 나머지도
        final java.util.Map<String, int[]> st = Calib.stats();
        List<String> keys = new ArrayList<>(st.keySet());
        java.util.Collections.sort(keys, (x, y) -> {
            int[] p = st.get(x), q = st.get(y);
            if (p[1] != q[1]) return q[1] - p[1];
            return q[0] - p[0];
        });
        int withErr = 0;
        for (String k : keys) if (st.get(k)[1] > 0) withErr++;
        int shown = calibShowAll ? keys.size() : Math.min(keys.size(), Math.max(6, Math.min(withErr, 6)));
        LinearLayout w = card(b, calibShowAll ? "모든 키 (" + keys.size() + "개)" : "오타가 많은 키");
        for (int i = 0; i < shown; i++) {
            final String k = keys.get(i);
            int[] v = st.get(k);
            if (i > 0) divider(w);
            LinearLayout r = col();
            r.setPadding(dp(14), dp(10), dp(14), dp(10));
            ripple(r, round(k.equals(calibSel) ? soft : Color.TRANSPARENT, 16), 16);
            r.addView(tv(Calib.nameOf(k) + "   " + v[0] + "번 중 " + v[1] + "번 (" + Math.round(v[1] * 100f / v[0]) + "%)", 15, text, true));
            r.addView(tv(v[1] > 0 ? "대신 눌린 키: " + Calib.confusions(k) : "오타 없음", 13, sub, false), mlp(-1, -2, 0, 2, 0, 0));
            r.setOnClickListener(vv -> { calibSel = k; show("calib_result", false); if (pageScroll != null) pageScroll.post(() -> pageScroll.smoothScrollTo(0, 0)); });
            w.addView(r);
        }
        if (keys.size() > shown || calibShowAll) {
            divider(w);
            TextView more = tv(calibShowAll ? "접기" : "모든 키 보기 (" + keys.size() + "개)", 14, ACCENT, true);
            more.setGravity(Gravity.CENTER);
            more.setPadding(dp(14), dp(12), dp(14), dp(12));
            ripple(more, round(Color.TRANSPARENT, 16), 16);
            more.setOnClickListener(vv -> { calibShowAll = !calibShowAll; show("calib_result", false); });
            w.addView(more);
        }
        List<String> none = new ArrayList<>();
        for (String id : Calib.standard().ids) if (!st.containsKey(id)) none.add(Calib.nameOf(id));
        if (calibShowAll && !none.isEmpty()) note(b, "기록이 없는 키: " + TextUtils.join(", ", none));

        LinearLayout rc = card(b, "추천 범위");
        addCompare(rc);
        rc.addView(tv("키마다 최근에 실제로 누른 위치의 평균 쪽으로 인식 범위를 옮겨요. 오래된 기록일수록 덜 반영하고, 기록이 5번보다 적은 키는 그대로 둬요.", 13, sub, false), mlp(-1, -2, 14, 0, 14, 12));
        addRecommendButtons(b, "calib_result");
        b.addView(ghostBtn("터치 범위 상세 설정", text, () -> { calibSel = null; open("calib_detail"); }), mlp(-1, -2, 0, 10, 0, 0));
        b.addView(ghostBtn("따라 쓰기 더 하기", text, () -> { trainIdx = 0; open("calib_train"); }), mlp(-1, -2, 0, 10, 0, 0));
        b.addView(ghostBtn("기록 모두 지우기", RED, () -> {
            Calib.clearSamples(a);
            Toast.makeText(a, "터치 기록을 지웠어요", Toast.LENGTH_SHORT).show();
            show("calib_result", false);
        }), mlp(-1, -2, 0, 10, 0, 0));
        return (View) b.getParent();
    }

    private boolean calibEditing, calibShowAll;
    private int calibTool;

    /** 기록된 터치를 세 가지 설정으로 다시 인식했을 때 오타 수 */
    private void addCompare(LinearLayout rc) {
        int n = Calib.samples.size();
        boolean on = Calib.isOn(a);
        java.util.Map<String, float[]> rec = Calib.recommend();
        int cur = on ? Calib.errorsWith(Calib.params(), true) : Calib.errorsWith(null, false);
        int recKeep = Calib.errorsWith(rec, true), recOnly = Calib.errorsWith(rec, false);
        rc.addView(tv("기록된 터치 " + n + "번을 다시 인식하면", 14, text, true), mlp(-1, -2, 14, 12, 14, 6));
        String[][] rows = {
                {"지금 설정" + (on ? "" : " (보정 꺼짐)"), String.valueOf(cur)},
                {"추천 범위 + 그린 범위 유지", String.valueOf(recKeep)},
                {"그린 범위 지우고 추천 범위만", String.valueOf(recOnly)}};
        int best = Math.min(cur, Math.min(recKeep, recOnly));
        for (String[] r : rows) {
            LinearLayout line = row();
            line.setPadding(dp(14), dp(4), dp(14), dp(4));
            boolean top = Integer.parseInt(r[1]) == best;
            line.addView(tv(r[0], 14, text, top), new LinearLayout.LayoutParams(0, -2, 1f));
            line.addView(tv("오타 " + r[1] + "번", 14, top ? ACCENT : text, true));
            rc.addView(line);
        }
        if (Calib.painted == 0) rc.addView(tv("지금은 직접 그린 범위가 없어서 아래 두 줄이 같아요.", 12.5f, sub, false), mlp(-1, -2, 14, 4, 14, 0));
        rc.addView(new View(a), new LinearLayout.LayoutParams(1, dp(8)));
    }

    /** 추천 범위 적용: 모든 키 / 그린 범위 지우고 / 고른 키만 */
    private void addRecommendButtons(LinearLayout b, final String page) {
        if (Calib.samples.isEmpty()) return;
        b.addView(primaryBtn("모든 키에 추천 범위 적용", () -> {
            Calib.applyRecommended(a);
            Toast.makeText(a, "모든 키에 추천 범위를 적용하고 터치 보정을 켰어요" + (Calib.painted > 0 ? " (그린 범위는 유지)" : ""), Toast.LENGTH_SHORT).show();
            show(page, false);
        }), mlp(-1, -2, 0, 16, 0, 0));
        if (Calib.painted > 0)
            b.addView(ghostBtn("그린 범위 지우고 모든 키에 추천 범위 적용", text, () -> {
                Calib.applyRecommendedClean(a);
                Toast.makeText(a, "그린 범위를 지우고 추천 범위를 적용했어요", Toast.LENGTH_SHORT).show();
                show(page, false);
            }), mlp(-1, -2, 0, 10, 0, 0));
        if (calibSel != null) {
            final String id = calibSel;
            b.addView(ghostBtn(Calib.nameOf(id) + " 키만 추천 범위 적용", text, () -> {
                if (Calib.applyRecommendedKey(a, id)) Toast.makeText(a, Calib.nameOf(id) + " 키에 추천 범위를 적용했어요", Toast.LENGTH_SHORT).show();
                else Toast.makeText(a, Calib.nameOf(id) + " 키는 기록이 5번보다 적어서 추천할 수 없어요", Toast.LENGTH_SHORT).show();
                show(page, false);
            }), mlp(-1, -2, 0, 10, 0, 0));
        }
    }
    private float calibBrush = 14;

    private TextView chipBtn(String label, boolean on, Runnable go) {
        TextView t = tv(label, 14, on ? Color.WHITE : text, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        ripple(t, round(on ? ACCENT : chip, 12), 12);
        t.setOnClickListener(v -> go.run());
        return t;
    }

    private View pageCalibDetail() {
        Calib.load(a);
        if (calibSel == null) calibEditing = false;
        LinearLayout b = scaffold("터치 범위 상세 설정", calibSel == null ? "색칠된 곳이 그 키로 인식되는 범위예요. 키를 눌러 고른 뒤 옮기거나 크기를 바꾸거나 직접 그려요"
                : "색칠된 곳이 " + Calib.nameOf(calibSel) + " 키로 인식되는 범위예요", true);
        LinearLayout c = card(b, null);
        switchRow(c, "터치 보정 사용", "꺼 두면 아래 범위는 저장만 되고 쓰이지 않아요", Calib.isOn(a), on -> Calib.setOn(a, on));
        LinearLayout mc = card(b, calibSel == null ? "인식 범위" : Calib.nameOf(calibSel) + " 키 인식 범위");
        final Calib.MapView mv = new Calib.MapView(a, true, text, line, ACCENT, dark);
        mv.sel = calibSel;
        mv.editing = calibEditing;
        mv.tool = calibTool;
        mv.brushDp = calibBrush;
        mv.pick = id -> root.post(() -> { calibSel = (id == null || id.equals(calibSel)) ? null : id; calibEditing = false; Calib.MapView.resetView(); show("calib_detail", false); });

        final TextView[] undoBtn = new TextView[1];
        if (calibEditing) {                                       // 편집 도구: 지도 바로 위에 떠 있게
            LinearLayout tools = row();
            tools.setPadding(dp(6), dp(6), dp(6), dp(6));
            tools.addView(chipBtn("그리기", calibTool == 0, () -> { calibTool = 0; show("calib_detail", false); }), mlp(-2, -2, 0, 0, 6, 0));
            tools.addView(chipBtn("지우개", calibTool == 1, () -> { calibTool = 1; show("calib_detail", false); }), mlp(-2, -2, 0, 0, 6, 0));
            undoBtn[0] = chipBtn("되돌리기", false, () -> { Calib.undo(a); mv.refresh(); if (undoBtn[0] != null) undoBtn[0].setAlpha(Calib.canUndo() ? 1f : 0.4f); });
            undoBtn[0].setAlpha(Calib.canUndo() ? 1f : 0.4f);
            tools.addView(undoBtn[0], mlp(-2, -2, 0, 0, 6, 0));
            tools.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
            TextView done = chipBtn("완료", true, () -> { calibEditing = false; Calib.clearUndo(); Calib.MapView.resetView(); show("calib_detail", false); });
            tools.addView(done);
            mc.addView(tools);
            LinearLayout sz = row();
            sz.setPadding(dp(6), 0, dp(6), dp(6));
            sz.addView(tv("붓 크기", 13, sub, true), mlp(-2, -2, 6, 0, 10, 0));
            float[] sizes = {8, 14, 22};
            String[] names = {"가늘게", "보통", "굵게"};
            for (int i = 0; i < 3; i++) {
                final float bs = sizes[i];
                sz.addView(chipBtn(names[i], Math.abs(calibBrush - bs) < 0.1f, () -> { calibBrush = bs; show("calib_detail", false); }), mlp(-2, -2, 0, 0, 6, 0));
            }
            sz.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
            final TextView dots = chipBtn(Calib.MapView.hideDots ? "점 보기" : "점 숨기기", false, () -> {});
            dots.setOnClickListener(v -> { mv.setHideDots(!Calib.MapView.hideDots); dots.setText(Calib.MapView.hideDots ? "점 보기" : "점 숨기기"); });
            sz.addView(dots);
            mc.addView(sz);
        }
        if (calibSel != null) {                                   // 확대·축소·이동
            LinearLayout nav = row();
            nav.setPadding(dp(6), 0, dp(6), dp(6));
            nav.addView(chipBtn("−", false, () -> mv.zoomBy(1 / 1.5f)), mlp(dp(44), -2, 0, 0, 4, 0));
            nav.addView(chipBtn("+", false, () -> mv.zoomBy(1.5f)), mlp(dp(44), -2, 0, 0, 10, 0));
            nav.addView(chipBtn("◀", false, () -> mv.panBy(-0.25f, 0)), mlp(dp(40), -2, 0, 0, 4, 0));
            nav.addView(chipBtn("▲", false, () -> mv.panBy(0, -0.25f)), mlp(dp(40), -2, 0, 0, 4, 0));
            nav.addView(chipBtn("▼", false, () -> mv.panBy(0, 0.25f)), mlp(dp(40), -2, 0, 0, 4, 0));
            nav.addView(chipBtn("▶", false, () -> mv.panBy(0.25f, 0)), mlp(dp(40), -2, 0, 0, 4, 0));
            nav.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
            nav.addView(chipBtn("전체", false, mv::fit));
            mc.addView(nav);
        }
        mv.onEdited = () -> { if (undoBtn[0] != null) undoBtn[0].setAlpha(Calib.canUndo() ? 1f : 0.4f); };
        mc.addView(mv, new LinearLayout.LayoutParams(-1, -2));

        if (calibSel == null) {
            mc.addView(tv("작은 점은 옮겨진 범위의 중심이에요.", 12.5f, sub, false), mlp(-1, -2, 12, 6, 12, 8));
            note(b, "위 자판에서 키를 하나 눌러 주세요.");
        } else {
            final String id = calibSel;
            int[] st = Calib.stats().get(id);
            int into = 0;
            for (Calib.Sample sm : Calib.samples) if (!sm.t.equals(id) && sm.p.equals(id)) into++;
            String nm = Calib.nameOf(id);
            mc.addView(tv("● 초록: " + nm + " 키를 제대로 누른 터치" + (st == null ? "" : " " + (st[0] - st[1]) + "번")
                    + "\n● 빨강: " + nm + " 키를 누르려다 다른 키가 눌린 터치" + (st == null ? "" : " " + st[1] + "번")
                    + "\n○ 파랑: 다른 키를 누르려다 " + nm + " 키로 눌린 터치 " + into + "번", 12.5f, sub, false), mlp(-1, -2, 12, 6, 12, 8));
            if (calibEditing) {
                note(b, calibTool == 0 ? "색칠된 범위 밖을 손가락으로 칠하면 그만큼 " + nm + " 키 범위가 넓어져요. 빨간 점이 색칠 안으로 들어오게 그려 보세요. 다른 키의 가운데 부분은 칠해지지 않아요."
                        : "색칠된 곳을 문지르면 " + nm + " 키 범위에서 빠지고, 가장 가까운 다른 키가 그 자리를 가져가요.");
            } else {
                b.addView(primaryBtn("직접 그리기", () -> { calibEditing = true; Calib.clearUndo(); show("calib_detail", false); }), mlp(-1, -2, 0, 14, 0, 0));
            }
            float[] q = Calib.param(id);
            LinearLayout k = card(b, nm + " 키 이동 · 크기");
            sliderF(k, "좌우 이동", -40, 40, Math.round(q[0] * 100), v -> v == 0 ? "가운데" : (v < 0 ? "왼쪽 " : "오른쪽 ") + Math.abs(v) + "%",
                    v -> { float[] p = Calib.param(id); Calib.setParam(a, id, v / 100f, p[1], p[2]); mv.refresh(); });
            divider(k);
            sliderF(k, "상하 이동", -40, 40, Math.round(q[1] * 100), v -> v == 0 ? "가운데" : (v < 0 ? "위 " : "아래 ") + Math.abs(v) + "%",
                    v -> { float[] p = Calib.param(id); Calib.setParam(a, id, p[0], v / 100f, p[2]); mv.refresh(); });
            divider(k);
            sliderF(k, "범위 크기", 70, 140, Math.round(q[2] * 100), v -> v + "%",
                    v -> { float[] p = Calib.param(id); Calib.setParam(a, id, p[0], p[1], v / 100f); mv.refresh(); });
            k.addView(tv("이동은 키 크기 기준이에요. 직접 그린 범위는 이동·크기보다 먼저 적용돼요.", 13, sub, false), mlp(-1, -2, 14, 0, 14, 10));
            b.addView(ghostBtn(nm + " 키 기본값으로 (그린 범위 포함)", text, () -> {
                Calib.setParam(a, id, 0, 0, 1); Calib.clearKeyPaint(a, id); calibEditing = false; show("calib_detail", false);
            }), mlp(-1, -2, 0, 16, 0, 0));
        }
        b.addView(ghostBtn("모든 키 기본값으로", RED, () -> {
            Calib.resetParams(a);
            calibEditing = false;
            Toast.makeText(a, "모든 키를 기본 범위로 되돌렸어요 (그린 범위 포함)", Toast.LENGTH_SHORT).show();
            show("calib_detail", false);
        }), mlp(-1, -2, 0, 10, 0, 0));
        if (!Calib.samples.isEmpty()) { LinearLayout rc = card(b, "추천 범위 비교"); addCompare(rc); }
        addRecommendButtons(b, "calib_detail");
        if (Calib.painted > 0) note(b, "\"모든 키에 추천 범위 적용\"은 직접 그린 범위를 남겨 둬요. 그린 범위가 맞지 않으면 \"그린 범위 지우고\"를 고르세요.");
        return (View) b.getParent();
    }

    // ================================================================== 오타 제안 · 자동 교정
    private String wordQuery = "", ruleQuery = "";

    private EditText field(String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setHintTextColor(sub);
        e.setTextColor(text);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setBackground(round(chip, 12));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private void onText(EditText e, final Runnable r) {
        e.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { r.run(); }
        });
    }

    private View pageTypo() {
        Typo.load(a);
        LinearLayout b = scaffold("오타 제안 · 자동 교정", "쿼티 한글에서 스페이스 대신 ㅠ·ㅜ 같은 윗줄 키가 눌린 오타를 찾아요", true);
        LinearLayout c = card(b, null);
        switchRow(c, "오타 제안", "고칠 말을 툴바에 보여주고, 누르면 바꿔요", Typo.on, v -> { Typo.on = v; Typo.saveFlags(a); });
        divider(c);
        switchRow(c, "ㅠ·ㅜ 오타 자동 교정", "스페이스 대신 ㅠ·ㅜ가 눌린 오타(장고규가기 → 장곡 가기)는 확실하면 띄어쓰기·마침표·엔터를 누를 때 바로 고쳐요. 바로 지우기를 누르면 되돌아가요", Typo.autoUU, v -> { Typo.autoUU = v; Typo.saveFlags(a); });
        divider(c);
        switchRow(c, "그 밖의 오타도 자동 교정", "아주 확실할 때만 띄어쓰기·마침표·엔터를 누르는 순간 스스로 바꿔요. 바로 지우기를 누르면 되돌아가요", Typo.auto, v -> { Typo.auto = v; Typo.saveFlags(a); });
        divider(c);
        switchRow(c, "자동 학습", "지우고 다시 친 글자로 오타를 모으고, 고친 터치를 터치 보정 기록에 더해요", Typo.learn, v -> { Typo.learn = v; Typo.saveFlags(a); });
        LinearLayout m = card(b, "내 오타");
        navRow(m, LineIcon.PENCIL, "자동 학습한 오타", Typo.learned.isEmpty() ? "지우고 다시 친 오타가 여기에 모여요" : Typo.learned.size() + "개 · 눌러서 자동 교정에 등록", () -> { learnQuery = ""; open("typo_learned"); });
        divider(m);
        navRow(m, LineIcon.SWAP, "자동 교정", Typo.rules.isEmpty() ? "오타 → 바꿀 말을 정해 두기" : Typo.rules.size() + "개", () -> { ruleQuery = ""; open("typo_rules"); });
        Spell.load(a);
        LinearLayout sc = card(b, "맞춤법");
        switchRow(sc, "맞춤법 제안", "자주 틀리는 맞춤법(되요 → 돼요, 몇일 → 며칠 등)을 툴바에 보여주고, 누르면 바꿔요. 천지인·나랏글·쿼티 모든 한글 자판에서 동작하고, 자동으로 바꾸지는 않아요",
                Spell.on, v -> { Spell.on = v; Spell.save(a); });
        divider(sc);
        int spellOff = 0;
        for (Spell.Item it : Spell.items) if (!Spell.enabled(it)) spellOff++;
        navRow(sc, LineIcon.SWAP, "맞춤법 목록", Spell.items.size() + "개" + (spellOff > 0 ? " · 꺼짐 " + spellOff + "개" : ""), () -> { spellQuery = ""; spellOffOnly = false; spellCat = null; open("spell"); });
        note(b, "비밀번호·이메일·주소 입력칸에서는 제안도 학습도 하지 않아요. 배운 내용은 이 폰 안에만 저장돼요 (이 앱은 인터넷 권한이 없어요).");
        note(b, "ㅠㅠ, ㅜㅜ 처럼 같은 키를 겹쳐 친 것은 건드리지 않아요.");
        note(b, "오타 제안과 맞춤법 제안이 같이 생기면 오타 제안을 먼저 보여줘요.");
        return (View) b.getParent();
    }

    // ================================================================== 자동 학습한 오타
    private String learnQuery = "";

    /**
     * 자동 교정 등록: 자음·모음만 / 한 글자 오타는 막고, 이미 이 오타를 고치는 규칙이 있으면 (보ㅓ 가 있는데 해보ㅓ) 물어본다.
     */
    private void tryAddRule(final String typo, final String right, final Runnable done) {
        if (Typo.jamoOnly(typo)) { Toast.makeText(a, "자음·모음만으로 된 오타는 등록할 수 없어요 (그 글자를 칠 때마다 바뀌어 버려요)", Toast.LENGTH_LONG).show(); return; }
        if (typo.length() < 2) { Toast.makeText(a, "한 글자 오타는 등록할 수 없어요 (앞뒤 문맥 없이는 오타인지 알 수 없고, 그 글자로 시작하는 말이 모두 바뀌어요)", Toast.LENGTH_LONG).show(); return; }
        final Runnable add = () -> {
            if (!Typo.addRule(typo, right)) { Toast.makeText(a, "등록할 수 없는 오타예요", Toast.LENGTH_SHORT).show(); return; }
            Typo.saveData(a);
            done.run();
        };
        String by = Typo.rules.containsKey(typo) ? null : Typo.coveredBy(typo);
        if (by == null) { add.run(); return; }
        new android.app.AlertDialog.Builder(a)
                .setMessage("이미 \"" + by + "\"가 등록되어 있어서 \"" + typo + "\"도 고쳐져요.\n그래도 등록하시겠습니까?")
                .setPositiveButton("등록", (d, w) -> add.run())
                .setNegativeButton("취소", null)
                .show();
    }

    private View pageTypoLearned() {
        Typo.load(a);
        Typo.cleanLearned();
        LinearLayout b = scaffold("자동 학습한 오타", "지우기로 고친 오타를 모았어요. 자동 교정에 등록하면 다음부터 바로 고쳐져요", true);
        LinearLayout lc = card(b, null);
        final EditText q = field("검색");
        q.setText(learnQuery);
        lc.addView(q, mlp(-1, -2, 8, 8, 8, 6));
        final LinearLayout list = col();
        lc.addView(list);
        onText(q, () -> { learnQuery = q.getText().toString().trim(); fillLearned(list); });
        fillLearned(list);
        note(b, "예) 과를 치려다 고ㅓ가 써져서 지우기로 ㅓ를 지우고 ㅏ를 치면 \"고ㅓ → 과\"가 모여요. 지운 글자 중 바뀐 것이 원래 키와 바로 붙은 키일 때만 모아요.");
        note(b, "삭제: 목록에서 지워요 (같은 오타가 또 모이면 다시 올라와요). 앞으로 무시: 목록에서 사라지고 같은 오타가 또 모여도 올라오지 않아요. 자동 교정 등록: 자동 교정 목록으로 옮겨요.");
        note(b, "모으지 않는 것: ㄹ → ㅎ 같은 자음·모음, 키 → 티 · 인 → 안 같은 한 글자 오타 (앞뒤 문맥 없이는 오타인지 알 수 없어서), 이미 자동 교정이 고치는 오타.");
        note(b, "보ㅓ 처럼 깨진 글자가 든 오타는 바뀐 부분만 모아요: 해보ㅓ → 해봐 는 보ㅓ → 봐 로. 보ㅓ → 봐 하나만 등록하면 해보ㅓ·보ㅓ서 등도 모두 고쳐져요.");
        String diag = "이번에 키보드를 켠 뒤 (쿼티 한글) 지우기 " + FixLearn.nBackspace + "번 · 지우고 다시 친 것 " + FixLearn.nChecked + "번 확인 · " + FixLearn.nLearned + "번 배움"
                + (FixLearn.lastSkip.isEmpty() ? "" : " · 최근 건너뛴 이유: " + FixLearn.lastSkip);
        note(b, diag);
        if (!Typo.ignored.isEmpty()) {
            // 무시한 오타 보기: 누르면 아래로 목록이 펼쳐지고, 항목마다 [무시 목록에서 빼기]
            final LinearLayout ig = col();
            final TextView[] tog = new TextView[1];
            tog[0] = ghostBtn("무시한 오타 보기 (" + Typo.ignored.size() + "개)", ACCENT, () -> {
                ignoredOpen = !ignoredOpen;
                fillIgnored(ig, tog[0]);
            });
            b.addView(tog[0], mlp(-1, -2, 0, 12, 0, 0));
            b.addView(ig);
            fillIgnored(ig, tog[0]);
        }
        return (View) b.getParent();
    }

    private boolean ignoredOpen;

    private void fillIgnored(final LinearLayout box, final TextView tog) {
        box.removeAllViews();
        tog.setText((ignoredOpen ? "무시한 오타 접기" : "무시한 오타 보기") + " (" + Typo.ignored.size() + "개)");
        if (!ignoredOpen) return;
        LinearLayout c = card(box, "무시한 오타");
        List<String> ks = new ArrayList<>(Typo.ignored);
        java.util.Collections.sort(ks);
        if (ks.isEmpty()) c.addView(tv("무시한 오타가 없어요.", 14, sub, false), mlp(-1, -2, 14, 10, 14, 12));
        for (final String k : ks) {
            int t = k.indexOf('\t');
            String title = t < 0 ? k : k.substring(0, t) + "  →  " + k.substring(t + 1);
            c.addView(toggleRow(title, "앞으로 모으지 않음", "무시 목록에서 빼기", ACCENT, () -> {
                Typo.ignored.remove(k);
                Typo.saveData(a);
                fillIgnored(box, tog);
            }));
        }
    }

    private void fillLearned(final LinearLayout list) {
        list.removeAllViews();
        List<String> ks = new ArrayList<>();
        for (String k : Typo.learned.keySet()) if (learnQuery.isEmpty() || k.contains(learnQuery)) ks.add(k);
        java.util.Collections.sort(ks, (x, y) -> {
            int cx = Typo.learned.get(x), cy = Typo.learned.get(y);
            return cx != cy ? Integer.compare(cy, cx) : x.compareTo(y);
        });
        if (ks.isEmpty()) list.addView(tv(Typo.learned.isEmpty() ? "아직 없어요. 쿼티 한글로 치다가 지우기로 오타를 고치면 여기에 모여요." : "'" + learnQuery + "' 에 맞는 오타가 없어요.", 14, sub, false), mlp(-1, -2, 14, 10, 14, 12));
        for (int i = 0; i < ks.size() && i < 200; i++) {
            final String k = ks.get(i);
            int tab = k.indexOf('\t');
            if (tab < 0) continue;
            final String typo = k.substring(0, tab), right = k.substring(tab + 1);
            LinearLayout box = col();
            box.setPadding(dp(14), dp(8), dp(6), dp(4));
            LinearLayout t = row();
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.addView(tv(typo + "  →  " + right, 15, text, true), new LinearLayout.LayoutParams(0, -2, 1f));
            t.addView(tv(Typo.learned.get(k) + "번 고침", 12.5f, sub, false), mlp(-2, -2, 8, 0, 8, 0));
            box.addView(t);
            LinearLayout r = row();
            r.setGravity(Gravity.END);
            box.addView(r, mlp(-1, -2, 0, 2, 0, 0));
            TextView del = tv("삭제", 13, sub, true);
            del.setPadding(dp(8), dp(8), dp(8), dp(8));
            ripple(del, round(Color.TRANSPARENT, 12), 12);
            del.setOnClickListener(v -> { Typo.learned.remove(k); Typo.saveData(a); fillLearned(list); });
            r.addView(del);
            TextView ig = tv("앞으로 무시", 13, RED, true);
            ig.setPadding(dp(8), dp(8), dp(8), dp(8));
            ripple(ig, round(Color.TRANSPARENT, 12), 12);
            ig.setOnClickListener(v -> { Typo.learned.remove(k); Typo.ignored.add(k); Typo.saveData(a); fillLearned(list); });
            r.addView(ig);
            TextView reg = tv("자동 교정 등록", 13, ACCENT, true);
            reg.setPadding(dp(8), dp(8), dp(8), dp(8));
            ripple(reg, round(Color.TRANSPARENT, 12), 12);
            reg.setOnClickListener(v -> tryAddRule(typo, right, () -> {
                Typo.learned.remove(k);
                Typo.cleanLearned();          // 새 규칙이 고치게 된 것도 목록에서 뺌
                Typo.saveData(a);
                Toast.makeText(a, typo + " → " + right + " 자동 교정에 등록했어요", Toast.LENGTH_SHORT).show();
                fillLearned(list);
            }));
            r.addView(reg);
            list.addView(box);
        }
        if (ks.size() > 200) list.addView(tv("외 " + (ks.size() - 200) + "개 · 검색으로 찾아 주세요.", 13, sub, false), mlp(-1, -2, 14, 8, 14, 10));
    }

    // ================================================================== 맞춤법 목록
    private String spellQuery = "";
    private boolean spellOffOnly;

    private String spellCat;      // null = 전체

    private View pageSpell() {
        Spell.load(a);
        // 고정: 제목 · 검색 · 칩 / 가운데: 목록만 스크롤 (남는 높이 전부) / 맨 아래: 꺼진 항목 모두 다시 켜기
        LinearLayout pg = col();
        pg.setPadding(dp(18), dp(6), dp(18), dp(12));
        android.widget.ImageView back = icon(LineIcon.BACK, text, 10);
        ripple(back, round(Color.TRANSPARENT, 22), 22);
        back.setOnClickListener(v -> back());
        pg.addView(back, mlp(dp(44), dp(44), -8, 0, 0, 4));
        pg.addView(tv("맞춤법 목록", 26, text, true), mlp(-1, -2, 4, 0, 4, 0));
        pg.addView(tv("표준어에 없어서 언제나 틀린 형태만 모았어요. 항목을 끄면 다시 제안하지 않아요", 14, sub, false), mlp(-1, -2, 4, 4, 4, 0));
        LinearLayout lc = card(pg, null);
        final EditText q = field("검색 (예: 되요, 베개)");
        q.setText(spellQuery);
        lc.addView(q, mlp(-1, -2, 8, 8, 8, 6));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        chips.addView(chipBtn("전체", !spellOffOnly && spellCat == null, () -> { spellOffOnly = false; spellCat = null; show("spell", false); }), mlp(-2, -2, 0, 0, 8, 0));
        chips.addView(chipBtn("꺼진 것만", spellOffOnly, () -> { spellOffOnly = true; spellCat = null; show("spell", false); }), mlp(-2, -2, 0, 0, 8, 0));
        List<String> cats = new ArrayList<>();
        for (Spell.Item it : Spell.items) if (!cats.contains(it.cat)) cats.add(it.cat);
        for (final String c : cats)
            chips.addView(chipBtn(c, !spellOffOnly && c.equals(spellCat), () -> { spellOffOnly = false; spellCat = c; show("spell", false); }), mlp(-2, -2, 0, 0, 8, 0));
        hs.addView(chips);
        lc.addView(hs, mlp(-1, -2, 8, 2, 8, 8));
        final ScrollView sv = new ScrollView(a);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout inner = col();
        LinearLayout listCard = card(inner, null);
        final LinearLayout list = col();
        listCard.addView(list);
        note(inner, "바꾼 직후 지우기를 누르면 되돌아가고, 그 항목은 \"되돌려서 꺼짐\"이 돼요. 여기서 다시 켤 수 있어요.");
        note(inner, "'되/돼'·'안/않'·'로서/로써'처럼 앞뒤 문맥을 봐야 하는 맞춤법과 띄어쓰기는 검사하지 않아요. ㅔ·ㅐ 분류에는 재일/제일처럼 둘 다 있는 말은 넣지 않았어요.");
        note(inner, "자료: 국립국어원 표준국어대사전·한국어 어문 규범(한글 맞춤법, 표준어 규정, 외래어 표기법), 국립국어원 '틀리기 쉬운 우리말' 안내 등.");
        sv.addView(inner, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f);
        sl.setMargins(0, dp(4), 0, 0);
        pg.addView(sv, sl);
        boolean any = !Spell.off.isEmpty() || !Spell.block.isEmpty();
        TextView all = ghostBtn(any ? "꺼진 항목 모두 다시 켜기 (" + (Spell.off.size() + Spell.block.size()) + "개)" : "꺼진 항목이 없어요", any ? ACCENT : sub,
                () -> { if (Spell.off.isEmpty() && Spell.block.isEmpty()) return; Spell.off.clear(); Spell.block.clear(); Spell.save(a); show("spell", false); });
        pg.addView(all, mlp(-1, -2, 0, 8, 0, 0));
        onText(q, () -> { spellQuery = q.getText().toString().trim(); fillSpell(list); });
        fillSpell(list);
        root.setTag(sv);
        pageScroll = sv;
        return pg;
    }

    private void fillSpell(final LinearLayout list) {
        list.removeAllViews();
        List<Spell.Item> ks = new ArrayList<>();
        for (Spell.Item it : Spell.items) {
            if (spellOffOnly && Spell.enabled(it)) continue;
            if (spellCat != null && !spellCat.equals(it.cat)) continue;
            boolean hit = spellQuery.isEmpty() || it.disp.contains(spellQuery);
            for (int i = 0; !hit && i < it.wrong.length; i++) hit = it.wrong[i].contains(spellQuery) || it.right[i].contains(spellQuery);
            if (hit) ks.add(it);
        }
        if (ks.isEmpty()) list.addView(tv(Spell.items.isEmpty() ? "목록을 불러오지 못했어요." : spellOffOnly && spellQuery.isEmpty() ? "꺼진 항목이 없어요." : "'" + spellQuery + "' 에 맞는 항목이 없어요.", 14, sub, false), mlp(-1, -2, 14, 10, 14, 12));
        for (int i = 0; i < ks.size() && i < 200; i++) {
            final Spell.Item it = ks.get(i);
            final boolean en = Spell.enabled(it);
            String st = en ? "켜짐" : Spell.block.contains(it.id) ? "되돌려서 꺼짐" : "꺼짐";
            String desc = st + (it.wrong.length > 1 ? " · 형태 " + it.wrong.length + "개" : "");
            list.addView(toggleRow(it.disp, desc, en ? "끄기" : "켜기", en ? RED : ACCENT, () -> {
                if (en) Spell.off.add(it.id); else { Spell.off.remove(it.id); Spell.block.remove(it.id); }
                Spell.save(a);
                fillSpell(list);
            }));
        }
        if (ks.size() > 200) list.addView(tv("외 " + (ks.size() - 200) + "개 · 검색으로 찾아 주세요.", 13, sub, false), mlp(-1, -2, 14, 8, 14, 10));
    }

    private View toggleRow(String title, String desc, String btn, int color, Runnable go) {
        LinearLayout r = row();
        r.setPadding(dp(14), dp(8), dp(8), dp(8));
        LinearLayout t = col();
        t.addView(tv(title, 15, text, true));
        if (desc != null) t.addView(tv(desc, 12.5f, sub, false), mlp(-1, -2, 0, 2, 0, 0));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView d = tv(btn, 13, color, true);
        d.setPadding(dp(12), dp(8), dp(12), dp(8));
        ripple(d, round(Color.TRANSPARENT, 12), 12);
        d.setOnClickListener(v -> go.run());
        r.addView(d);
        return r;
    }

    private View pageTypoWords() {
        Typo.load(a);
        LinearLayout b = scaffold("내 단어", "이름·지명·줄임말을 넣어 두면 그 단어 쪽으로 고쳐줘요", true);
        LinearLayout add = card(b, "단어 추가");
        LinearLayout r = row();
        r.setPadding(dp(8), dp(8), dp(8), dp(8));
        final EditText in = field("예: 장곡");
        r.addView(in, new LinearLayout.LayoutParams(0, -2, 1f));
        final LinearLayout list = col();
        TextView ok = primaryBtn("추가", () -> {
            String w = in.getText().toString().trim();
            if (w.length() < 2 || w.contains(" ")) { Toast.makeText(a, "띄어쓰기 없는 두 글자 이상의 단어를 넣어 주세요", Toast.LENGTH_SHORT).show(); return; }
            Typo.words.put(w, Typo.MANUAL);
            Typo.saveData(a);
            in.setText("");
            fillWords(list);
        });
        ok.setPadding(dp(16), dp(10), dp(16), dp(10));
        r.addView(ok, mlp(-2, -2, 8, 0, 0, 0));
        add.addView(r);
        LinearLayout lc = card(b, "목록");
        final EditText q = field("검색");
        q.setText(wordQuery);
        lc.addView(q, mlp(-1, -2, 8, 8, 8, 6));
        lc.addView(list);
        onText(q, () -> { wordQuery = q.getText().toString().trim(); fillWords(list); });
        fillWords(list);
        note(b, "자동으로 배운 단어는 3번 넘게 쳤을 때부터 쓰여요. 오타가 잘못 들어와 있으면 지워 주세요.");
        b.addView(ghostBtn("자동으로 배운 단어 모두 지우기", RED, () -> {
            for (java.util.Iterator<java.util.Map.Entry<String, Integer>> it = Typo.words.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue() < Typo.MANUAL) it.remove();
            Typo.saveData(a);
            fillWords(list);
        }), mlp(-1, -2, 0, 16, 0, 0));
        return (View) b.getParent();
    }

    private void fillWords(final LinearLayout list) {
        list.removeAllViews();
        List<String> ks = new ArrayList<>();
        for (String k : Typo.words.keySet()) if (wordQuery.isEmpty() || k.contains(wordQuery)) ks.add(k);
        java.util.Collections.sort(ks, (x, y) -> {
            int cx = Typo.words.get(x), cy = Typo.words.get(y);
            return cx != cy ? Integer.compare(cy, cx) : x.compareTo(y);
        });
        if (ks.isEmpty()) list.addView(tv(wordQuery.isEmpty() ? "아직 단어가 없어요." : "'" + wordQuery + "' 에 맞는 단어가 없어요.", 14, sub, false), mlp(-1, -2, 14, 10, 14, 12));
        for (int i = 0; i < ks.size() && i < 200; i++) {
            final String k = ks.get(i);
            int n = Typo.words.get(k);
            list.addView(itemRow(k, n >= Typo.MANUAL ? "직접 등록" : "자동 학습 · " + n + "번", () -> { Typo.words.remove(k); Typo.saveData(a); fillWords(list); }));
        }
        if (ks.size() > 200) list.addView(tv("외 " + (ks.size() - 200) + "개 · 검색으로 찾아 주세요.", 13, sub, false), mlp(-1, -2, 14, 8, 14, 10));
    }

    private View itemRow(String title, String desc, Runnable del) {
        LinearLayout r = row();
        r.setPadding(dp(14), dp(8), dp(8), dp(8));
        LinearLayout t = col();
        t.addView(tv(title, 15, text, true));
        if (desc != null) t.addView(tv(desc, 12.5f, sub, false), mlp(-1, -2, 0, 2, 0, 0));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView d = tv("삭제", 13, RED, true);
        d.setPadding(dp(12), dp(8), dp(12), dp(8));
        ripple(d, round(Color.TRANSPARENT, 12), 12);
        d.setOnClickListener(v -> del.run());
        r.addView(d);
        return r;
    }

    private int ruleSort;        // 0 가나다, 1 많이 고친 순, 2 적게 고친 순

    private View pageTypoRules() {
        Typo.load(a);
        LinearLayout b = scaffold("자동 교정", null, true);
        b.addView(tv("오타를 치고 띄어쓰기·마침표·엔터를 누르면 바꿀 말로 바로 바뀌어요. 어절이 오타로 시작하면 뒤 글자는 그대로 두고 바꾸고 (고ㅓ일 → 과일), "
                + "'ㅓ'처럼 홀로 선 자모가 든 오타는 어절 가운데에 있어도 바꿔요 (사고ㅓ주스 → 사과주스).", 12.5f, sub, false), mlp(-1, -2, 4, 4, 4, 0));
        // 한 줄: 오타 · 바꿀 말 · 등록
        LinearLayout add = card(b, null);
        LinearLayout ar = row();
        ar.setGravity(Gravity.CENTER_VERTICAL);
        ar.setPadding(dp(4), dp(4), dp(4), dp(4));
        final EditText from = field("오타");
        final EditText to = field("바꿀 말");
        ar.addView(from, new LinearLayout.LayoutParams(0, -2, 1f));
        ar.addView(to, mlp(0, -2, 6, 0, 6, 0));
        ((LinearLayout.LayoutParams) to.getLayoutParams()).weight = 1f;
        final LinearLayout list = col();
        TextView ok = primaryBtn("등록", () -> {
            String f = from.getText().toString().trim(), t = to.getText().toString().trim();
            if (f.isEmpty() || t.isEmpty() || f.equals(t) || f.length() > 30) { Toast.makeText(a, "오타와 바꿀 말을 서로 다르게 넣어 주세요 (오타는 30자까지)", Toast.LENGTH_SHORT).show(); return; }
            tryAddRule(f, t, () -> { from.setText(""); to.setText(""); fillRules(list); });
        });
        ok.setPadding(dp(16), dp(10), dp(16), dp(10));
        ar.addView(ok, new LinearLayout.LayoutParams(-2, -2));
        add.addView(ar);
        // 목록 제목 + 정렬
        LinearLayout hr = row();
        hr.setGravity(Gravity.CENTER_VERTICAL);
        hr.addView(tv("목록 " + Typo.rules.size() + "개", 13, sub, true), new LinearLayout.LayoutParams(0, -2, 1f));
        hr.addView(chipBtn("많이 고친 순", ruleSort == 1, () -> { ruleSort = ruleSort == 1 ? 0 : 1; show("typo_rules", false); }), mlp(-2, -2, 0, 0, 6, 0));
        hr.addView(chipBtn("적게 고친 순", ruleSort == 2, () -> { ruleSort = ruleSort == 2 ? 0 : 2; show("typo_rules", false); }));
        b.addView(hr, mlp(-1, -2, 8, 18, 0, 8));
        LinearLayout lc = card(b, null);
        ((LinearLayout.LayoutParams) lc.getLayoutParams()).topMargin = 0;
        final EditText q = field("검색");
        q.setText(ruleQuery);
        lc.addView(q, mlp(-1, -2, 8, 8, 8, 6));
        lc.addView(list);
        onText(q, () -> { ruleQuery = q.getText().toString().trim(); fillRules(list); });
        fillRules(list);
        return (View) b.getParent();
    }

    private void fillRules(final LinearLayout list) {
        list.removeAllViews();
        List<String> ks = new ArrayList<>();
        for (String k : Typo.rules.keySet()) if (ruleQuery.isEmpty() || k.contains(ruleQuery) || Typo.rules.get(k).contains(ruleQuery)) ks.add(k);
        java.util.Collections.sort(ks);
        if (ruleSort != 0) java.util.Collections.sort(ks, (x, y) -> {        // 고친 횟수 순 (같으면 가나다)
            int hx = Typo.ruleHits.containsKey(x) ? Typo.ruleHits.get(x) : 0, hy = Typo.ruleHits.containsKey(y) ? Typo.ruleHits.get(y) : 0;
            return hx != hy ? (ruleSort == 1 ? Integer.compare(hy, hx) : Integer.compare(hx, hy)) : x.compareTo(y);
        });
        if (ks.isEmpty()) list.addView(tv(ruleQuery.isEmpty() ? "아직 없어요." : "'" + ruleQuery + "' 에 맞는 것이 없어요.", 14, sub, false), mlp(-1, -2, 14, 10, 14, 12));
        for (int i = 0; i < ks.size() && i < 200; i++) {
            final String k = ks.get(i);
            Integer hits = Typo.ruleHits.get(k);
            String desc = (Typo.jamoOnly(k) ? "자음·모음만으로 된 오타라 동작하지 않아요 · " : "") + "등록 뒤 " + (hits == null ? 0 : hits) + "번 고침";
            list.addView(itemRow(k + "  →  " + Typo.rules.get(k), desc, () -> { Typo.removeRule(k); Typo.saveData(a); fillRules(list); }));
        }
        if (ks.size() > 200) list.addView(tv("외 " + (ks.size() - 200) + "개 · 검색으로 찾아 주세요.", 13, sub, false), mlp(-1, -2, 14, 8, 14, 10));
    }
}
