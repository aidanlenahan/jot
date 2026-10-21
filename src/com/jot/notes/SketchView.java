package com.jot.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

/**
 * Inline handwriting / freewrite canvas. Strokes are stored in resolution-independent units
 * (block width = 1000) so a sketch looks the same on any screen and in exports.
 *
 * Touch rules: when inactive, a finger scrolls the page and a tap activates the sketch; a stylus
 * always writes. When active, one finger writes (unless palm rejection is on and a stylus has been
 * seen), two fingers pinch-zoom / pan.
 */
public class SketchView extends View {

    public static final int PEN = 0, HIGHLIGHTER = 1, ERASER = 2, LASSO = 3;

    // ---- shared tool state (set from settings / toolbar)
    public static int tool = PEN;
    public static int penColor = 0;            // 0 = automatic ink
    public static int markerColor = 0xFFFFD54F;
    public static float penWidth = 4f;         // units
    public static float markerWidth = 22f;
    public static boolean pressureMode;        // "P" size preset selected
    public static int smoothing = 1;           // 0 off, 1 smooth, 2 extra
    public static boolean palmRejection, shapeSnap = true, partialEraser;
    public static boolean stylusSeen;
    public static final float PRESSURE_BASE = 4.5f;

    private static final float[] FOLLOW = {1f, 0.5f, 0.3f};   // live stabiliser (lower = steadier)

    public interface Listener {
        void onActivate(SketchView v);
        void onChanged(SketchView v);
        void onSelectionChanged(SketchView v);
    }

