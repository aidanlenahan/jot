package com.jot.notes;

import java.util.ArrayList;
import java.util.List;

/** Stroke geometry (pure Java): smoothing, simplification, partial erasing, hit tests. */
public final class Ink {
    private Ink() {}

    private static final int[] PASSES = {0, 2, 4};             // averaging passes on pen-up
    private static final float[] EPS = {0.25f, 0.5f, 0.8f};    // simplification tolerance (units)

    /** Pen-up smoothing: averaging passes (endpoints fixed) then Ramer–Douglas–Peucker. Returns {pts, wm}. */
    public static float[][] smooth(float[] p, float[] wm, int level) {
        int n = p.length / 2;
        if (n < 3) return new float[][]{p, wm};
        level = Math.max(0, Math.min(2, level));
        float[] a = p.clone();
        float[] w = wm == null ? null : wm.clone();
        for (int pass = 0; pass < PASSES[level]; pass++) {
            float[] b = a.clone();
            float[] bw = w == null ? null : w.clone();
            for (int i = 1; i < n - 1; i++) {
                b[2 * i] = (a[2 * i - 2] + 2 * a[2 * i] + a[2 * i + 2]) / 4f;
                b[2 * i + 1] = (a[2 * i - 1] + 2 * a[2 * i + 1] + a[2 * i + 3]) / 4f;
                if (bw != null) bw[i] = (w[i - 1] + 2 * w[i] + w[i + 1]) / 4f;
            }
            a = b; w = bw;
        }
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        rdp(a, 0, n - 1, EPS[level], keep);
        if (w != null) { // keep points where pressure changes noticeably
            int last = 0;
            for (int i = 1; i < n; i++) {
                if (keep[i]) { last = i; continue; }
                if (Math.abs(w[i] - w[last]) > 0.08f) { keep[i] = true; last = i; }
            }
        }
        int m = 0;
        for (boolean k : keep) if (k) m++;
        float[] out = new float[m * 2];
        float[] ow = w == null ? null : new float[m];
        int j = 0;
        for (int i = 0; i < n; i++) if (keep[i]) {
            if (ow != null) ow[j / 2] = w[i];
            out[j++] = a[2 * i]; out[j++] = a[2 * i + 1];
        }
        return new float[][]{out, ow};
    }

    private static void rdp(float[] p, int s, int e, float eps, boolean[] keep) {
        if (e <= s + 1) return;
        float max = -1; int idx = -1;
        for (int i = s + 1; i < e; i++) {
            float d = segDist2(p[2 * i], p[2 * i + 1], p[2 * s], p[2 * s + 1], p[2 * e], p[2 * e + 1]);
            if (d > max) { max = d; idx = i; }
        }
        if (max > eps * eps) {
            keep[idx] = true;
            rdp(p, s, idx, eps, keep);
            rdp(p, idx, e, eps, keep);
        }
    }

    public static boolean hits(float[] p, float x, float y, float r) {
        if (p.length == 2) return dist2(x, y, p[0], p[1]) <= r * r;
        for (int i = 0; i + 3 < p.length; i += 2)
            if (segDist2(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= r * r) return true;
        return false;
    }

    /**
     * Partial eraser: removes the part of the stroke within r of (x,y).
     * Returns null if the stroke isn't touched, else the remaining pieces as {pts, wm} pairs (may be empty).
     */
    public static List<float[][]> erase(float[] p, float[] wm, float x, float y, float r) {
        if (!hits(p, x, y, r)) return null;
        float[][] rs = resample(p, wm, 2.5f);
        float[] q = rs[0], qw = rs[1];
        int n = q.length / 2;
        List<float[][]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= n; i++) {
            boolean gone = i == n || dist2(q[2 * i], q[2 * i + 1], x, y) <= r * r;
            if (!gone && start < 0) start = i;
            if (gone && start >= 0) {
                int len = i - start;
                if (len >= 2) {
                    float[] np = new float[len * 2];
                    System.arraycopy(q, start * 2, np, 0, len * 2);
                    float[] nw = null;
                    if (qw != null) { nw = new float[len]; System.arraycopy(qw, start, nw, 0, len); }
                    out.add(new float[][]{np, nw});
                }
                start = -1;
            }
        }
        return out;
    }

    /** Re-samples a polyline so points are at most 'step' apart (interpolating widths). */
    static float[][] resample(float[] p, float[] wm, float step) {
        int n = p.length / 2;
        List<Float> xs = new ArrayList<>(), ws = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            xs.add(p[2 * i]); xs.add(p[2 * i + 1]);
            if (wm != null) ws.add(wm[i]);
            if (i + 1 < n) {
                float d = (float) Math.sqrt(dist2(p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3]));
                int k = (int) (d / step);
                for (int j = 1; j <= k; j++) {
                    float t = j / (float) (k + 1);
                    xs.add(p[2 * i] + (p[2 * i + 2] - p[2 * i]) * t);
                    xs.add(p[2 * i + 1] + (p[2 * i + 3] - p[2 * i + 1]) * t);
                    if (wm != null) ws.add(wm[i] + (wm[i + 1] - wm[i]) * t);
                }
            }
        }
        float[] a = new float[xs.size()];
        for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
        float[] b = null;
        if (wm != null) { b = new float[ws.size()]; for (int i = 0; i < b.length; i++) b[i] = ws.get(i); }
        return new float[][]{a, b};
    }

    /** Point-in-polygon (even-odd) for lasso selection. */
    public static boolean inside(float[] poly, float x, float y) {
        boolean in = false;
        int n = poly.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = poly[2 * i], yi = poly[2 * i + 1], xj = poly[2 * j], yj = poly[2 * j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi) in = !in;
        }
        return in;
    }

    static float dist2(float ax, float ay, float bx, float by) { float dx = ax - bx, dy = ay - by; return dx * dx + dy * dy; }

    static float segDist2(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay;
        float len = vx * vx + vy * vy;
        float t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (py - ay) * vy) / len));
        return dist2(px, py, ax + t * vx, ay + t * vy);
    }
}
