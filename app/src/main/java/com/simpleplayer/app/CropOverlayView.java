package com.simpleplayer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Draggable crop rectangle drawn on top of the video preview. */
public class CropOverlayView extends View {

    private static final int NONE = 0, MOVE = 1, TL = 2, TR = 3, BL = 4, BR = 5, L = 6, T = 7, R = 8, B = 9;

    private final Paint dim = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final float touch;
    private final float minSize;

    private float ratio; // width/height in view pixels; 0 = free
    private int mode = NONE;
    private float lastX, lastY;
    private boolean init;

    public CropOverlayView(Context c) {
        this(c, null);
    }

    public CropOverlayView(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        touch = 30 * density;
        minSize = 56 * density;
        dim.setColor(0xAA000000);
        line.setColor(0xFFFFFFFF);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * density);
        thin.setColor(0x66FFFFFF);
        thin.setStyle(Paint.Style.STROKE);
        thin.setStrokeWidth(1 * density);
        corner.setColor(0xFFFFFFFF);
        corner.setStyle(Paint.Style.STROKE);
        corner.setStrokeWidth(4 * density);
    }

    /** Sets the aspect lock (width/height) and refits the rectangle; 0 keeps it free. */
    void setRatio(float r) {
        ratio = r;
        if (r > 0 && getWidth() > 0) fitRatio();
        invalidate();
    }

    void reset() {
        ratio = 0;
        rect.set(0, 0, getWidth(), getHeight());
        invalidate();
    }

    /** Crop area as fractions (0..1) of the video. */
    void getNormalized(RectF out) {
        float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        out.set(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h);
    }

    boolean isFull() {
        float w = getWidth(), h = getHeight();
        return rect.left < 2 && rect.top < 2 && rect.right > w - 2 && rect.bottom > h - 2;
    }

    private void fitRatio() {
        float w = getWidth(), h = getHeight();
        float cw = w, ch = w / ratio;
        if (ch > h) {
            ch = h;
            cw = h * ratio;
        }
        float cx = rect.isEmpty() ? w / 2 : rect.centerX();
        float cy = rect.isEmpty() ? h / 2 : rect.centerY();
        float l = Math.max(0, Math.min(w - cw, cx - cw / 2));
        float t = Math.max(0, Math.min(h - ch, cy - ch / 2));
        rect.set(l, t, l + cw, t + ch);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!init || ow == 0 || oh == 0) {
            rect.set(0, 0, w, h);
            init = true;
        } else {
            float sx = (float) w / ow, sy = (float) h / oh;
            rect.set(rect.left * sx, rect.top * sy, rect.right * sx, rect.bottom * sy);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, rect.top, dim);
        c.drawRect(0, rect.bottom, w, h, dim);
        c.drawRect(0, rect.top, rect.left, rect.bottom, dim);
        c.drawRect(rect.right, rect.top, w, rect.bottom, dim);
        // thirds grid
        for (int i = 1; i < 3; i++) {
            float x = rect.left + rect.width() * i / 3f;
            float y = rect.top + rect.height() * i / 3f;
            c.drawLine(x, rect.top, x, rect.bottom, thin);
            c.drawLine(rect.left, y, rect.right, y, thin);
        }
        c.drawRect(rect, line);
        float k = 18 * density;
        drawCorner(c, rect.left, rect.top, k, k);
        drawCorner(c, rect.right, rect.top, -k, k);
        drawCorner(c, rect.left, rect.bottom, k, -k);
        drawCorner(c, rect.right, rect.bottom, -k, -k);
    }

    private void drawCorner(Canvas c, float x, float y, float dx, float dy) {
        c.drawLine(x, y, x + dx, y, corner);
        c.drawLine(x, y, x, y + dy, corner);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = hit(e.getX(), e.getY());
                lastX = e.getX();
                lastY = e.getY();
                if (mode != NONE) getParent().requestDisallowInterceptTouchEvent(true);
                return mode != NONE;
            case MotionEvent.ACTION_MOVE:
                if (mode == NONE) return false;
                drag(e.getX(), e.getY());
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = NONE;
                return true;
        }
        return true;
    }

    private int hit(float x, float y) {
        boolean nl = Math.abs(x - rect.left) < touch, nr = Math.abs(x - rect.right) < touch;
        boolean nt = Math.abs(y - rect.top) < touch, nb = Math.abs(y - rect.bottom) < touch;
        if (nl && nt) return TL;
        if (nr && nt) return TR;
        if (nl && nb) return BL;
        if (nr && nb) return BR;
        if (ratio <= 0) {
            boolean inX = x > rect.left && x < rect.right, inY = y > rect.top && y < rect.bottom;
            if (nl && inY) return L;
            if (nr && inY) return R;
            if (nt && inX) return T;
            if (nb && inX) return B;
        }
        if (rect.contains(x, y)) return MOVE;
        return NONE;
    }

    private void drag(float x, float y) {
        float w = getWidth(), h = getHeight();
        float dx = x - lastX, dy = y - lastY;
        if (mode == MOVE) {
            float nl = Math.max(0, Math.min(w - rect.width(), rect.left + dx));
            float nt = Math.max(0, Math.min(h - rect.height(), rect.top + dy));
            rect.offsetTo(nl, nt);
            return;
        }
        if (ratio > 0) {
            if (mode == TL || mode == TR || mode == BL || mode == BR) lockedCorner(x, y, w, h);
            return;
        }
        float cx = Math.max(0, Math.min(w, x)), cy = Math.max(0, Math.min(h, y));
        switch (mode) {
            case TL: rect.left = Math.min(cx, rect.right - minSize); rect.top = Math.min(cy, rect.bottom - minSize); break;
            case TR: rect.right = Math.max(cx, rect.left + minSize); rect.top = Math.min(cy, rect.bottom - minSize); break;
            case BL: rect.left = Math.min(cx, rect.right - minSize); rect.bottom = Math.max(cy, rect.top + minSize); break;
            case BR: rect.right = Math.max(cx, rect.left + minSize); rect.bottom = Math.max(cy, rect.top + minSize); break;
            case L: rect.left = Math.min(cx, rect.right - minSize); break;
            case R: rect.right = Math.max(cx, rect.left + minSize); break;
            case T: rect.top = Math.min(cy, rect.bottom - minSize); break;
            case B: rect.bottom = Math.max(cy, rect.top + minSize); break;
        }
    }

    /** Corner drag with a fixed aspect ratio: the opposite corner stays where it is. */
    private void lockedCorner(float x, float y, float w, float h) {
        boolean left = mode == TL || mode == BL;
        boolean top = mode == TL || mode == TR;
        float ax = left ? rect.right : rect.left;
        float ay = top ? rect.bottom : rect.top;
        float availW = left ? ax : w - ax;
        float availH = top ? ay : h - ay;
        float nw = Math.abs(x - ax);
        nw = Math.max(minSize, Math.min(nw, availW));
        float nh = nw / ratio;
        if (nh > availH) {
            nh = availH;
            nw = nh * ratio;
        }
        if (nh < minSize / 2 || nw < minSize / 2) return;
        rect.set(left ? ax - nw : ax, top ? ay - nh : ay, left ? ax : ax + nw, top ? ay : ay + nh);
    }
}
