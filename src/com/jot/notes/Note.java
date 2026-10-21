package com.jot.notes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A note is a title plus an ordered list of blocks: markdown text, a sketch (handwriting) or an image. */
public final class Note {
    public String id;
    public String title = "";
    public boolean pinned;
    public long created, updated;
    public int color;            // index into Ui note colours, 0 = none
    public String folder = "";   // "" = no folder
    public long trashed;         // 0 = not in trash, else time it was trashed
    public final List<NBlock> blocks = new ArrayList<>();

    public static final int TEXT = 0, SKETCH = 1, IMAGE = 2;
    /** Sketch backgrounds. */
    public static final int BG_BLANK = 0, BG_LINED = 1, BG_GRID = 2;
    /** Sketch coordinates are stored in units where the block width is 1000. */
    public static final float UNITS = 1000f;

    public static final class Stroke {
        public int color;           // 0 = automatic ink (follows light/dark theme)
        public float width;         // in units
        public boolean highlighter;
        public float[] pts;         // x0,y0,x1,y1...
        public float[] wm;          // optional per-point width multiplier (pressure); null = uniform

        public float maxY() { float m = 0; for (int i = 1; i < pts.length; i += 2) m = Math.max(m, pts[i]); return m; }
        public float maxW() {
            if (wm == null) return width;
            float m = 0; for (float f : wm) m = Math.max(m, f);
            return width * m;
        }
        public Stroke copy() {
            Stroke s = new Stroke();
            s.color = color; s.width = width; s.highlighter = highlighter;
            s.pts = pts.clone(); s.wm = wm == null ? null : wm.clone();
            return s;
        }
    }

    public static final class NBlock {
        public int kind = TEXT;
        public String text = "";
        public int bg = BG_BLANK;
        public float height = 600f;
        public List<Stroke> strokes = new ArrayList<>();
        public String img;            // file name in app storage (IMAGE)
        public int imgW, imgH;

        public static NBlock text(String t) { NBlock b = new NBlock(); b.text = t; return b; }
        public static NBlock sketch(int bg, float h) { NBlock b = new NBlock(); b.kind = SKETCH; b.bg = bg; b.height = h; return b; }
        public static NBlock image(String file, int w, int h) { NBlock b = new NBlock(); b.kind = IMAGE; b.img = file; b.imgW = w; b.imgH = h; return b; }

        public float contentBottom() { float m = 0; for (Stroke s : strokes) m = Math.max(m, s.maxY() + s.maxW()); return m; }
    }

    public static Note create() {
        Note n = new Note();
        n.id = UUID.randomUUID().toString();
        n.created = n.updated = System.currentTimeMillis();
        n.blocks.add(NBlock.text(""));
        return n;
    }

    public boolean isEmpty() {
        if (!title.trim().isEmpty()) return false;
        for (NBlock b : blocks) {
            if (b.kind == TEXT && !b.text.trim().isEmpty()) return false;
            if (b.kind == SKETCH && !b.strokes.isEmpty()) return false;
            if (b.kind == IMAGE) return false;
        }
        return true;
    }

    public boolean hasSketch() {
        for (NBlock b : blocks) if (b.kind == SKETCH && !b.strokes.isEmpty()) return true;
        return false;
    }

    public boolean hasImage() {
        for (NBlock b : blocks) if (b.kind == IMAGE) return true;
        return false;
    }

