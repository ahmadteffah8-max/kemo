package com.kemo.calc;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity implements GlassButton.Listener {
    private static final int MAX_LEN = 64;

    private SoundManager sound;
    private DisplayView display;
    private KemoView kemo;
    private FxView fx;
    private final StringBuilder expr = new StringBuilder();
    private boolean justEvaluated = false;
    private boolean error = false;
    private int presses = 0;

    private static final String[][] LAYOUT = {
            {"C", "(", ")", "DEL"},
            {"7", "8", "9", "÷"},
            {"4", "5", "6", "×"},
            {"1", "2", "3", "−"},
            {"%", "0", ".", "+"},
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sound = SoundManager.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);
        root.setFitsSystemWindows(true);
        root.addView(new BackgroundView(this), new FrameLayout.LayoutParams(-1, -1));

        int sidePad = (int) Theme.dp(this, 22), vPad = (int) Theme.dp(this, 20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setWeightSum(100f);
        col.setPadding(sidePad, vPad, sidePad, vPad);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // --- kemo band ---
        FrameLayout band = new FrameLayout(this);
        kemo = new KemoView(this);
        band.addView(kemo, new FrameLayout.LayoutParams(-1, -1));
        GlassButton sndBtn = new GlassButton(this, "♪", "SND", GlassButton.FN);
        sndBtn.setListener(this, false);
        int sb = (int) Theme.dp(this, 46);
        FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(sb, sb, Gravity.TOP | Gravity.END);
        band.addView(sndBtn, sbp);
        col.addView(band, new LinearLayout.LayoutParams(-1, 0, 25f));

        // --- display ---
        display = new DisplayView(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 0, 15f);
        dlp.topMargin = (int) Theme.dp(this, 4);
        dlp.bottomMargin = (int) Theme.dp(this, 6);
        col.addView(display, dlp);

        // --- keypad ---
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setWeightSum(6f);
        for (String[] row : LAYOUT) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setWeightSum(4f);
            for (String k : row) {
                GlassButton g = new GlassButton(this, labelFor(k), k, variantFor(k));
                g.setListener(this, "DEL".equals(k));
                r.addView(g, new LinearLayout.LayoutParams(0, -1, 1f));
            }
            pad.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        LinearLayout eqRow = new LinearLayout(this);
        GlassButton eq = new GlassButton(this, "=", "=", GlassButton.EQ);
        eq.setListener(this, false);
        eqRow.addView(eq, new LinearLayout.LayoutParams(-1, -1));
        pad.addView(eqRow, new LinearLayout.LayoutParams(-1, 0, 1.05f));
        col.addView(pad, new LinearLayout.LayoutParams(-1, 0, 60f));

        fx = new FxView(this);
        root.addView(fx, new FrameLayout.LayoutParams(-1, -1));
        HudFrameView hud = new HudFrameView(this);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        display.setInput("", false);
        kemo.say("よろしくね！", true, 3.2f);   // よろしくね！
    }

    private static String labelFor(String k) { return "DEL".equals(k) ? "DEL" : k; }
    private static int variantFor(String k) {
        switch (k) {
            case "÷": case "×": case "−": case "+": return GlassButton.OP;
            case "C": case "(": case ")": case "DEL": case "%": return GlassButton.FN;
            default: return GlassButton.NUM;
        }
    }

    @Override protected void onResume() { super.onResume(); sound.startMusic(); }
    @Override protected void onPause() { sound.pauseMusic(); super.onPause(); }

    // ---------------------------------------------------------------- input

    @Override public void onPress(GlassButton b, float rawX, float rawY) {
        String k = b.getKey();
        fx.ripple(rawX, rawY, b.getBaseColor());
        kemo.nudge();
        presses++;
        switch (k) {
            case "SND": showSoundDialog(); return;
            case "C": clearAll(); return;
            case "DEL": deleteLast(); return;
            case "=": evaluate(); return;
            default: break;
        }
        if (error || justEvaluated) {
            boolean continueWithResult = justEvaluated && !error && isOp(k.charAt(0));
            if (!continueWithResult) expr.setLength(0);
            justEvaluated = false; error = false;
        }
        boolean changed = false;
        char ch = k.charAt(0);
        if (ch >= '0' && ch <= '9') changed = inputDigit(ch);
        else if (ch == '.') changed = inputDot();
        else if (isOp(ch)) changed = inputOp(ch);
        else if (ch == '%') changed = inputPercent();
        else if (ch == '(') changed = inputOpen();
        else if (ch == ')') changed = inputClose();
        if (changed) {
            sound.playKey(k);
            display.setInput(expr.toString(), true);
            if (presses % 12 == 0) kemo.say("がんばれ！", false, 2f);   // がんばれ！
        }
    }

    @Override public void onLongPress(GlassButton b) {
        if ("DEL".equals(b.getKey())) clearAll();
    }

    private static boolean isOp(char c) { return c == '+' || c == '−' || c == '×' || c == '÷'; }
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private char lastChar() { return expr.length() == 0 ? '\0' : expr.charAt(expr.length() - 1); }

    private int currentNumberStart() {
        int i = expr.length();
        while (i > 0 && (isDigit(expr.charAt(i - 1)) || expr.charAt(i - 1) == '.')) i--;
        return i;
    }

    private boolean inputDigit(char d) {
        if (expr.length() >= MAX_LEN) return false;
        char l = lastChar();
        if (l == ')' || l == '%') expr.append('×');
        int st = currentNumberStart();
        if (expr.length() - st == 1 && expr.charAt(st) == '0') expr.setLength(st);   // no leading zeros
        expr.append(d);
        return true;
    }

    private boolean inputDot() {
        if (expr.length() >= MAX_LEN) return false;
        char l = lastChar();
        if (l == ')' || l == '%') expr.append('×');
        int st = currentNumberStart();
        if (expr.substring(st).indexOf('.') >= 0) return false;
        if (st == expr.length()) expr.append('0');
        expr.append('.');
        return true;
    }

    private boolean inputOp(char op) {
        int n = expr.length();
        if (n == 0) { if (op == '−') { expr.append(op); return true; } return false; }
        char l = lastChar();
        if (l == '(') { if (op == '−') { expr.append(op); return true; } return false; }
        if (l == '.') expr.setLength(n - 1);
        l = lastChar();
        if (isOp(l)) {
            if (op == '−' && (l == '×' || l == '÷')) { expr.append(op); return true; }   // 5×-3
            while (expr.length() > 0 && isOp(lastChar())) expr.setLength(expr.length() - 1);
            if (expr.length() == 0 || lastChar() == '(') {
                if (op == '−') { expr.append(op); return true; }
                return false;
            }
        }
        if (expr.length() >= MAX_LEN) return false;
        expr.append(op);
        return true;
    }

    private boolean inputPercent() {
        char l = lastChar();
        if (!(isDigit(l) || l == ')' || l == '%')) return false;
        if (expr.length() >= MAX_LEN) return false;
        expr.append('%');
        return true;
    }

    private boolean inputOpen() {
        if (expr.length() >= MAX_LEN) return false;
        expr.append('(');
        return true;
    }

    private boolean inputClose() {
        int open = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') open++; else if (c == ')') open--;
        }
        char l = lastChar();
        if (open <= 0 || !(isDigit(l) || l == ')' || l == '%')) return false;
        expr.append(')');
        return true;
    }

    private void deleteLast() {
        if (error) { expr.setLength(0); error = false; display.setInput("", false); sound.playDelete(); return; }
        justEvaluated = false;
        if (expr.length() == 0) return;
        RectF rc = new RectF();
        boolean ok = display.lastCharRect(rc);
        if (ok) {
            int[] loc = new int[2];
            display.getLocationOnScreen(loc);
            fx.pixelBurst(loc[0] + rc.left, loc[1] + rc.top, loc[0] + rc.right, loc[1] + rc.bottom, Theme.CYAN);
        }
        expr.setLength(expr.length() - 1);
        sound.playDelete();
        display.setInput(expr.toString(), false);
    }

    private void clearAll() {
        boolean had = expr.length() > 0 || error;
        if (had) {
            int[] loc = new int[2];
            display.getLocationOnScreen(loc);
            fx.vanish(loc[0], loc[1], loc[0] + display.getWidth(), loc[1] + display.getHeight());
        }
        expr.setLength(0);
        justEvaluated = false; error = false;
        sound.playClear();
        display.setInput("", false);
        if (had) kemo.say("リセット！", false, 1.8f);   // リセット！
    }

    private void evaluate() {
        if (expr.length() == 0) return;
        String e = expr.toString();
        // drop trailing operators / dot, auto-close parentheses
        StringBuilder q = new StringBuilder(e);
        while (q.length() > 0 && (isOp(q.charAt(q.length() - 1)) || q.charAt(q.length() - 1) == '.')) q.setLength(q.length() - 1);
        int open = 0;
        for (int i = 0; i < q.length(); i++) { char c = q.charAt(i); if (c == '(') open++; else if (c == ')') open--; }
        while (open-- > 0) q.append(')');
        try {
            if (q.length() == 0) return;
            String res = Calc.format(Calc.eval(q.toString()));
            display.setResult(q.toString(), res);
            expr.setLength(0);
            if (res.indexOf('E') < 0) expr.append(res);   // sci-notation results can't be continued
            justEvaluated = true;
            sound.playResult();
            float[] cc = display.resultCenter();
            int[] loc = new int[2];
            display.getLocationOnScreen(loc);
            fx.starBurst(loc[0] + cc[0], loc[1] + cc[1]);
            kemo.say("できた！", true, 2.4f);   // できた！
        } catch (ArithmeticException ex) {
            error = true;
            expr.setLength(0);
            sound.playClear();
            display.setError("ERROR");
            kemo.say("あれれ？", false, 2.2f);  // あれれ？
        }
    }

    // ---------------------------------------------------------------- sound dialog

    private void showSoundDialog() {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) Theme.dp(this, 20);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2030E14);
        bg.setCornerRadius(Theme.dp(this, 22));
        bg.setStroke((int) Theme.dp(this, 1.5f), Theme.CYAN);
        box.setBackground(bg);

        TextView title = label("SOUND  //  サウンド", Theme.CYAN, 17, true);   // サウンド
        box.addView(title);
        String[] names = {"Keys  •  キー", "Delete  •  削除", "Result  •  結果", "Clear  •  消去", "Music  •  音楽"};
        for (int i = 0; i < SoundManager.GROUPS.length; i++) {
            final String g = SoundManager.GROUPS[i];
            TextView t = label(names[i], Theme.ICE, 14, false);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.topMargin = (int) Theme.dp(this, 14);
            box.addView(t, tp);
            SeekBar sb = new SeekBar(this);
            sb.setMax(100);
            sb.setProgress(sound.getVolume(g));
            sb.setProgressTintList(ColorStateList.valueOf(i % 2 == 0 ? Theme.CYAN : Theme.PINK));
            sb.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                    if (fromUser) sound.setVolume(g, v);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) { sound.preview(g); }
            });
            box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView ok = label("OK", Theme.PINK, 16, true);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, (int) Theme.dp(this, 16), 0, 0);
        ok.setOnClickListener(v -> d.dismiss());
        box.addView(ok, new LinearLayout.LayoutParams(-1, -2));

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private TextView label(String s, int color, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setShadowLayer(Theme.dp(this, 5), 0, 0, color);
        return t;
    }
}
