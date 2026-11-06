package com.kemo.calc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Glass display panel. Shows the typed expression (last char fades/slides in) and,
 * after "=", the previous expression above and the result popping in.
 */
public class DisplayView extends View {
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint main = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private String text = "";
    private String top = "";
    private boolean error = false;
    private float appear = 1f;       // 0..1 animation of the last character
    private float pop = 1f;          // 0..1 result pop animation
    private long last = 0;
    private final float maxSize, minSize, padX;

    public DisplayView(Context c) {
        super(c);
        maxSize = Theme.dp(c, 46);
        minSize = Theme.dp(c, 22);
        padX = Theme.dp(c, 16);
        panel.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Theme.dp(c, 1.4f));
        stroke.setColor(Theme.alpha(Theme.CYAN, 190));
        main.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        main.setColor(Theme.ICE);
        main.setTextAlign(Paint.Align.LEFT);
        small.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        small.setColor(Theme.alpha(Theme.SKY, 190));
        small.setTextAlign(Paint.Align.RIGHT);
        small.setTextSize(Theme.dp(c, 15));
        accent.setColor(Theme.PINK);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        panel.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{Theme.alpha(Theme.CYAN, 34), Theme.alpha(Theme.TEAL, 10)}, null, Shader.TileMode.CLAMP));
    }

    /** Typed input; animateLast=true makes the newest character glide in. */
    public void setInput(String s, boolean animateLast) {
        text = s; top = ""; error = false;
        if (animateLast) { appear = 0f; pop = 1f; } else appear = 1f;
        kick();
    }

    /** After "=": expression goes up, result pops in. */
    public void setResult(String expr, String res) {
        top = expr + " ="; text = res; error = false; appear = 1f; pop = 0f; kick();
    }

    public void setError(String msg) {
        top = ""; text = msg; error = true; appear = 1f; pop = 0f; kick();
    }

    private void kick() { last = 0; postInvalidateOnAnimation(); }

    private float fitSize(String s, float avail) {
        float sz = maxSize;
        main.setTextSize(sz);
        float wd = main.measureText(s);
        if (wd > avail) sz = Math.max(minSize, sz * avail / wd);
        return sz;
    }

    /** Rect (view coords) of the last character, for the delete explosion. Null if empty. */
    public boolean lastCharRect(RectF out) {
        if (text.isEmpty()) return false;
        float avail = getWidth() - padX * 2;
        float sz = fitSize(text, avail);
        main.setTextSize(sz);
        float total = main.measureText(text);
        float right = getWidth() - padX;
        float lw = main.measureText(text.substring(text.length() - 1));
        float baseline = baseline();
        out.set(right - lw, baseline - sz * 0.85f, right, baseline + sz * 0.15f);
        if (total > avail) { /* text is clipped on the left; last char is still at right edge */ }
        return true;
    }

    public float[] resultCenter() {
        float avail = getWidth() - padX * 2;
        float sz = fitSize(text.isEmpty() ? "0" : text, avail);
        main.setTextSize(sz);
        float wd = main.measureText(text.isEmpty() ? "0" : text);
        return new float[]{getWidth() - padX - wd / 2f, baseline() - sz * 0.35f};
    }

    private float baseline() { return getHeight() * 0.80f; }

    @Override protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        boolean animating = false;
        if (appear < 1f) { appear = Math.min(1f, appear + dt / 0.14f); animating = true; }
        if (pop < 1f) { pop = Math.min(1f, pop + dt / 0.45f); animating = true; }

        int w = getWidth(), h = getHeight();
        float rad = Theme.dp(getContext(), 18);
        r.set(Theme.dp(getContext(), 2), Theme.dp(getContext(), 2), w - Theme.dp(getContext(), 2), h - Theme.dp(getContext(), 2));
        c.drawRoundRect(r, rad, rad, panel);
        stroke.setStrokeWidth(Theme.dp(getContext(), 4)); stroke.setAlpha(34);
        c.drawRoundRect(r, rad, rad, stroke);
        stroke.setStrokeWidth(Theme.dp(getContext(), 1.4f)); stroke.setAlpha(200);
        c.drawRoundRect(r, rad, rad, stroke);
        // pink corner ticks
        float t = Theme.dp(getContext(), 12), sw = Theme.dp(getContext(), 2);
        c.drawRect(r.left + rad, r.top - sw / 2, r.left + rad + t, r.top + sw / 2, accent);
        c.drawRect(r.right - rad - t, r.bottom - sw / 2, r.right - rad, r.bottom + sw / 2, accent);

        float right = w - padX, base = baseline();
        if (!top.isEmpty()) {
            small.setTextSize(Theme.dp(getContext(), 15));
            c.save(); c.clipRect(padX, 0, right, h);
            float tw = small.measureText(top);
            float x = tw > right - padX ? padX + tw : right;
            c.drawText(top, x, h * 0.30f, small);
            c.restore();
        }
        if (text.isEmpty()) {
            main.setTextSize(maxSize);
            main.setAlpha(110);
            main.setShadowLayer(0, 0, 0, 0);
            c.drawText("0", right - main.measureText("0"), base, main);
            main.setAlpha(255);
        } else {
            float avail = w - padX * 2;
            float sz = fitSize(text, avail);
            main.setTextSize(sz);
            int glowColor = error ? Theme.PINK : Theme.CYAN;
            main.setShadowLayer(Theme.dp(getContext(), 9), 0, 0, glowColor);
            main.setColor(error ? Theme.PINK : Theme.ICE);
            c.save();
            c.clipRect(padX * 0.5f, 0, w - padX * 0.5f, h);
            if (pop < 1f) {
                float e = easeOutBack(pop);
                float sc = 0.55f + 0.45f * e;
                float cx = right - main.measureText(text) / 2f;
                c.scale(sc, sc, cx, base - sz * 0.3f);
                main.setAlpha((int) (255 * Theme.clamp01(pop * 2.5f)));
                c.drawText(text, right - main.measureText(text), base, main);
                main.setAlpha(255);
            } else {
                String prefix = text.substring(0, text.length() - 1);
                String lastCh = text.substring(text.length() - 1);
                float total = main.measureText(text);
                float x0 = right - total;
                float pw = main.measureText(prefix);
                c.drawText(prefix, x0, base, main);
                float e = easeOutBack(appear);
                main.setAlpha((int) (255 * Theme.clamp01(appear * 1.6f)));
                float lw = main.measureText(lastCh);
                c.save();
                c.scale(0.6f + 0.4f * e, 0.6f + 0.4f * e, x0 + pw + lw / 2f, base - sz * 0.3f);
                c.drawText(lastCh, x0 + pw, base + (1f - e) * sz * 0.25f, main);
                c.restore();
                main.setAlpha(255);
            }
            c.restore();
            main.setShadowLayer(0, 0, 0, 0);
            main.setColor(Theme.ICE);
        }
        if (animating) postInvalidateOnAnimation(); else last = 0;
    }

    private static float easeOutBack(float t) {
        float s = 1.70158f;
        t = t - 1f;
        return t * t * ((s + 1) * t + s) + 1f;
    }
}