    /** All markdown text joined (sketches and images skipped). */
    public String markdownBody() {
        StringBuilder sb = new StringBuilder();
        for (NBlock b : blocks) {
            if (b.kind != TEXT) continue;
            String t = trimEnd(b.text);
            if (t.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
        }
        return sb.toString();
    }

    public List<String> images() {
        List<String> out = new ArrayList<>();
        for (NBlock b : blocks) if (b.kind == IMAGE && b.img != null) out.add(b.img);
        return out;
    }

    static String trimEnd(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    // ------------------------------------------------------------------ JSON

    private static String floats(float[] a, float mul) {
        StringBuilder sb = new StringBuilder(a.length * 5);
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(' ');
            float r = Math.round(a[i] * mul) / mul;
            if (r == (int) r) sb.append((int) r); else sb.append(r);
        }
        return sb.toString();
    }

    private static float[] parseFloats(String p) {
        p = p == null ? "" : p.trim();
        if (p.isEmpty()) return new float[0];
        String[] parts = p.split(" ");
        float[] out = new float[parts.length];
        for (int q = 0; q < parts.length; q++) out[q] = Float.parseFloat(parts[q]);
        return out;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id).put("title", title).put("pinned", pinned).put("created", created).put("updated", updated);
        if (color != 0) o.put("color", color);
        if (!folder.isEmpty()) o.put("folder", folder);
        if (trashed != 0) o.put("trashed", trashed);
        JSONArray arr = new JSONArray();
        for (NBlock b : blocks) {
            JSONObject jb = new JSONObject();
            if (b.kind == TEXT) {
                jb.put("type", "text").put("text", b.text);
            } else if (b.kind == IMAGE) {
                jb.put("type", "image").put("img", b.img).put("w", b.imgW).put("h", b.imgH);
            } else {
                jb.put("type", "sketch").put("bg", b.bg).put("h", (double) b.height);
                JSONArray ss = new JSONArray();
                for (Stroke s : b.strokes) {
                    JSONObject js = new JSONObject();
                    js.put("c", s.color).put("w", (double) s.width);
                    if (s.highlighter) js.put("hl", true);
                    js.put("p", floats(s.pts, 10f));
                    if (s.wm != null) js.put("q", floats(s.wm, 100f));
                    ss.put(js);
                }
                jb.put("strokes", ss);
            }
            arr.put(jb);
        }
        o.put("blocks", arr);
        return o;
    }

    public static Note fromJson(JSONObject o) throws JSONException {
        Note n = new Note();
        n.id = o.getString("id");
        n.title = o.optString("title", "");
        n.pinned = o.optBoolean("pinned", false);
        n.created = o.optLong("created", System.currentTimeMillis());
        n.updated = o.optLong("updated", n.created);
        n.color = o.optInt("color", 0);
        n.folder = o.optString("folder", "");
        n.trashed = o.optLong("trashed", 0);
        JSONArray arr = o.optJSONArray("blocks");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject jb = arr.getJSONObject(i);
            String type = jb.optString("type");
            if ("sketch".equals(type)) {
                NBlock b = NBlock.sketch(jb.optInt("bg", BG_BLANK), (float) jb.optDouble("h", 600));
                JSONArray ss = jb.optJSONArray("strokes");
                if (ss != null) for (int k = 0; k < ss.length(); k++) {
                    JSONObject js = ss.getJSONObject(k);
                    Stroke s = new Stroke();
                    s.color = js.optInt("c", 0);
                    s.width = (float) js.optDouble("w", 4);
                    s.highlighter = js.optBoolean("hl", false);
                    float[] p = parseFloats(js.optString("p", ""));
                    s.pts = p.length % 2 == 0 ? p : java.util.Arrays.copyOf(p, p.length - 1);
                    if (js.has("q")) {
                        float[] q = parseFloats(js.optString("q", ""));
                        if (q.length == s.pts.length / 2) s.wm = q;
                    }
                    if (s.pts.length >= 2) b.strokes.add(s);
                }
                n.blocks.add(b);
            } else if ("image".equals(type)) {
                String img = jb.optString("img", "");
                if (!img.isEmpty()) n.blocks.add(NBlock.image(img, jb.optInt("w", 1), jb.optInt("h", 1)));
            } else {
                n.blocks.add(NBlock.text(jb.optString("text", "")));
            }
        }
        if (n.blocks.isEmpty()) n.blocks.add(NBlock.text(""));
        // always keep a text block at the end so there's somewhere to type after a sketch/image
        if (n.blocks.get(n.blocks.size() - 1).kind != TEXT) n.blocks.add(NBlock.text(""));
        return n;
    }
}
