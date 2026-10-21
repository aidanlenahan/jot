package com.jot.notes;

/** "Hold to snap": turns a rough stroke into a straight line, rectangle, circle or ellipse (pure Java). */
public final class Shapes {
    private Shapes() {}

    /** Returns replacement points, or null when the stroke doesn't look like a shape. */
    public static float[] recognize(float[] p) {
        int n = p.length / 2;
        if (n < 2) return null;
        float x0 = p[0], y0 = p[1], x1 = p[2 * n - 2], y1 = p[2 * n - 1];
        float minx = Float.MAX_VALUE, miny = Float.MAX_VALUE, maxx = -Float.MAX_VALUE, maxy = -Float.MAX_VALUE;
        float pathLen = 0;
        for (int i = 0; i < n; i++) {
            float x = p[2 * i], y = p[2 * i + 1];
            minx = Math.min(minx, x); maxx = Math.max(maxx, x); miny = Math.min(miny, y); maxy = Math.max(maxy, y);
            if (i > 0) pathLen += dist(p[2 * i - 2], p[2 * i - 1], x, y);
        }
        float w = maxx - minx, h = maxy - miny, size = Math.max(w, h);
        if (size < 25) return null;

        // straight line
        float len = dist(x0, y0, x1, y1);
        if (len > 25 && pathLen < len * 1.25f) {
            float maxDev = 0;
            for (int i = 0; i < n; i++) maxDev = Math.max(maxDev, segDist(p[2 * i], p[2 * i + 1], x0, y0, x1, y1));
            if (maxDev < Math.max(6f, 0.07f * len)) return new float[]{x0, y0, x1, y1};
        }

        // closed shapes
        if (dist(x0, y0, x1, y1) > 0.35f * size || pathLen < 2f * size) return null;
        if (w < 12 || h < 12) return null;
        float cx = (minx + maxx) / 2, cy = (miny + maxy) / 2, rx = w / 2, ry = h / 2;
        float ellErr = 0, rectErr = 0;
        for (int i = 0; i < n; i++) {
            float x = p[2 * i], y = p[2 * i + 1];
            float dx = (x - cx) / rx, dy = (y - cy) / ry;
            ellErr += Math.abs((float) Math.sqrt(dx * dx + dy * dy) - 1f);
            float d = Math.min(Math.min(Math.abs(x - minx) / w, Math.abs(maxx - x) / w),
                               Math.min(Math.abs(y - miny) / h, Math.abs(maxy - y) / h));
            rectErr += d;
        }
        ellErr /= n; rectErr /= n;
        if (Math.min(ellErr, rectErr) > 0.13f) return null;

        if (rectErr < ellErr) {
            return densify(new float[]{minx, miny, maxx, miny, maxx, maxy, minx, maxy, minx, miny}, 5f);
        }
        if (Math.abs(rx - ry) / Math.max(rx, ry) < 0.15f) { float r = (rx + ry) / 2; rx = r; ry = r; }
        int segs = 72;
        float[] out = new float[(segs + 1) * 2];
        for (int i = 0; i <= segs; i++) {
            double a = 2 * Math.PI * i / segs - Math.PI / 2;
            out[2 * i] = cx + (float) (rx * Math.cos(a));
            out[2 * i + 1] = cy + (float) (ry * Math.sin(a));
        }
        return out;
    }

    /** Adds points along straight edges so curve smoothing keeps corners crisp. */
    static float[] densify(float[] poly, float step) {
        java.util.ArrayList<Float> out = new java.util.ArrayList<>();
        for (int i = 0; i + 3 < poly.length; i += 2) {
            float ax = poly[i], ay = poly[i + 1], bx = poly[i + 2], by = poly[i + 3];
            int k = Math.max(1, (int) (dist(ax, ay, bx, by) / step));
            for (int j = 0; j < k; j++) { out.add(ax + (bx - ax) * j / k); out.add(ay + (by - ay) * j / k); }
        }
        out.add(poly[poly.length - 2]); out.add(poly[poly.length - 1]);
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    static float dist(float ax, float ay, float bx, float by) { float dx = ax - bx, dy = ay - by; return (float) Math.sqrt(dx * dx + dy * dy); }

    static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay, len = vx * vx + vy * vy;
        float t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (py - ay) * vy) / len));
        return dist(px, py, ax + t * vx, ay + t * vy);
    }
}
