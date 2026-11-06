package com.kemo.calc;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** Liquid-glass key: translucent gradient, neon outline, top sheen, glow flash + squish on press. */
public class GlassButton extends View {
    public static final int NUM = 0, OP = 1, FN = 2, EQ = 3;

    public interface Listener { void onPress(GlassButton b, float rawX, float rawY); void onLongPress(GlassButton b); }

    private final String label;
    private final String key;
    private final int variant;
    private final int base;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF r2 = new RectF();
    private final Path icon = new Path();
    private float glow = 0f;
    private boolean down = false;
    private ValueAnimator glowAnim;
    private Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPress = new Runnable() {
        @Override public void run() { if (down && listener != null) listener.onLongPress(GlassButton.this); }
    };
    private boolean wantsLongPress = false;

    public GlassButton(Context c, String label, String key, int variant) {
        super(c);
        this.label = label; this.key = key; this.variant = variant;
        switch (variant) {
            case OP: base = Theme.PINK; break;
            case FN: base = Theme.SKY; break;
            default: base = Theme.CYAN;
        }
        border.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setColor(variant == EQ ? Theme.ICE : (variant == OP ? 0xFFFFE3F4 : Theme.ICE));
        flash.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public String getKey() { return key; }
    public int getBaseColor() { return variant == EQ ? Theme.CYAN : base; }
    public void setListener(Listener l, boolean longPress) { listener = l; wantsLongPress = longPress; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = Theme.dp(getContext(), 3);
        r.set(pad, pad, w - pad, h - pad);
        int top, bot;
        if (variant == EQ) { top = Theme.alpha(Theme.CYAN, 120); bot = Theme.alpha(Theme.PINK, 105); }
        else { top = Theme.alpha(base, variant == OP ? 70 : 56); bot = Theme.alpha(base, 14); }
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bot, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.5f,
                Theme.alpha(0xFFFFFFFF, 70), Theme.alpha(0xFFFFFFFF, 0), Shader.TileMode.CLAMP));
        text.setTextSize(Math.min(h * 0.42f, Theme.dp(getContext(), 26)));
        text.setShadowLayer(Theme.dp(getContext(), 7), 0, 0, getBaseColor());
        r2.set(r.left + Theme.dp(getContext(), 2), r.top + Theme.dp(getContext(), 2),
                r.right - Theme.dp(getContext(), 2), r.top + r.height() * 0.5f);
        buildIcon();
    }

    private void buildIcon() {
        icon.reset();
        if (!"DEL".equals(key)) return;
        float cx = r.centerX(), cy = r.centerY();
        float w = Math.min(r.width() * 0.34f, Theme.dp(getContext(), 20)), h = w * 0.62f;
        icon.moveTo(cx - w, cy);
        icon.lineTo(cx - w * 0.35f, cy - h);
        icon.lineTo(cx + w, cy - h);
        icon.lineTo(cx + w, cy + h);
        icon.lineTo(cx - w * 0.35f, cy + h);
        icon.close();
        float k = h * 0.42f, kx = cx + w * 0.25f;
        icon.moveTo(kx - k, cy - k); icon.lineTo(kx + k, cy + k);
        icon.moveTo(kx - k, cy + k); icon.lineTo(kx + k, cy - k);
    }

    @Override protected void onDraw(Canvas c) {
        float rad = Math.min(r.height() * 0.32f, Theme.dp(getContext(), 18));
        c.drawRoundRect(r, rad, rad, fill);
        if (glow > 0f) {
            flash.setColor(Theme.alpha(getBaseColor(), (int) (110 * glow)));
            c.drawRoundRect(r, rad, rad, flash);
        }
        c.drawRoundRect(r2, rad * 0.8f, rad * 0.8f, sheen);
        // outer halo + crisp border
        border.setStrokeWidth(Theme.dp(getContext(), 4));
        border.setColor(Theme.alpha(getBaseColor(), (int) (34 + 120 * glow)));
        c.drawRoundRect(r, rad, rad, border);
        border.setStrokeWidth(Math.max(1f, Theme.dp(getContext(), 1.3f)));
        border.setColor(Theme.alpha(getBaseColor(), 215));
        c.drawRoundRect(r, rad, rad, border);

        if ("DEL".equals(key)) {
            border.setStrokeWidth(Theme.dp(getContext(), 2f));
            border.setColor(Theme.ICE);
            border.setStrokeJoin(Paint.Join.ROUND);
            border.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(icon, border);
            border.setStrokeCap(Paint.Cap.BUTT);
        } else {
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, text);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                animatePress(true);
                startGlow();
                if (listener != null) listener.onPress(this, e.getRawX(), e.getRawY());
                if (wantsLongPress) handler.postDelayed(longPress, 650);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false;
                handler.removeCallbacks(longPress);
                animatePress(false);
                if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void animatePress(boolean pressed) {
        if (pressed) animate().cancel();
        animate().scaleX(pressed ? 0.93f : 1f).scaleY(pressed ? 0.93f : 1f)
                .setDuration(pressed ? 60 : 180)
                .setInterpolator(pressed ? null : new OvershootInterpolator(2.5f)).start();
    }

    private void startGlow() {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofFloat(1f, 0f);
        glowAnim.setDuration(380);
        glowAnim.addUpdateListener(a -> { glow = (Float) a.getAnimatedValue(); invalidate(); });
        glowAnim.start();
    }
}
