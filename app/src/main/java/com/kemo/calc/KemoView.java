package com.kemo.calc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Random;

/** ORIGINA / kemo: the pixel-art digital girl who "owns" the calculator. Blinks, bobs, reacts and talks. */
public class KemoView extends View {
    private final Bitmap open, blink, happy;
    private final Paint bmp = new Paint();          // no filtering => crisp pixels
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Random rnd = new Random();
    private Paint haloPaint;

    private float time = 0;
    private long last = 0;
    private float nextBlink = 2.5f, blinkUntil = 0, happyUntil = 0, bounce = 0;
    private String bubble = null;
    private float bubbleUntil = 0;
    private boolean running = false;

    public KemoView(Context c) {
        super(c);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        open = BitmapFactory.decodeResource(c.getResources(), R.drawable.sprite_open, o);
        blink = BitmapFactory.decodeResource(c.getResources(), R.drawable.sprite_blink, o);
        happy = BitmapFactory.decodeResource(c.getResources(), R.drawable.sprite_happy, o);
        bmp.setFilterBitmap(false);
        bmp.setAntiAlias(false);
        title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        title.setColor(Theme.ICE);
        title.setShadowLayer(Theme.dp(c, 10), 0, 0, Theme.CYAN);
        sub.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        sub.setColor(Theme.PINK);
        sub.setShadowLayer(Theme.dp(c, 4), 0, 0, Theme.PINK);
        bubbleFill.setColor(Theme.alpha(0xFF031A24, 235));
        bubbleStroke.setStyle(Paint.Style.STROKE);
        bubbleStroke.setStrokeWidth(Theme.dp(c, 1.4f));
        bubbleStroke.setColor(Theme.CYAN);
        bubbleText.setColor(Theme.ICE);
        bubbleText.setTextAlign(Paint.Align.CENTER);
        bubbleText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    /** Small hop when a key is pressed. */
    public void nudge() { bounce = 1f; }

    /** Speech bubble (+ optional happy face). */
    public void say(String text, boolean happyFace, float seconds) {
        bubble = text;
        bubbleUntil = time + seconds;
        if (happyFace) happyUntil = time + seconds;
        invalidate();
        kick();
    }

    private void kick() { if (running) { /* loop already running */ } else { running = true; invalidate(); } }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); running = true; last = 0; invalidate(); }
    @Override protected void onDetachedFromWindow() { running = false; super.onDetachedFromWindow(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float cx = h * 0.45f;
        haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloPaint.setShader(new RadialGradient(cx, h * 0.5f, h * 0.75f,
                new int[]{Theme.alpha(Theme.CYAN, 90), Theme.alpha(Theme.TEAL, 30), Theme.alpha(Theme.TEAL, 0)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.04f : Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        time += dt;
        bounce = Math.max(0, bounce - dt * 4.5f);
        if (time >= nextBlink) { blinkUntil = time + 0.13f; nextBlink = time + 2.2f + rnd.nextFloat() * 3f; }

        int w = getWidth(), h = getHeight();
        // integer pixel scale keeps the pixel art crisp
        int s = Math.max(1, (int) Math.floor((h * 0.96f) / open.getHeight()));
        float sw = open.getWidth() * s, sh = open.getHeight() * s;
        float bob = (float) Math.sin(time * 2.0f) * Theme.dp(getContext(), 2f);
        float hop = (float) Math.sin(bounce * Math.PI) * Theme.dp(getContext(), 6f);
        float left = Theme.dp(getContext(), 4);
        float top = h - sh + Math.round(bob - hop);
        // snap to the pixel grid
        left = Math.round(left); top = Math.round(top);

        c.drawRect(0, 0, w, h, haloPaint);
        Bitmap b = time < happyUntil ? happy : (time < blinkUntil ? blink : open);
        src.set(0, 0, b.getWidth(), b.getHeight());
        dst.set(left, top, left + sw, top + sh);
        c.drawBitmap(b, src, dst, bmp);

        // orbiting light particles around her
        float ox = left + sw / 2f, oy = top + sh * 0.45f;
        for (int i = 0; i < 6; i++) {
            float a = time * 0.9f + i * 1.0472f;
            float rx = sw * 0.62f, ry = sh * 0.5f;
            float px = ox + (float) Math.cos(a) * rx, py = oy + (float) Math.sin(a * 1.3f) * ry;
            float tw = 0.4f + 0.6f * (0.5f + 0.5f * (float) Math.sin(time * 4 + i));
            p.setColor(Theme.alpha(i % 3 == 0 ? Theme.PINK : Theme.CYAN, (int) (255 * tw)));
            float q = Theme.dp(getContext(), 2.2f);
            c.drawRect(px - q * 1.6f, py - q / 2, px + q * 1.6f, py + q / 2, p);
            c.drawRect(px - q / 2, py - q * 1.6f, px + q / 2, py + q * 1.6f, p);
        }

        // title block on the right
        float tx = left + sw + Theme.dp(getContext(), 14);
        float tsz = Math.min(h * 0.30f, Theme.dp(getContext(), 40));
        title.setTextSize(tsz);
        float pulse = 0.75f + 0.25f * (float) Math.sin(time * 2.4f);
        title.setShadowLayer(Theme.dp(getContext(), 6 + 8 * pulse), 0, 0, Theme.CYAN);
        c.drawText("KEMO", tx, h * 0.56f, title);
        sub.setTextSize(Math.min(h * 0.095f, Theme.dp(getContext(), 12)));
        c.drawText("ORIGINA // DIGITAL GIRL", tx, h * 0.56f + sub.getTextSize() * 1.7f, sub);

        // speech bubble
        if (bubble != null && time < bubbleUntil) {
            float fade = Theme.clamp01((bubbleUntil - time) / 0.3f);
            float pop = Theme.clamp01((time - (bubbleUntil - 0f)) + 1f);
            bubbleText.setTextSize(Math.min(h * 0.11f, Theme.dp(getContext(), 14)));
            float tw = bubbleText.measureText(bubble);
            float padX = Theme.dp(getContext(), 10), padY = Theme.dp(getContext(), 6);
            float bx = tx, by = Math.max(Theme.dp(getContext(), 2), h * 0.56f - tsz - padY * 2 - Theme.dp(getContext(), 4));
            RectF r = new RectF(bx, by, bx + tw + padX * 2, by + bubbleText.getTextSize() + padY * 2);
            bubbleFill.setAlpha((int) (235 * fade));
            bubbleStroke.setAlpha((int) (255 * fade));
            bubbleText.setAlpha((int) (255 * fade));
            c.drawRoundRect(r, Theme.dp(getContext(), 8), Theme.dp(getContext(), 8), bubbleFill);
            c.drawRoundRect(r, Theme.dp(getContext(), 8), Theme.dp(getContext(), 8), bubbleStroke);
            c.drawText(bubble, r.centerX(), r.bottom - padY - bubbleText.getTextSize() * 0.2f, bubbleText);
        }
        if (running) postInvalidateDelayed(40);
    }
}
