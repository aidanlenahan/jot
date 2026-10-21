package com.jot.notes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Note exports: .md / .txt (text only), .pdf / .docx (text + sketches), .svg (one sketch). */
public final class Exporter {
    private Exporter() {}

    public static final int MD = 0, TXT = 1, PDF = 2, DOCX = 3;
    public static final String[] EXT = {"md", "txt", "pdf", "docx"};
    public static final String[] MIME = {"text/markdown", "text/plain", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"};

    public static String fileName(Note n, int fmt) {
        String t = n.title.trim().isEmpty() ? "Untitled note" : n.title.trim();
        t = t.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").trim();
        if (t.length() > 60) t = t.substring(0, 60).trim();
        return t + "." + EXT[fmt];
    }

    public static void write(android.content.Context ctx, Note n, int fmt, OutputStream os) throws Exception {
        switch (fmt) {
            case MD: os.write(markdown(n).getBytes(Store.UTF8)); break;
            case TXT: os.write(text(n).getBytes(Store.UTF8)); break;
            case PDF: pdf(ctx, n, os); break;
            case DOCX: docx(ctx, n, os); break;
        }
    }

    public static String markdown(Note n) {
        StringBuilder sb = new StringBuilder();
        if (!n.title.trim().isEmpty()) sb.append("# ").append(n.title.trim()).append("\n\n");
        sb.append(n.markdownBody());
        return sb.append('\n').toString();
    }

    public static String text(Note n) {
        StringBuilder sb = new StringBuilder();
        if (!n.title.trim().isEmpty()) sb.append(n.title.trim()).append("\n\n");
        sb.append(Md.toPlain(n.markdownBody()));
        return sb.append('\n').toString();
    }

    // ------------------------------------------------------------------ sketches

    /** Height in units actually used by the drawing (trims empty space at the bottom). */
    static float usedHeight(Note.NBlock b) {
        return Math.min(b.height, Math.max(80, b.contentBottom() + 30));
    }

    public static Bitmap sketchBitmap(Note.NBlock b, int widthPx) {
        float s = widthPx / Note.UNITS;
        int h = Math.max(1, Math.round(usedHeight(b) * s));
        Bitmap bm = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(0xFFFFFFFF);
        c.scale(s, s);
        SketchView.drawStrokes(c, b.strokes, 0xFF000000, new Paint(Paint.ANTI_ALIAS_FLAG));
        return bm;
    }

    public static String svg(Note.NBlock b, boolean darkInk) {
        float h = usedHeight(b);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1000 ")
          .append(fmt(h)).append("\" width=\"1000\" height=\"").append(fmt(h)).append("\">\n");
        for (Note.Stroke st : b.strokes) {
            int color = st.color == 0 ? 0xFF000000 : st.color;
            String hex = String.format(Locale.US, "#%06X", color & 0xFFFFFF);
            float[] p = st.pts;
            if (p.length == 2) {
                sb.append("<circle cx=\"").append(fmt(p[0])).append("\" cy=\"").append(fmt(p[1])).append("\" r=\"")
                  .append(fmt(st.width / 2)).append("\" fill=\"").append(hex).append("\"")
                  .append(st.highlighter ? " fill-opacity=\"0.4\"" : "").append("/>\n");
                continue;
            }
            if (st.wm != null && st.wm.length == p.length / 2) {
                sb.append("<g fill=\"none\" stroke=\"").append(hex).append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\">\n");
                int m = p.length / 2;
                float sx = p[0], sy = p[1];
                for (int i = 1; i < m; i++) {
                    float ex, ey;
                    String d;
                    if (i < m - 1) {
                        ex = (p[2 * i] + p[2 * i + 2]) / 2; ey = (p[2 * i + 1] + p[2 * i + 3]) / 2;
                        d = "M" + fmt(sx) + " " + fmt(sy) + " Q" + fmt(p[2 * i]) + " " + fmt(p[2 * i + 1]) + " " + fmt(ex) + " " + fmt(ey);
                    } else {
                        ex = p[2 * i]; ey = p[2 * i + 1];
                        d = "M" + fmt(sx) + " " + fmt(sy) + " L" + fmt(ex) + " " + fmt(ey);
                    }
                    sb.append("<path stroke-width=\"").append(fmt(Math.max(0.6f, st.width * (st.wm[i - 1] + st.wm[i]) / 2))).append("\" d=\"").append(d).append("\"/>\n");
                    sx = ex; sy = ey;
                }
                sb.append("</g>\n");
                continue;
            }
            sb.append("<path fill=\"none\" stroke=\"").append(hex).append("\" stroke-width=\"").append(fmt(st.width))
              .append("\" stroke-linecap=\"").append(st.highlighter ? "square" : "round").append("\" stroke-linejoin=\"round\"")
              .append(st.highlighter ? " stroke-opacity=\"0.4\"" : "").append(" d=\"M").append(fmt(p[0])).append(' ').append(fmt(p[1]));
            int n = p.length;
            for (int i = 2; i < n - 2; i += 2) {
                sb.append(" Q").append(fmt(p[i])).append(' ').append(fmt(p[i + 1])).append(' ')
                  .append(fmt((p[i] + p[i + 2]) / 2)).append(' ').append(fmt((p[i + 1] + p[i + 3]) / 2));
            }
            sb.append(" L").append(fmt(p[n - 2])).append(' ').append(fmt(p[n - 1])).append("\"/>\n");
        }
        return sb.append("</svg>\n").toString();
    }

