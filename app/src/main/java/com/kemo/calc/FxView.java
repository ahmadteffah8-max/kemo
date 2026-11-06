package com.kemo.calc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/** Transparent overlay that draws neon ripples, pixel explosions and star bursts. Allocation-free, pooled. */
public class FxView extends View {
    private static final int MAX = 180;
    private static final int RING = 0, PIXEL = 1, STAR = 2;
    private final int[] type = new int[MAX], color = new int[MAX];
    private final float[] x = new float[MAX], y = new float[MAX], vx = new float[MAX], vy = new float[MAX];
    private final float[] life = new float[MAX], maxLife = new float[MAX], size = new float[MAX];
    private int count = 0;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final int[] loc = new int[2];
    private long last = 0;
    private final float d;

    public FxView(Context c) {
        super(c);
        d = Theme.dp(c, 1);
        setClickable(false);
        setFocusable(false);
    }

    private void toLocal(float sx, float sy, float[] out) {
        getLocationOnScreen(loc);
        out[0] = sx - loc[0]; out[1] = sy - loc[1];
    }
    private final float[] tmp = new float[2];

    private int add(int t, float px, float py, float pvx, float pvy, float l, float s, int col) {
        if (count >= MAX) { // overwrite oldest
            for (int i = 1; i < MAX; i++) copy(i, i - 1);
            count = MAX - 1;
        }
        int i = count++;
        type[i] = t; x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy;
        life[i] = 0; maxLife[i] = l; size[i] = s; color[i] = col;
        return i;
    }
    private void copy(int from, int to) {
        type[to] = type[from]; color[to] = color[from]; x[to] = x[from]; y[to] = y[from];
        vx[to] = vx[from]; vy[to] = vy[from]; life[to] = life[from]; maxLife[to] = maxLife[from]; size[to] = size[from];
    }

    /** Neon wave at a screen position (touch ripple). */
    public void ripple(float sx, float sy, int col) {
        toLocal(sx, sy, tmp);
        add(RING, tmp[0], tmp[1], 0, 0, 0.55f, 46 * d, col);
        add(RING, tmp[0], tmp[1], 0, 0, 0.75f, 70 * d, Theme.alpha(col, 150));
        kick();
    }

    /** Pixel explosion inside a rectangle given in screen coordinates (deleted digit). */
    public void pixelBurst(float sl, float st, float sr, float sb, int col) {
        toLocal(sl, st, tmp);
        float l = tmp[0], t = tmp[1], w = sr - sl, h = sb - st;
        int cols = 5, rows = 7;
        for (int i = 0; i < cols; i++)
            for (int j = 0; j < rows; j++) {
                float px = l + (i + 0.5f) * w / cols, py = t + (j + 0.5f) * h / rows;
                float ang = rnd.nextFloat() * 6.2832f, spd = (40 + rnd.nextFloat() * 170) * d;
                int cc = rnd.nextInt(5) == 0 ? Theme.PINK : (rnd.nextBoolean() ? col : Theme.ICE);
                add(PIXEL, px, py, (float) Math.cos(ang) * spd, (float) Math.sin(ang) * spd - 40 * d,
                        0.45f + rnd.nextFloat() * 0.35f, (2 + rnd.nextInt(3)) * d, cc);
            }
        kick();
    }

    /** Stars + glowing particles bursting from a point (result). */
    public void starBurst(float sx, float sy) {
        toLocal(sx, sy, tmp);
        for (int i = 0; i < 12; i++) {
            float ang = rnd.nextFloat() * 6.2832f, spd = (50 + rnd.nextFloat() * 160) * d;
            add(STAR, tmp[0], tmp[1], (float) Math.cos(ang) * spd, (float) Math.sin(ang) * spd - 30 * d,
                    0.7f + rnd.nextFloat() * 0.6f, (2 + rnd.nextFloat() * 2.5f) * d,
                    rnd.nextInt(3) == 0 ? Theme.PINK : (rnd.nextBoolean() ? Theme.CYAN : Theme.ICE));
        }
        for (int i = 0; i < 14; i++) {
            float ang = rnd.nextFloat() * 6.2832f, spd = (20 + rnd.nextFloat() * 100) * d;
            add(PIXEL, tmp[0], tmp[1], (float) Math.cos(ang) * spd, (float) Math.sin(ang) * spd,
                    0.6f + rnd.nextFloat() * 0.5f, (1.5f + rnd.nextFloat() * 2f) * d, Theme.alpha(Theme.CYAN, 220));
        }
        add(RING, tmp[0], tmp[1], 0, 0, 0.7f, 90 * d, Theme.PINK);
        kick();
    }

    /** Digital vanish: pixels rain over a rectangle (clear all). */
    public void vanish(float sl, float st, float sr, float sb) {
        toLocal(sl, st, tmp);
        for (int i = 0; i < 46; i++) {
            float px = tmp[0] + rnd.nextFloat() * (sr - sl), py = tmp[1] + rnd.nextFloat() * (sb - st);
            add(PIXEL, px, py, (rnd.nextFloat() - 0.5f) * 40 * d, -(20 + rnd.nextFloat() * 120) * d,
                    0.5f + rnd.nextFloat() * 0.6f, (2 + rnd.nextInt(3)) * d,
                    rnd.nextInt(4) == 0 ? Theme.PINK : Theme.CYAN);
        }
        kick();
    }

    private void kick() { if (last == 0) postInvalidateOnAnimation(); else invalidate(); }

    @Override protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        int w = 0;
        for (int i = 0; i < count; i++) {
            life[i] += dt;
            if (life[i] >= maxLife[i]) continue;
            float t = life[i] / maxLife[i];
            if (type[i] == RING) {
                float r = size[i] * (1f - (1f - t) * (1f - t));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, (1 - t) * 3 * d));
                p.setColor(Theme.alpha(color[i], (int) (Theme.clamp01(1 - t) * Math.min(255, (color[i] >>> 24)))));
                c.drawCircle(x[i], y[i], r, p);
            } else {
                x[i] += vx[i] * dt; y[i] += vy[i] * dt;
                vy[i] += 260 * d * dt * (type[i] == PIXEL ? 1f : 0.35f);
                vx[i] *= 0.985f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(Theme.alpha(color[i], (int) (255 * Theme.clamp01(1.6f - t * 1.6f))));
                float s = size[i] * (1 - 0.4f * t);
                if (type[i] == PIXEL) {
                    c.drawRect(x[i] - s, y[i] - s, x[i] + s, y[i] + s, p);
                } else {
                    float tw = (0.6f + 0.4f * (float) Math.sin(life[i] * 30));
                    c.drawRect(x[i] - s * 2.2f * tw, y[i] - s * 0.5f, x[i] + s * 2.2f * tw, y[i] + s * 0.5f, p);
                    c.drawRect(x[i] - s * 0.5f, y[i] - s * 2.2f * tw, x[i] + s * 0.5f, y[i] + s * 2.2f * tw, p);
                }
            }
            if (w != i) copy(i, w);
            w++;
        }
        count = w;
        if (count > 0) postInvalidateOnAnimation(); else last = 0;
    }
}
