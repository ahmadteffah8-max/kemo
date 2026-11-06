package com.kemo.calc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/** Black background with twinkling pixel stars, drifting pixels and a faint neon grid. Capped at ~30 fps. */
public class BackgroundView extends View {
    private static final int N = 44;
    private final float[] x = new float[N], y = new float[N], sz = new float[N], sp = new float[N], ph = new float[N];
    private final int[] kind = new int[N], col = new int[N];
    private final Paint p = new Paint();
    private final Paint glow = new Paint();
    private final Random rnd = new Random(7);
    private long last = 0;
    private float time = 0;
    private float cell;
    private boolean running = false;

    public BackgroundView(Context c) {
        super(c);
        p.setStyle(Paint.Style.FILL);
        cell = Theme.dp(c, 26);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (int i = 0; i < N; i++) {
            x[i] = rnd.nextFloat() * w;
            y[i] = rnd.nextFloat() * h;
            kind[i] = rnd.nextInt(3) == 0 ? 1 : 0;       // 1 = drifting pixel, 0 = star
            sz[i] = Theme.dp(getContext(), kind[i] == 1 ? 2 + rnd.nextInt(2) : 1.2f + rnd.nextFloat() * 1.3f);
            sp[i] = Theme.dp(getContext(), 6 + rnd.nextFloat() * 18);
            ph[i] = 1 + rnd.nextFloat() * 3;
            int r = rnd.nextInt(10);
            col[i] = r < 5 ? Theme.CYAN : (r < 7 ? Theme.ICE : (r < 9 ? Theme.SKY : Theme.PINK));
        }
        glow.setShader(new RadialGradient(w * 0.3f, h * 0.12f, w * 0.9f,
                new int[]{Theme.alpha(Theme.TEAL, 46), Theme.alpha(Theme.TEAL, 0)}, null, Shader.TileMode.CLAMP));
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); running = true; last = 0; invalidate(); }
    @Override protected void onDetachedFromWindow() { running = false; super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.033f : Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        time += dt;
        int w = getWidth(), h = getHeight();
        c.drawColor(Theme.BG);
        c.drawRect(0, 0, w, h, glow);

        // faint neon grid
        p.setColor(Theme.alpha(Theme.CYAN, 12));
        for (float gx = cell; gx < w; gx += cell) c.drawRect(gx, 0, gx + 1, h, p);
        for (float gy = cell; gy < h; gy += cell) c.drawRect(0, gy, w, gy + 1, p);

        for (int i = 0; i < N; i++) {
            float a = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(time * ph[i] + i));
            p.setColor(Theme.alpha(col[i], (int) (255 * a)));
            if (kind[i] == 1) {
                y[i] -= sp[i] * dt;
                if (y[i] < -10) { y[i] = h + 10; x[i] = rnd.nextFloat() * w; }
                c.drawRect(x[i], y[i], x[i] + sz[i], y[i] + sz[i], p);
            } else {
                float s = sz[i];
                c.drawRect(x[i] - s * 2, y[i] - s / 2, x[i] + s * 2, y[i] + s / 2, p);   // 4-point pixel star
                c.drawRect(x[i] - s / 2, y[i] - s * 2, x[i] + s / 2, y[i] + s * 2, p);
            }
        }
        if (running) postInvalidateDelayed(33);
    }
}
