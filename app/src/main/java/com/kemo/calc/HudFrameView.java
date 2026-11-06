package com.kemo.calc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** Neon HUD frame: cyan border with pink dashes, "|| 01 ||" tags and vertical Japanese label. */
public class HudFrameView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float m, sw;
    private String tag = "01";
    private final String[] side = {"ケ", "モ"};

    public HudFrameView(Context c) {
        super(c);
        m = Theme.dp(c, 10);
        sw = Math.max(1f, Theme.dp(c, 1.1f));
        line.setColor(Theme.alpha(Theme.CYAN, 200)); line.setStrokeWidth(sw); line.setStyle(Paint.Style.STROKE);
        pink.setColor(Theme.PINK); pink.setStrokeWidth(sw * 1.4f); pink.setStyle(Paint.Style.STROKE);
        txt.setColor(Theme.CYAN); txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(Theme.dp(c, 10)); txt.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        txt.setShadowLayer(Theme.dp(c, 4), 0, 0, Theme.CYAN);
        setClickable(false);
    }

    public void setLabel(String t) { tag = t; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f;
        float gap = Theme.dp(getContext(), 34);
        float dash = Theme.dp(getContext(), 22);
        // top & bottom borders with gap for the tag
        for (int k = 0; k < 2; k++) {
            float y = k == 0 ? m : h - m;
            c.drawLine(m, y, cx - gap - dash - Theme.dp(getContext(), 8), y, line);
            c.drawLine(cx - gap - dash, y, cx - gap, y, pink);
            c.drawLine(cx + gap, y, cx + gap + dash, y, pink);
            c.drawLine(cx + gap + dash + Theme.dp(getContext(), 8), y, w - m, y, line);
            float ty = y + txt.getTextSize() * 0.35f;
            c.drawText("|| " + tag + " ||", cx, ty, txt);
        }
        // side borders with a gap for the vertical label
        float cy = h * 0.5f, sg = Theme.dp(getContext(), 34);
        c.drawLine(m, m, m, cy - sg, line);
        c.drawLine(m, cy + sg, m, h - m, line);
        c.drawLine(w - m, m, w - m, cy - sg, line);
        c.drawLine(w - m, cy + sg, w - m, h - m, line);
        for (int s = 0; s < 2; s++) {
            float x = s == 0 ? m : w - m;
            c.drawLine(x - Theme.dp(getContext(), 4), cy - sg, x + Theme.dp(getContext(), 4), cy - sg, pink);
            c.drawLine(x - Theme.dp(getContext(), 4), cy + sg, x + Theme.dp(getContext(), 4), cy + sg, pink);
            for (int i = 0; i < side.length; i++)
                c.drawText(side[i], x, cy - sg * 0.35f + i * txt.getTextSize() * 1.5f, txt);
        }
    }
}