    private static String fmt(float f) {
        float r = Math.round(f * 10f) / 10f;
        return r == (int) r ? Integer.toString((int) r) : Float.toString(r);
    }

    // ------------------------------------------------------------------ docx

    static void docx(android.content.Context ctx, Note n, OutputStream os) throws Exception {
        List<Object> parts = new ArrayList<>();
        for (Note.NBlock b : n.blocks) {
            if (b.kind == Note.TEXT) {
                if (!b.text.trim().isEmpty()) parts.add(b.text);
            } else if (b.kind == Note.IMAGE) {
                java.io.File f = Store.imageFile(ctx, b.img);
                if (f.exists()) parts.add(new DocxWriter.Image(Store.readBytes(f), Math.max(1, b.imgW), Math.max(1, b.imgH), "jpg"));
            } else if (!b.strokes.isEmpty()) {
                Bitmap bm = sketchBitmap(b, 1200);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
                parts.add(new DocxWriter.Image(bo.toByteArray(), bm.getWidth(), bm.getHeight()));
                bm.recycle();
            }
        }
        DocxWriter.write(n.title.trim(), parts, os);
    }

    // ------------------------------------------------------------------ pdf

    static void pdf(android.content.Context ctx, Note n, OutputStream os) throws Exception {
        final int pw = 595, ph = 842, m = 54;
        final int cw = pw - 2 * m;
        Ui.Pal p = Ui.print();
        PdfState st = new PdfState(new PdfDocument(), pw, ph, m);
        st.newPage();

        TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setTextSize(11.5f);
        body.setColor(0xFF000000);
        body.linkColor = p.accent;

        if (!n.title.trim().isEmpty()) {
            TextPaint tp = new TextPaint(body);
            tp.setTextSize(22f);
            tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            drawLayout(st, layout(n.title.trim(), tp, cw));
            st.y += 12;
        }
        for (Note.NBlock b : n.blocks) {
            if (b.kind == Note.TEXT) {
                if (b.text.trim().isEmpty()) continue;
                SpannableStringBuilder sp = MdStyle.render(b.text, p, 1f, null);
                drawLayout(st, layout(sp, body, cw));
                st.y += 10;
            } else if (b.kind == Note.IMAGE) {
                Bitmap bm = Images.load(ctx, b.img, 1400);
                if (bm == null) continue;
                float scale = cw / (float) bm.getWidth();
                float maxH = ph - 2 * m;
                if (bm.getHeight() * scale > maxH) scale = maxH / bm.getHeight();
                float h = bm.getHeight() * scale, w = bm.getWidth() * scale;
                if (st.y + h > ph - m) st.newPage();
                Canvas c = st.page.getCanvas();
                android.graphics.RectF dst = new android.graphics.RectF(m, st.y, m + w, st.y + h);
                c.drawBitmap(bm, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                st.y += h + 12;
            } else if (!b.strokes.isEmpty()) {
                float hu = usedHeight(b);
                float scale = cw / Note.UNITS;
                float maxH = ph - 2 * m;
                if (hu * scale > maxH) scale = maxH / hu;
                float h = hu * scale;
                if (st.y + h > ph - m) st.newPage();
                Canvas c = st.page.getCanvas();
                c.save();
                c.translate(m, st.y);
                c.clipRect(0, 0, Note.UNITS * scale, h);
                c.scale(scale, scale);
                SketchView.drawStrokes(c, b.strokes, 0xFF000000, new Paint(Paint.ANTI_ALIAS_FLAG));
                c.restore();
                st.y += h + 12;
            }
        }
        st.doc.finishPage(st.page);
        st.doc.writeTo(os);
        st.doc.close();
    }

    private static final class PdfState {
        final PdfDocument doc; final int pw, ph, m;
        PdfDocument.Page page; float y; int num;
        PdfState(PdfDocument d, int pw, int ph, int m) { doc = d; this.pw = pw; this.ph = ph; this.m = m; }
        void newPage() {
            if (page != null) doc.finishPage(page);
            page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, ++num).create());
            y = m;
        }
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout layout(CharSequence text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.2f)
                .setIncludePad(true)
                .build();
    }

    /** Draws a StaticLayout across as many pages as needed, splitting on line boundaries. */
    private static void drawLayout(PdfState st, StaticLayout l) {
        int count = l.getLineCount();
        int line = 0;
        float bottomLimit = st.ph - st.m;
        while (line < count) {
            int top = l.getLineTop(line);
            int last = line;
            while (last < count && st.y + (l.getLineBottom(last) - top) <= bottomLimit) last++;
            if (last == line) {
                if (st.y <= st.m + 0.5f) last = line + 1; // a single line taller than a page: draw anyway
                else { st.newPage(); continue; }
            }
            int bottom = l.getLineBottom(last - 1);
            Canvas c = st.page.getCanvas();
            c.save();
            c.translate(st.m, st.y - top);
            c.clipRect(-st.m, top, l.getWidth() + st.m, bottom);
            l.draw(c);
            c.restore();
            st.y += bottom - top;
            line = last;
            if (line < count) st.newPage();
        }
    }
}
