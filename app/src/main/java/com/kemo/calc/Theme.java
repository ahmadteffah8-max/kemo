package com.kemo.calc;

import android.content.Context;
import android.graphics.Color;

/** Shared palette + helpers (turquoise / sky blue with pink accents on black). */
public final class Theme {
    private Theme() {}

    public static final int BG = 0xFF000000;
    public static final int CYAN = 0xFF3DF2FF;
    public static final int TEAL = 0xFF19D3C5;
    public static final int SKY = 0xFF7AD8FF;
    public static final int PINK = 0xFFFF6EC7;
    public static final int ICE = 0xFFEAFFFF;

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static int alpha(int color, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)), Color.red(color), Color.green(color), Color.blue(color));
    }

    public static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
