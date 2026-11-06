package com.kemo.calc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.Random;

public class SplashActivity extends Activity {
    private static final long TOTAL_MS = 4200;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean launched = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);
        root.setFitsSystemWindows(true);
        root.addView(new BackgroundView(this));
        root.addView(new SplashView(this));
        HudFrameView hud = new HudFrameView(this);
        hud.setLabel("00");
        root.addView(hud);
        root.setOnTouchListener((v, e) -> { if (e.getAction() == MotionEvent.ACTION_UP) launch(); return true; });
        setContentView(root);
        SoundManager.get(this).playStartup();
        handler.postDelayed(this::launch, TOTAL_MS);
    }

    private void launch() {
        if (launched) return;
        launched = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }

    /** Pixel-art reveal of kemo, Japanese greeting typed out, then glowing KEMO title. */
    static final class SplashView extends View {
        private final Bitmap sprite;
        private final Paint bmp = new Paint();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint jp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private final Random rnd = new Random(5);
        private final String greeting = "ようこそ、ケモの世界へ";
        private long start = 0;
        private Paint halo;

        SplashView(Context c) {
            super(c);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            sprite = BitmapFactory.decodeResource(c.getResources(), R.drawable.sprite_happy, o);
            bmp.setFilterBitmap(false);
            bmp.setAntiAlias(false);
            jp.setColor(Theme.CYAN);
            jp.setTextAlign(Paint.Align.CENTER);
            jp.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            jp.setShadowLayer(Theme.dp(c, 6), 0, 0, Theme.CYAN);
            title.setColor(Theme.ICE);
            title.setTextAlign(Paint.Align.CENTER);
            title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            sub.setColor(Theme.PINK);
            sub.setTextAlign(Paint.Align.CENTER);
            sub.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            sub.setShadowLayer(Theme.dp(c, 4), 0, 0, Theme.PINK);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            halo = new Paint(Paint.ANTI_ALIAS_FLAG);
            halo.setShader(new RadialGradient(w / 2f, h * 0.34f, w * 0.7f,
                    new int[]{Theme.alpha(Theme.CYAN, 110), Theme.alpha(Theme.TEAL, 0)}, null, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas c) {
            long now = System.currentTimeMillis();
            if (start == 0) start = now;
            float t = (now - start) / 1000f;
            int w = getWidth(), h = getHeight();

            int s = Math.max(2, (int) Math.floor(Math.min(w * 0.62f / sprite.getWidth(), h * 0.40f / sprite.getHeight())));
            float sw = sprite.getWidth() * s, sh = sprite.getHeight() * s;
            float left = Math.round((w - sw) / 2f), top = Math.round(h * 0.13f);

            // neon halo pulse
            halo.setAlpha((int) (255 * Theme.clamp01(t / 0.8f) * (0.8f + 0.2f * (float) Math.sin(t * 3))));
            c.drawRect(0, 0, w, h, halo);

            // 1) scanline pixel reveal (top -> bottom, with flicker) over ~1.2 s
            float reveal = Theme.clamp01((t - 0.15f) / 1.2f);
            if (reveal > 0) {
                int rowsTotal = sprite.getHeight();
                int rows = (int) (rowsTotal * reveal);
                src.set(0, 0, sprite.getWidth(), Math.max(1, rows));
                dst.set(left, top, left + sw, top + rows * s);
                if (reveal < 1f && rnd.nextInt(5) == 0) dst.offset(s * (rnd.nextInt(3) - 1), 0);   // glitch
                c.drawBitmap(sprite, src, dst, bmp);
                if (reveal < 1f) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Theme.alpha(Theme.CYAN, 230));
                    c.drawRect(left - s * 2, top + rows * s, left + sw + s * 2, top + rows * s + s, p);
                    p.setColor(Theme.alpha(Theme.PINK, 160));
                    c.drawRect(left - s * 2, top + rows * s + s, left + sw + s * 2, top + rows * s + s * 2, p);
                }
            }
            // sparkles around her
            if (t > 0.5f) {
                for (int i = 0; i < 14; i++) {
                    float a = t * 0.7f + i * 0.449f;
                    float px = w / 2f + (float) Math.cos(a * 1.1f + i) * sw * (0.55f + 0.04f * (i % 4));
                    float py = top + sh * 0.5f + (float) Math.sin(a * 1.4f) * sh * 0.55f;
                    float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * 5 + i * 2));
                    float q = Theme.dp(getContext(), 2f);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Theme.alpha(i % 3 == 0 ? Theme.PINK : Theme.CYAN, (int) (255 * tw)));
                    c.drawRect(px - q * 1.8f, py - q / 2, px + q * 1.8f, py + q / 2, p);
                    c.drawRect(px - q / 2, py - q * 1.8f, px + q / 2, py + q * 1.8f, p);
                }
            }

            // 2) Japanese greeting typed out (1.3 s .. 2.5 s)
            float gy = top + sh + Theme.dp(getContext(), 38);
            float gsz = Math.min(w * 0.06f, Theme.dp(getContext(), 22));
            jp.setTextSize(gsz);
            int chars = (int) (greeting.length() * Theme.clamp01((t - 1.3f) / 1.1f));
            if (chars > 0) {
                String part = greeting.substring(0, chars);
                c.drawText(part, w / 2f, gy, jp);
                if (((int) (t * 6)) % 2 == 0 && chars < greeting.length()) {
                    float cx = w / 2f + jp.measureText(part) / 2f + Theme.dp(getContext(), 4);
                    p.setColor(Theme.CYAN);
                    c.drawRect(cx, gy - gsz * 0.85f, cx + Theme.dp(getContext(), 3), gy + gsz * 0.1f, p);
                }
            }

            // 3) KEMO title glow-in (2.4 s ..)
            float tt = Theme.clamp01((t - 2.4f) / 0.8f);
            if (tt > 0) {
                float tsz = Math.min(w * 0.22f, Theme.dp(getContext(), 84));
                title.setTextSize(tsz * (0.85f + 0.15f * tt));
                float pulse = 0.7f + 0.3f * (float) Math.sin(t * 4);
                title.setShadowLayer(Theme.dp(getContext(), 10 + 16 * pulse * tt), 0, 0, Theme.CYAN);
                title.setAlpha((int) (255 * tt));
                float ty = gy + tsz * 1.25f;
                c.drawText("KEMO", w / 2f, ty, title);
                sub.setTextSize(Math.min(w * 0.036f, Theme.dp(getContext(), 13)));
                sub.setAlpha((int) (255 * tt));
                c.drawText("ORIGINA // DIGITAL CALCULATOR", w / 2f, ty + sub.getTextSize() * 2.4f, sub);
            }
            if (t < 5f) postInvalidateOnAnimation();
        }
    }
}
