package com.simpleplayer.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Timeline with video thumbnails, two trim handles, a playhead, a time ruler,
 * pinch-to-zoom and horizontal scrolling (so the cut can be placed precisely).
 */
public class TrimBarView extends View {

    interface Listener {
        /** Playhead moved by the user (tap or scrub on the ruler). */
        void onSeek(long ms);

        /** A handle is being dragged (start or end), ms is its new position. */
        void onHandleDragging(boolean isStart, long ms);

        void onRangeChanged(long startMs, long endMs);
    }

    private static final long[] NICE = {100, 200, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000,
            120000, 300000, 600000, 1200000, 3600000};
    private static final int DRAG_NONE = 0, DRAG_START = 1, DRAG_END = 2, DRAG_SCRUB = 3, DRAG_PAN = 4;

    private final float d;
    private final float handleW, pad, rulerH, thumbH, minLenMs = 300;
    private final Paint thumbBg = new Paint();
    private final Paint dim = new Paint();
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF tmpF = new RectF();
    private final Rect srcR = new Rect(), dstR = new Rect();

    private Listener listener;
    private Uri uri;
    private long durationMs;
    private long startMs, endMs, playMs;
    private float zoom = 1f;
    private float scroll; // px

    private int drag = DRAG_NONE;
    private float dragOffset;
    private float lastX, downX;
    private boolean pinching;
    private final ScaleGestureDetector scaler;
    private final OverScroller scroller;
    private long visFrom, visTo; // visible thumbnails range (ms), read by the loader thread

    private final LruCache<Long, Bitmap> cache = new LruCache<>(90);
    private final Set<Long> pending = new HashSet<>();
    private ExecutorService loader;
    private MediaMetadataRetriever retriever;
    private float videoAspect = 16f / 9f;

    public TrimBarView(Context c) {
        this(c, null);
    }