    public final Note.NBlock block;
    private final Ui.Pal pal;
    private final Listener listener;
    private boolean active, readOnly;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);

    // current stroke
    private Note.Stroke current;
    private final FloatList curPts = new FloatList(), curW = new FloatList();
    private boolean curPressure, snapped;
    private int drawPointer = -1;
    private float rawX, rawY;

    // tap-to-activate
    private boolean tapCandidate;
    private float downX, downY;

    // pinch zoom
    private float zoom = 1f, panX, panY;
    private boolean pinching;
    private float pinchD0, pinchZ0, pinchCx, pinchCy;

    // erase gesture
    private boolean eraseSnapPushed;

    // lasso
    private final FloatList lasso = new FloatList();
    private final List<Note.Stroke> selection = new ArrayList<>();
    private List<Note.Stroke> selOrig;
    private RectF selBox, selBox0;
    private int selMode; // 0 none, 1 move, 2 resize
    private float selDownX, selDownY;

    // hold-to-snap
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float holdX, holdY;
    private final Runnable snapRun = this::trySnap;

    // undo / redo snapshots
    private static final class Snap { List<Note.Stroke> strokes; int bg; float height; }
    private final List<Snap> undo = new ArrayList<>(), redo = new ArrayList<>();

    // bitmap cache of committed strokes
    private Bitmap cache;
    private boolean cacheDirty = true;

    public SketchView(Context c, Note.NBlock block, Ui.Pal pal, Listener l) {
        super(c);
        this.block = block; this.pal = pal; this.listener = l;
        guide.setStyle(Paint.Style.STROKE);
        frame.setStyle(Paint.Style.STROKE);
        dash.setStyle(Paint.Style.STROKE);
        dash.setPathEffect(new DashPathEffect(new float[]{Ui.dp(c, 6), Ui.dp(c, 4)}, 0));
        setContentDescription("Handwriting area");
    }

    public void setActive(boolean a) {
        active = a;
        if (!a) {
            clearSelection(); resetZoom();
            if (cache != null) { cache.recycle(); cache = null; }
            cacheDirty = true;
        }
        invalidate();
    }
    public boolean isActive() { return active; }
    public void setReadOnly(boolean r) { readOnly = r; }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public boolean hasSelection() { return !selection.isEmpty(); }
    public boolean isZoomed() { return zoom > 1.01f; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * block.height / Note.UNITS));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { invalidateCache(); }

    private float scale() { return getWidth() / Note.UNITS; }

    private void invalidateCache() { cacheDirty = true; invalidate(); }

    // =====================================================================
    // Drawing
    // =====================================================================

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = Ui.dp(getContext(), 12);
        float s = scale();
        RectF box = new RectF(1, 1, w - 1, h - 1);
        fill.setColor(pal.card);
        fill.setStyle(Paint.Style.FILL);
        c.drawRoundRect(box, r, r, fill);

        c.save();
        c.clipRect(box);
        c.translate(panX, panY);
        c.scale(zoom, zoom);
        guide.setColor(pal.border);
        guide.setStrokeWidth(Math.max(1, s * 1.5f) / zoom);
        if (block.bg == Note.BG_LINED) {
            for (float y = 90; y < block.height; y += 90) c.drawLine(30 * s, y * s, w - 30 * s, y * s, guide);
        } else if (block.bg == Note.BG_GRID) {
            for (float y = 50; y < block.height; y += 50) c.drawLine(0, y * s, w, y * s, guide);
            for (float x = 50; x < Note.UNITS; x += 50) c.drawLine(x * s, 0, x * s, h, guide);
        }
        if (zoom > 1.01f || !active || !useCache(w, h)) {
            c.save();
            c.scale(s, s);
            drawStrokes(c, block.strokes, pal.text, paint);
            c.restore();
        } else {
            if (cacheDirty || cache == null) rebuildCache((int) w, (int) h, s);
            c.drawBitmap(cache, 0, 0, null);
        }
        c.scale(s, s);
        if (current != null) {
            Note.Stroke tmp = new Note.Stroke();
            tmp.color = current.color; tmp.width = current.width; tmp.highlighter = current.highlighter;
            tmp.pts = curPts.toArray(); tmp.wm = curPressure ? curW.toArray() : null;
            drawStroke(c, tmp, pal.text, paint);
        }
        float unitPx = 1f / (s * zoom); // one screen pixel in unit space
        if (lasso.size() >= 4) {
            dash.setColor(pal.accent);
            dash.setStrokeWidth(Ui.dp(getContext(), 1.5f) * unitPx);
            Path lp = new Path();
            lp.moveTo(lasso.get(0), lasso.get(1));
            for (int i = 2; i < lasso.size(); i += 2) lp.lineTo(lasso.get(i), lasso.get(i + 1));
            c.drawPath(lp, dash);
        }
        if (selBox != null) {
            dash.setColor(pal.accent);
            dash.setStrokeWidth(Ui.dp(getContext(), 1.5f) * unitPx);
            c.drawRect(selBox, dash);
            fill.setColor(pal.accent);
            c.drawCircle(selBox.right, selBox.bottom, Ui.dp(getContext(), 9) * unitPx, fill);
        }
        c.restore();

        frame.setColor(active ? pal.accent : pal.border);
        frame.setStrokeWidth(Ui.dp(getContext(), active ? 2 : 1));
        c.drawRoundRect(box, r, r, frame);
        if (zoom > 1.01f) {
            fill.setColor(pal.accent);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(pal.onAccent);
            tp.setTextSize(Ui.dp(getContext(), 12));
            String label = Math.round(zoom * 100) + "%";
            float tw = tp.measureText(label), pad = Ui.dp(getContext(), 6);
            RectF pill = new RectF(w - tw - 3 * pad, pad, w - pad, pad + Ui.dp(getContext(), 22));
            c.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, fill);
            c.drawText(label, pill.left + pad, pill.bottom - Ui.dp(getContext(), 6.5f), tp);
        }
    }

    private boolean useCache(float w, float h) { return w * h * 4 < 40_000_000; }

    private void rebuildCache(int w, int h, float s) {
        if (cache == null || cache.getWidth() != w || cache.getHeight() != h) {
            if (cache != null) cache.recycle();
            cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        cache.eraseColor(0);
        Canvas cc = new Canvas(cache);
        cc.scale(s, s);
        drawStrokes(cc, block.strokes, pal.text, paint);
        cacheDirty = false;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (cache != null) { cache.recycle(); cache = null; }
        cacheDirty = true;
        handler.removeCallbacks(snapRun);
    }

    /** Draws strokes in unit space (caller scales the canvas). */
    public static void drawStrokes(Canvas c, List<Note.Stroke> strokes, int autoInk, Paint paint) {
        for (Note.Stroke st : strokes) drawStroke(c, st, autoInk, paint);
    }

    static void drawStroke(Canvas c, Note.Stroke st, int autoInk, Paint paint) {
        int color = st.color == 0 ? autoInk : st.color;
        if (st.highlighter) color = (color & 0x00FFFFFF) | 0x66000000;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(st.highlighter ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        float[] pts = st.pts;
        int n = pts.length;
        if (n < 2) return;
        if (n == 2) {
            paint.setStyle(Paint.Style.FILL);
            float w = st.width * (st.wm != null && st.wm.length > 0 ? st.wm[0] : 1f);
            c.drawCircle(pts[0], pts[1], w / 2f, paint);
            return;
        }
        if (st.wm == null || st.wm.length != n / 2) {
            paint.setStrokeWidth(st.width);
            Path path = new Path();
            path.moveTo(pts[0], pts[1]);
            for (int i = 2; i < n - 2; i += 2) {
                float mx = (pts[i] + pts[i + 2]) / 2f, my = (pts[i + 1] + pts[i + 3]) / 2f;
                path.quadTo(pts[i], pts[i + 1], mx, my);
            }
            path.lineTo(pts[n - 2], pts[n - 1]);
            c.drawPath(path, paint);
            return;
        }
        // variable width (pressure): one short curve per point, each with its own width
        int m = n / 2;
        Path seg = new Path();
        float sx = pts[0], sy = pts[1];
        for (int i = 1; i < m; i++) {
            seg.reset();
            seg.moveTo(sx, sy);
            float ex, ey;
            if (i < m - 1) {
                ex = (pts[2 * i] + pts[2 * i + 2]) / 2f; ey = (pts[2 * i + 1] + pts[2 * i + 3]) / 2f;
                seg.quadTo(pts[2 * i], pts[2 * i + 1], ex, ey);
            } else {
                ex = pts[2 * i]; ey = pts[2 * i + 1];
                seg.lineTo(ex, ey);
            }
            paint.setStrokeWidth(Math.max(0.6f, st.width * (st.wm[i - 1] + st.wm[i]) / 2f));
            c.drawPath(seg, paint);
            sx = ex; sy = ey;
        }
    }

    // =====================================================================
    // Touch
    // =====================================================================

    private static boolean isStylus(MotionEvent e, int idx) {
        int t = e.getToolType(idx);
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER;
    }

    private float ux(float x) { return (x - panX) / zoom / scale(); }
    private float uy(float y) { return (y - panY) / zoom / scale(); }

    private void disallowParent(boolean d) {
        ViewParent vp = getParent();
        if (vp != null) vp.requestDisallowInterceptTouchEvent(d);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (readOnly) return false;
        int act = e.getActionMasked();
        int idx = e.getActionIndex();

        switch (act) {
            case MotionEvent.ACTION_DOWN: {
                boolean stylus = isStylus(e, 0);
                if (stylus) stylusSeen = true;
                if (!active && !stylus) {
                    tapCandidate = true; downX = e.getX(); downY = e.getY();
                    return true;
                }
                disallowParent(true);
                if (!active && listener != null) listener.onActivate(this);
                if (!stylus && palmRejection && stylusSeen) return true; // ignore the palm, still own the gesture
                startPointer(e, 0);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (!active) return true;
                if (isStylus(e, idx)) {
                    stylusSeen = true;
                    cancelCurrent();
                    pinching = false;
                    startPointer(e, idx);
                    return true;
                }
                if (drawPointer >= 0 && isStylus(e, e.findPointerIndex(drawPointer))) return true; // stylus busy: ignore fingers
                if (fingerCount(e) >= 2) startPinch(e);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!active) {
                    if (Math.abs(e.getX() - downX) > Ui.dp(getContext(), 8) || Math.abs(e.getY() - downY) > Ui.dp(getContext(), 8)) tapCandidate = false;
                    return true;
                }
                if (pinching) { updatePinch(e); return true; }
                if (drawPointer >= 0) {
                    int pi = e.findPointerIndex(drawPointer);
                    if (pi >= 0) movePointer(e, pi);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(idx);
                if (id == drawPointer) { endPointer(); }
                if (pinching && fingerCount(e) - 1 < 2) pinching = false;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!active) {
                    if (tapCandidate && listener != null) { listener.onActivate(this); performClick(); }
                    tapCandidate = false;
                    return true;
                }
                if (e.getPointerId(0) == drawPointer) endPointer();
                pinching = false;
                disallowParent(false);
                performClick();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                tapCandidate = false;
                cancelCurrent();
                pinching = false;
                disallowParent(false);
                return true;
            }
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    private static int fingerCount(MotionEvent e) {
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (!isStylus(e, i)) n++;
        return n;
    }

    private int modeFor(MotionEvent e, int idx) {
        boolean stylus = isStylus(e, idx);
        if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER) return ERASER;
        if (stylus && (e.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0) return ERASER;
        return tool;
    }

    private int gestureMode;

    private void startPointer(MotionEvent e, int idx) {
        drawPointer = e.getPointerId(idx);
        gestureMode = modeFor(e, idx);
        float x = ux(e.getX(idx)), y = uy(e.getY(idx));
        switch (gestureMode) {
            case ERASER:
                eraseSnapPushed = false;
                eraseAt(x, y);
                return;
            case LASSO:
                startLasso(x, y);
                return;
        }
        current = new Note.Stroke();
        current.highlighter = gestureMode == HIGHLIGHTER;
        current.color = gestureMode == HIGHLIGHTER ? markerColor : penColor;
        curPressure = gestureMode == PEN && pressureMode && isStylus(e, idx);
        current.width = gestureMode == HIGHLIGHTER ? markerWidth : (pressureMode ? PRESSURE_BASE : penWidth);
        curPts.clear(); curW.clear();
        snapped = false;
        addPoint(x, y, e.getPressure(idx));
        holdX = x; holdY = y;
        scheduleSnap();
        invalidate();
    }

    private void movePointer(MotionEvent e, int pi) {
        switch (gestureMode) {
            case ERASER:
                for (int h = 0; h < e.getHistorySize(); h++) eraseAt(ux(e.getHistoricalX(pi, h)), uy(e.getHistoricalY(pi, h)));
                eraseAt(ux(e.getX(pi)), uy(e.getY(pi)));
                return;
            case LASSO:
                moveLasso(ux(e.getX(pi)), uy(e.getY(pi)));
                return;
        }
        if (current == null || snapped) return;
        for (int h = 0; h < e.getHistorySize(); h++)
            addPoint(ux(e.getHistoricalX(pi, h)), uy(e.getHistoricalY(pi, h)), e.getHistoricalPressure(pi, h));
        float x = ux(e.getX(pi)), y = uy(e.getY(pi));
        addPoint(x, y, e.getPressure(pi));
        if (Ink.dist2(x, y, holdX, holdY) > 16) { holdX = x; holdY = y; scheduleSnap(); }
        invalidate();
    }

    private void endPointer() {
        handler.removeCallbacks(snapRun);
        int mode = gestureMode;
        drawPointer = -1;
        if (mode == LASSO) { endLasso(); return; }
        if (mode == ERASER) return;
        if (current == null || curPts.size() == 0) { current = null; return; }
        float[] pts = curPts.toArray();
        float[] wm = curPressure ? curW.toArray() : null;
        if (!snapped && smoothing > 0 && pts.length >= 4) {
            // reach the real end point that the stabiliser lagged behind
            pts = append(pts, rawX, rawY);
            if (wm != null) wm = append1(wm, wm[wm.length - 1]);
        }
        if (!snapped) {
            float[][] sm = Ink.smooth(pts, wm, smoothing);
            pts = sm[0]; wm = sm[1];
        }
        current.pts = pts; current.wm = wm;
        pushUndo();
        block.strokes = new ArrayList<>(block.strokes);
        block.strokes.add(current);
        current = null; curPts.clear(); curW.clear();
        growIfNeeded();
        changed();
    }

    private void cancelCurrent() {
        handler.removeCallbacks(snapRun);
        current = null; curPts.clear(); curW.clear();
        lasso.clear();
        drawPointer = -1;
        invalidate();
    }

    private static float[] append(float[] a, float x, float y) {
        float[] r = java.util.Arrays.copyOf(a, a.length + 2);
        r[a.length] = x; r[a.length + 1] = y;
        return r;
    }

    private static float[] append1(float[] a, float v) {
        float[] r = java.util.Arrays.copyOf(a, a.length + 1);
        r[a.length] = v;
        return r;
    }

    private void addPoint(float x, float y, float pressure) {
        x = Math.max(0, Math.min(Note.UNITS, x));
        y = Math.max(0, Math.min(block.height, y));
        rawX = x; rawY = y;
        int n = curPts.size();
        if (n >= 2 && smoothing > 0) {
            float k = FOLLOW[smoothing];
            x = curPts.get(n - 2) + (x - curPts.get(n - 2)) * k;
            y = curPts.get(n - 1) + (y - curPts.get(n - 1)) * k;
        }
        if (n >= 2) {
            float dx = x - curPts.get(n - 2), dy = y - curPts.get(n - 1);
            if (dx * dx + dy * dy < 1.2f) return;
        }
        curPts.add(x); curPts.add(y);
        if (curPressure) {
            float p = Math.max(0f, Math.min(1f, pressure));
            float w = 0.3f + 1.4f * p;
            int m = curW.size();
            if (m > 0) w = curW.get(m - 1) + (w - curW.get(m - 1)) * 0.5f; // soften jumps
            curW.add(w);
        }
    }

    // ---- hold to snap

    private void scheduleSnap() {
        handler.removeCallbacks(snapRun);
        if (shapeSnap && current != null && !current.highlighter) handler.postDelayed(snapRun, 550);
    }

    private void trySnap() {
        if (current == null || snapped || curPts.size() < 8) return;
        float[] shape = Shapes.recognize(curPts.toArray());
        if (shape == null) return;
        curPts.clear();
        for (float f : shape) curPts.add(f);
        curPressure = false; curW.clear();
        if (pressureMode) current.width = PRESSURE_BASE;
        snapped = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    // ---- pinch zoom

    private void startPinch(MotionEvent e) {
        cancelCurrent();
        pinching = true;
        float[] f = twoFingers(e);
        pinchD0 = Math.max(1, (float) Math.hypot(f[2] - f[0], f[3] - f[1]));
        pinchZ0 = zoom;
        float mx = (f[0] + f[2]) / 2, my = (f[1] + f[3]) / 2;
        pinchCx = (mx - panX) / zoom; pinchCy = (my - panY) / zoom;
    }

    private void updatePinch(MotionEvent e) {
        if (fingerCount(e) < 2) return;
        float[] f = twoFingers(e);
        float d = (float) Math.hypot(f[2] - f[0], f[3] - f[1]);
        zoom = Math.max(1f, Math.min(5f, pinchZ0 * d / pinchD0));
        float mx = (f[0] + f[2]) / 2, my = (f[1] + f[3]) / 2;
        panX = mx - pinchCx * zoom; panY = my - pinchCy * zoom;
        clampPan();
        invalidate();
    }

    private float[] twoFingers(MotionEvent e) {
        float[] out = new float[4];
        int k = 0;
        for (int i = 0; i < e.getPointerCount() && k < 4; i++) {
            if (isStylus(e, i)) continue;
            out[k++] = e.getX(i); out[k++] = e.getY(i);
        }
        return out;
    }

    private void clampPan() {
        float w = getWidth(), h = getHeight();
        panX = Math.min(0, Math.max(w - w * zoom, panX));
        panY = Math.min(0, Math.max(h - h * zoom, panY));
    }

    public void resetZoom() { zoom = 1f; panX = panY = 0; invalidate(); }

    // ---- eraser

    private void eraseAt(float x, float y) {
        float radius = 14f / zoom;
        boolean changedAny = false;
        List<Note.Stroke> next = new ArrayList<>(block.strokes.size());
        for (Note.Stroke st : block.strokes) {
            float r = radius + st.maxW() / 2f;
            if (partialEraser) {
                List<float[][]> pieces = Ink.erase(st.pts, st.wm, x, y, r);
                if (pieces == null) { next.add(st); continue; }
                changedAny = true;
                for (float[][] pc : pieces) {
                    Note.Stroke ns = new Note.Stroke();
                    ns.color = st.color; ns.width = st.width; ns.highlighter = st.highlighter;
                    ns.pts = pc[0]; ns.wm = pc[1];
                    next.add(ns);
                }
            } else if (Ink.hits(st.pts, x, y, r)) {
                changedAny = true;
            } else next.add(st);
        }
        if (changedAny) {
            if (!eraseSnapPushed) { pushUndo(); eraseSnapPushed = true; }
            selection.retainAll(next);
            block.strokes = next;
            changed();
        }
    }

    // ---- lasso

    private void startLasso(float x, float y) {
        if (selBox != null) {
            float handle = Ui.dp(getContext(), 20) / (scale() * zoom);
            if (Math.hypot(x - selBox.right, y - selBox.bottom) <= handle) { beginSelEdit(2, x, y); return; }
            RectF hit = new RectF(selBox);
            hit.inset(-handle / 2, -handle / 2);
            if (hit.contains(x, y)) { beginSelEdit(1, x, y); return; }
            clearSelection();
        }
        lasso.clear();
        lasso.add(x); lasso.add(y);
        invalidate();
    }

    private void beginSelEdit(int mode, float x, float y) {
        selMode = mode;
        selDownX = x; selDownY = y;
        pushUndo();
        selOrig = new ArrayList<>();
        List<Note.Stroke> next = new ArrayList<>(block.strokes);
        List<Note.Stroke> newSel = new ArrayList<>();
        for (Note.Stroke s : selection) {
            Note.Stroke copy = s.copy();
            selOrig.add(s);
            int i = next.indexOf(s);
            if (i >= 0) next.set(i, copy);
            newSel.add(copy);
        }
        block.strokes = next;
        selection.clear(); selection.addAll(newSel);
        selBox0 = new RectF(selBox);
    }

    private void moveLasso(float x, float y) {
        if (selMode == 0) {
            lasso.add(x); lasso.add(y);
            invalidate();
            return;
        }
        float sx = 1, sy = 1, tx, ty;
        if (selMode == 1) {
            tx = x - selDownX; ty = y - selDownY;
        } else {
            float w0 = Math.max(10, selBox0.width()), h0 = Math.max(10, selBox0.height());
            float f = Math.max(0.1f, Math.max((x - selBox0.left) / w0, (y - selBox0.top) / h0));
            sx = sy = f; tx = 0; ty = 0;
        }
        for (int i = 0; i < selection.size(); i++) {
            Note.Stroke o = selOrig.get(i), s = selection.get(i);
            for (int k = 0; k < o.pts.length; k += 2) {
                s.pts[k] = selBox0.left + (o.pts[k] - selBox0.left) * sx + tx;
                s.pts[k + 1] = selBox0.top + (o.pts[k + 1] - selBox0.top) * sy + ty;
            }
            s.width = o.width * sx;
        }
        selBox = new RectF(selBox0.left + tx, selBox0.top + ty,
                selBox0.left + tx + selBox0.width() * sx, selBox0.top + ty + selBox0.height() * sy);
        invalidateCache();
    }

    private void endLasso() {
        if (selMode != 0) {
            selMode = 0;
            selOrig = null;
            if (selBox.bottom > block.height - 40) { block.height = Math.min(12000, selBox.bottom + 200); requestLayout(); }
            changed();
            return;
        }
        float[] poly = lasso.toArray();
        lasso.clear();
        selection.clear();
        if (poly.length >= 6) {
            for (Note.Stroke s : block.strokes) {
                int in = 0, n = s.pts.length / 2;
                for (int i = 0; i < n; i++) if (Ink.inside(poly, s.pts[2 * i], s.pts[2 * i + 1])) in++;
                if (n > 0 && in >= Math.max(1, n * 0.6f)) selection.add(s);
            }
        }
        updateSelBox();
        if (listener != null) listener.onSelectionChanged(this);
        invalidate();
    }

    private void updateSelBox() {
        if (selection.isEmpty()) { selBox = null; return; }
        RectF b = null;
        for (Note.Stroke s : selection) {
            float pad = s.maxW() / 2 + 6;
            for (int i = 0; i < s.pts.length; i += 2) {
                RectF p = new RectF(s.pts[i] - pad, s.pts[i + 1] - pad, s.pts[i] + pad, s.pts[i + 1] + pad);
                if (b == null) b = p; else b.union(p);
            }
        }
        selBox = b;
    }

    public void clearSelection() {
        boolean had = !selection.isEmpty() || selBox != null;
        selection.clear(); selBox = null; selMode = 0; lasso.clear();
        if (had) { if (listener != null) listener.onSelectionChanged(this); invalidate(); }
    }

    public void recolorSelection(int color) {
        if (selection.isEmpty()) return;
        pushUndo();
        List<Note.Stroke> next = new ArrayList<>(block.strokes);
        List<Note.Stroke> newSel = new ArrayList<>();
        for (Note.Stroke s : selection) {
            Note.Stroke c = s.copy();
            c.color = color;
            int i = next.indexOf(s);
            if (i >= 0) next.set(i, c);
            newSel.add(c);
        }
        block.strokes = next;
        selection.clear(); selection.addAll(newSel);
        changed();
    }

    public void deleteSelection() {
        if (selection.isEmpty()) return;
        pushUndo();
        List<Note.Stroke> next = new ArrayList<>(block.strokes);
        next.removeAll(selection);
        block.strokes = next;
        clearSelection();
        changed();
    }

    public void duplicateSelection() {
        if (selection.isEmpty()) return;
        pushUndo();
        List<Note.Stroke> next = new ArrayList<>(block.strokes);
        List<Note.Stroke> copies = new ArrayList<>();
        for (Note.Stroke s : selection) {
            Note.Stroke c = s.copy();
            for (int i = 0; i < c.pts.length; i++) c.pts[i] += 20;
            copies.add(c);
        }
        next.addAll(copies);
        block.strokes = next;
        selection.clear(); selection.addAll(copies);
        updateSelBox();
        growIfNeeded();
        changed();
    }

    // =====================================================================
    // Undo / redo & block edits
    // =====================================================================

    private Snap snap() {
        Snap s = new Snap();
        s.strokes = new ArrayList<>(block.strokes); s.bg = block.bg; s.height = block.height;
        return s;
    }

    private void restore(Snap s) {
        block.strokes = new ArrayList<>(s.strokes); block.bg = s.bg;
        if (block.height != s.height) { block.height = s.height; requestLayout(); }
        clearSelection();
    }

    private void pushUndo() {
        undo.add(snap());
        if (undo.size() > 200) undo.remove(0);
        redo.clear();
    }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.add(snap());
        restore(undo.remove(undo.size() - 1));
        changed();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.add(snap());
        restore(redo.remove(redo.size() - 1));
        changed();
    }

    public void clearAll() {
        if (block.strokes.isEmpty()) return;
        pushUndo();
        block.strokes = new ArrayList<>();
        clearSelection();
        changed();
    }

    public void cycleBackground() {
        pushUndo();
        block.bg = (block.bg + 1) % 3;
        changed();
    }

    public void addSpace() {
        pushUndo();
        block.height = Math.min(block.height + 450, 12000);
        requestLayout();
        changed();
    }

    public void trim() {
        float target = Math.max(300, block.contentBottom() + 120);
        if (target < block.height) {
            pushUndo();
            block.height = target;
            resetZoom();
            requestLayout();
            changed();
        }
    }

    private void growIfNeeded() {
        if (block.contentBottom() > block.height - 140) {
            block.height = Math.min(block.height + 450, 12000);
            requestLayout();
        }
    }

    private void changed() {
        invalidateCache();
        if (listener != null) listener.onChanged(this);
    }

    /** Minimal growable float array. */
    static final class FloatList {
        float[] a = new float[256]; int n;
        void add(float v) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = v; }
        float get(int i) { return a[i]; }
        int size() { return n; }
        void clear() { n = 0; }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
