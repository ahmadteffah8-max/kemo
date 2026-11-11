package com.simpleplayer.app;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** A FrameLayout that is as large as possible inside its parent while keeping a fixed aspect ratio. */
public class AspectFrameLayout extends FrameLayout {

    private float aspect = 16f / 9f;

    public AspectFrameLayout(Context c) {
        super(c);
    }

    public AspectFrameLayout(Context c, AttributeSet a) {
        super(c, a);
    }

    void setAspect(float a) {
        if (a > 0 && Math.abs(a - aspect) > 0.001f) {
            aspect = a;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getSize(wSpec);
        int maxH = MeasureSpec.getSize(hSpec);
        int w = maxW;
        int h = Math.round(w / aspect);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * aspect);
        }
        int ws = MeasureSpec.makeMeasureSpec(Math.max(1, w), MeasureSpec.EXACTLY);
        int hs = MeasureSpec.makeMeasureSpec(Math.max(1, h), MeasureSpec.EXACTLY);
        super.onMeasure(ws, hs);
    }
}