    public TrimBarView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        handleW = 16 * d;
        pad = handleW + 6 * d;
        rulerH = 22 * d;
        thumbH = 56 * d;
        thumbBg.setColor(0xFF262626);
        dim.setColor(0xB3000000);
        frame.setColor(0xFFFFFFFF);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(3 * d);
        handle.setColor(0xFFFFFFFF);
        grip.setColor(0xFF000000);
        grip.setStrokeWidth(2 * d);
        grip.setStrokeCap(Paint.Cap.ROUND);
        head.setColor(0xFFFF453A);
        head.setStrokeWidth(2.5f * d);
        tick.setColor(0x88FFFFFF);
        tick.setStrokeWidth(1 * d);
        text.setColor(0xCCFFFFFF);
        text.setTextSize(10 * d);
        scroller = new OverScroller(c);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector det) {
                pinching = true;
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector det) {
                zoomAround(zoom * det.getScaleFactor(), det.getFocusX());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector det) {
                pinching = false;
            }
        });
    }

    // ================================================================== public API

    void setListener(Listener l) {
        listener = l;
    }

    long getStartMs() {
        return startMs;
    }

    long getEndMs() {
        return endMs;
    }

    void setSource(Uri u, long durMs, float aspect) {
        release();
        uri = u;
        durationMs = Math.max(1, durMs);
        startMs = 0;
        endMs = durationMs;
        playMs = 0;
        zoom = 1f;
        scroll = 0;
        videoAspect = aspect > 0 ? aspect : 16f / 9f;
        cache.evictAll();
        pending.clear();
        loader = Executors.newSingleThreadExecutor();
        invalidate();
    }

    void setPlayhead(long ms) {
        if (ms == playMs) return;
        playMs = ms;
        invalidate();
    }

    void zoomBy(float factor) {
        zoomAround(zoom * factor, getWidth() / 2f);
    }

    void release() {
        if (loader != null) {
            loader.shutdownNow();
            final MediaMetadataRetriever r = retriever;
            retriever = null;
            loader = null;
            if (r != null) {
                new Thread(() -> {
                    try {
                        r.release();
                    } catch (Throwable ignored) {
                    }
                }).start();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int hh = (int) (rulerH + thumbH + 8 * d);
        setMeasuredDimension(getDefaultSize(0, w), hh);
    }

    // ================================================================== geometry

    private float basePx() { // px per ms when fully zoomed out
        return (getWidth() - 2 * pad) / (float) durationMs;
    }

    private float pxPerMs() {
        return basePx() * zoom;
    }

    private float xOf(long ms) {
        return pad + ms * pxPerMs() - scroll;
    }

    private long msOf(float x) {
        return Math.max(0, Math.min(durationMs, Math.round((x - pad + scroll) / pxPerMs())));
    }

    private float maxZoom() {
        return Math.max(1f, Math.min(80f, durationMs / 1200f));
    }

    private void clampScroll() {
        float content = durationMs * pxPerMs();
        float view = getWidth() - 2 * pad;
        scroll = Math.max(0, Math.min(Math.max(0, content - view), scroll));
    }

    private void zoomAround(float newZoom, float focusX) {
        if (durationMs <= 0 || getWidth() == 0) return;
        float nz = Math.max(1f, Math.min(maxZoom(), newZoom));
        float t = (focusX - pad + scroll) / pxPerMs();
        zoom = nz;
        scroll = pad + t * pxPerMs() - focusX;
        scroll = Math.max(0, scroll + 0f);
        // keep the time under the focus fixed
        scroll = t * pxPerMs() - (focusX - pad);
        clampScroll();
        invalidate();
    }

    // ================================================================== drawing

    @Override
    protected void onDraw(Canvas c) {
        if (uri == null || durationMs <= 0 || getWidth() == 0) return;
        float w = getWidth();
        float top = rulerH;
        float bottom = rulerH + thumbH;
        float ppm = pxPerMs();

        drawRuler(c, w, ppm);

        // --- thumbnails
        c.save();
        c.clipRect(pad, top, w - pad, bottom);
        long bucket = bucketMs(ppm);
        long first = Math.max(0, msOf(pad) / bucket - 1);
        long last = msOf(w - pad) / bucket + 1;
        visFrom = first * bucket;
        visTo = (last + 1) * bucket;
        for (long k = first; k <= last; k++) {
            long t0 = k * bucket;
            if (t0 >= durationMs) break;
            long t1 = Math.min(durationMs, t0 + bucket);
            float x0 = xOf(t0), x1 = xOf(t1);
            long key = t0 + bucket / 2;
            Bitmap b = cache.get(key);
            if (b != null && !b.isRecycled()) {
                // center-crop the bitmap into the tile
                float tileAspect = (x1 - x0) / thumbH;
                float bAspect = (float) b.getWidth() / b.getHeight();
                if (bAspect > tileAspect) {
                    int sw = Math.round(b.getHeight() * tileAspect);
                    int sx = (b.getWidth() - sw) / 2;
                    srcR.set(sx, 0, sx + sw, b.getHeight());
                } else {
                    int sh = Math.round(b.getWidth() / tileAspect);
                    int sy = (b.getHeight() - sh) / 2;
                    srcR.set(0, sy, b.getWidth(), sy + sh);
                }
                dstR.set(Math.round(x0), Math.round(top), Math.round(x1) + 1, Math.round(bottom));
                c.drawBitmap(b, srcR, dstR, bmpPaint);
            } else {
                c.drawRect(x0, top, x1, bottom, thumbBg);
                request(key);
            }
        }
        // dim outside the selection
        float xs = xOf(startMs), xe = xOf(endMs);
        c.drawRect(pad, top, Math.max(pad, Math.min(w - pad, xs)), bottom, dim);
        c.drawRect(Math.max(pad, Math.min(w - pad, xe)), top, w - pad, bottom, dim);
        c.restore();

        // --- selection frame + handles
        c.drawLine(xs, top, xe, top, frame);
        c.drawLine(xs, bottom, xe, bottom, frame);
        drawHandle(c, xs - handleW, top, true, bottom);
        drawHandle(c, xe, top, false, bottom);

        // --- playhead
        float xp = xOf(playMs);
        if (xp >= pad - 2 && xp <= w - pad + 2) {
            c.drawLine(xp, top - 4 * d, xp, bottom + 4 * d, head);
            c.drawCircle(xp, top - 4 * d, 5 * d, head);
        }
    }

    private void drawHandle(Canvas c, float x, float top, boolean isStart, float bottom) {
        tmpF.set(x, top - 2 * d, x + handleW, bottom + 2 * d);
        c.drawRoundRect(tmpF, 5 * d, 5 * d, handle);
        float cx = x + handleW / 2, cy = (top + bottom) / 2;
        c.drawLine(cx, cy - 8 * d, cx, cy + 8 * d, grip);
    }

    private void drawRuler(Canvas c, float w, float ppm) {
        long step = NICE[NICE.length - 1];
        for (long s : NICE) {
            if (s * ppm >= 64 * d) {
                step = s;
                break;
            }
        }
        long from = (msOf(pad) / step) * step;
        for (long t = from; t <= durationMs && xOf(t) <= w - pad + 1; t += step) {
            float x = xOf(t);
            if (x < pad - 1) continue;
            c.drawLine(x, rulerH - 7 * d, x, rulerH - 2 * d, tick);
            String label = step < 1000
                    ? String.format(java.util.Locale.US, "%.1f", t / 1000f)
                    : MediaUtils.formatTime(t);
            float tw = text.measureText(label);
            float lx = Math.max(0, Math.min(w - tw, x - tw / 2));
            c.drawText(label, lx, rulerH - 10 * d, text);
        }
    }

    /** Thumbnail bucket: a "nice" duration so tiles are about as wide as the bar is tall. */
    private long bucketMs(float ppm) {
        float want = thumbH * videoAspect / ppm; // ms covered by one natural-width tile
        for (long s : NICE) {
            if (s >= want) return s;
        }
        return NICE[NICE.length - 1];
    }

    // ================================================================== thumbnails

    private void request(final long key) {
        final ExecutorService ex = loader;
        if (ex == null || pending.contains(key)) return;
        pending.add(key);
        final Uri src = uri;
        try {
            ex.execute(() -> {
                Bitmap bmp = null;
                try {
                    if (key < visFrom - 4000 || key > visTo + 4000) { // scrolled away already
                        return;
                    }
                    if (retriever == null) {
                        MediaMetadataRetriever r = new MediaMetadataRetriever();
                        r.setDataSource(getContext(), src);
                        retriever = r;
                    }
                    long us = Math.min(key, Math.max(0, durationMs - 1)) * 1000L;
                    int th = Math.max(48, Math.round(thumbH));
                    int tw = Math.round(th * videoAspect);
                    if (Build.VERSION.SDK_INT >= 27) {
                        bmp = retriever.getScaledFrameAtTime(us,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th);
                    } else {
                        Bitmap full = retriever.getFrameAtTime(us,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (full != null) {
                            bmp = Bitmap.createScaledBitmap(full, tw, th, true);
                            if (bmp != full) full.recycle();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    final Bitmap result = bmp;
                    post(() -> {
                        pending.remove(key);
                        if (result != null && uri == src) {
                            cache.put(key, result);
                            invalidate();
                        } else if (uri == src && result == null && key >= visFrom && key <= visTo) {
                            // keep an empty marker so we do not retry forever
                            cache.put(key, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888));
                        }
                    });
                }
            });
        } catch (Throwable t) {
            pending.remove(key);
        }
    }

    // ================================================================== touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (uri == null) return false;
        scaler.onTouchEvent(e);
        if (pinching || e.getPointerCount() > 1) {
            drag = DRAG_NONE;
            return true;
        }
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                scroller.forceFinished(true);
                lastX = x;
                downX = x;
                float xs = xOf(startMs), xe = xOf(endMs);
                float slop = 30 * d;
                float ds = Math.abs(x - (xs - handleW / 2)), de = Math.abs(x - (xe + handleW / 2));
                if (y > rulerH - 8 * d && Math.min(ds, de) < slop && ds <= de) {
                    drag = DRAG_START;
                    dragOffset = x - xs;
                } else if (y > rulerH - 8 * d && Math.min(ds, de) < slop) {
                    drag = DRAG_END;
                    dragOffset = x - xe;
                } else if (y < rulerH) {
                    drag = DRAG_SCRUB;
                    seekTo(x);
                } else {
                    drag = DRAG_PAN;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drag == DRAG_START || drag == DRAG_END) {
                    moveHandle(x);
                } else if (drag == DRAG_SCRUB) {
                    seekTo(x);
                } else if (drag == DRAG_PAN) {
                    scroll -= x - lastX;
                    clampScroll();
                    invalidate();
                }
                lastX = x;
                return true;
            case MotionEvent.ACTION_UP:
                if (drag == DRAG_PAN) {
                    if (Math.abs(x - downX) < 8 * d && e.getEventTime() - e.getDownTime() < 250) {
                        seekTo(x);
                    }
                }
                if (drag == DRAG_START || drag == DRAG_END) {
                    if (listener != null) listener.onRangeChanged(startMs, endMs);
                }
                drag = DRAG_NONE;
                return true;
            case MotionEvent.ACTION_CANCEL:
                drag = DRAG_NONE;
                return true;
        }
        return true;
    }

    private void seekTo(float x) {
        long ms = msOf(x);
        playMs = ms;
        invalidate();
        if (listener != null) listener.onSeek(ms);
    }

    private void moveHandle(float x) {
        boolean isStart = drag == DRAG_START;
        long ms = msOf(x - dragOffset);
        long minLen = Math.min((long) minLenMs, durationMs);
        if (isStart) {
            startMs = Math.max(0, Math.min(ms, endMs - minLen));
        } else {
            endMs = Math.min(durationMs, Math.max(ms, startMs + minLen));
        }
        // pan when the handle reaches the edge of the bar
        float edge = 36 * d;
        if (x < pad + edge) {
            scroll -= 12 * d;
        } else if (x > getWidth() - pad - edge) {
            scroll += 12 * d;
        }
        clampScroll();
        invalidate();
        if (listener != null) {
            listener.onHandleDragging(isStart, isStart ? startMs : endMs);
            listener.onRangeChanged(startMs, endMs);
        }
        if (x < pad + edge || x > getWidth() - pad - edge) postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.getCurrX();
            clampScroll();
            postInvalidateOnAnimation();
        }
    }
}
