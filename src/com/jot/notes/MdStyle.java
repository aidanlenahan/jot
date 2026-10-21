package com.jot.notes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.view.View;

import java.util.List;

/** Turns markdown into Android spans: live (markers stay visible, dimmed) and rendered (markers removed). */
public final class MdStyle {
    private MdStyle() {}

    static final float[] H_SIZES = {1f, 1.55f, 1.35f, 1.2f, 1.1f, 1.05f, 1f};

    /** Marker interface so we only ever remove our own live spans. */
    interface Live {}
    static final class LBold extends StyleSpan implements Live { LBold() { super(Typeface.BOLD); } }
    static final class LItalic extends StyleSpan implements Live { LItalic() { super(Typeface.ITALIC); } }
    static final class LStrike extends StrikethroughSpan implements Live {}
    static final class LUnder extends UnderlineSpan implements Live {}
    static final class LFg extends ForegroundColorSpan implements Live { LFg(int c) { super(c); } }
    static final class LBg extends BackgroundColorSpan implements Live { LBg(int c) { super(c); } }
    static final class LSize extends RelativeSizeSpan implements Live { LSize(float f) { super(f); } }
    static final class LMono extends TypefaceSpan implements Live { LMono() { super("monospace"); } }

    // =====================================================================
    // Live styling inside the editor
    // =====================================================================

    public static void live(Editable e, Ui.Pal p) {
        for (Object o : e.getSpans(0, e.length(), Live.class)) e.removeSpan(o);
        String s = e.toString();
        for (Md.Line l : Md.parseLines(s)) {
            switch (l.type) {
                case Md.H:
                    set(e, new LSize(H_SIZES[l.level]), l.start, l.end);
                    set(e, new LBold(), l.contentStart, l.end);
                    set(e, new LFg(p.faint), l.start, l.contentStart);
                    liveInline(e, s, l.contentStart, l.end, p);
                    break;
                case Md.BULLET:
                case Md.NUMBER:
                    set(e, new LFg(p.accent), l.prefixStart, l.contentStart);
                    set(e, new LBold(), l.prefixStart, l.contentStart);
                    liveInline(e, s, l.contentStart, l.end, p);
                    break;
                case Md.TASK:
                    set(e, new LFg(p.faint), l.prefixStart, l.checkOffset - 1);
                    set(e, new CheckboxSpan(l.checked, p), l.checkOffset - 1, l.checkOffset + 2);
                    if (l.checked) {
                        set(e, new LStrike(), l.contentStart, l.end);
                        set(e, new LFg(p.sub), l.contentStart, l.end);
                    }
                    liveInline(e, s, l.contentStart, l.end, p);
                    break;
                case Md.QUOTE:
                    set(e, new LFg(p.accent), l.start, l.contentStart);
                    set(e, new LFg(p.sub), l.contentStart, l.end);
                    set(e, new LItalic(), l.contentStart, l.end);
                    liveInline(e, s, l.contentStart, l.end, p);
                    break;
                case Md.CODEBLOCK:
                    set(e, new LMono(), l.start, l.end);
                    set(e, new LBg(p.codeBg), l.start, l.end);
                    break;
                case Md.FENCE:
                    set(e, new LMono(), l.start, l.end);
                    set(e, new LFg(p.faint), l.start, l.end);
                    break;
                case Md.HR:
                    set(e, new LFg(p.faint), l.start, l.end);
                    break;
                case Md.P:
                    liveInline(e, s, l.start, l.end, p);
                    break;
                default: break;
            }
        }
    }

    private static void liveInline(Editable e, String s, int from, int to, Ui.Pal p) {
        if (to <= from) return;
        Md.Inline in = Md.parseInline(s, from, to);
        for (Md.Span sp : in.spans) {
            if (sp.end <= sp.start) continue;
            switch (sp.style) {
                case Md.BOLD: set(e, new LBold(), sp.start, sp.end); break;
                case Md.ITALIC: set(e, new LItalic(), sp.start, sp.end); break;
                case Md.STRIKE: set(e, new LStrike(), sp.start, sp.end); break;
                case Md.UNDERLINE: set(e, new LUnder(), sp.start, sp.end); break;
                case Md.HIGHLIGHT: set(e, new LBg(p.highlight), sp.start, sp.end); break;
                case Md.CODE: set(e, new LMono(), sp.start, sp.end); set(e, new LBg(p.codeBg), sp.start, sp.end); break;
                case Md.LINK: set(e, new LFg(p.accent), sp.start, sp.end); set(e, new LUnder(), sp.start, sp.end); break;
            }
        }
        for (int[] m : in.markers) set(e, new LFg(p.faint), m[0], m[1]);
    }

    private static void set(Spannable e, Object span, int a, int b) {
        if (b > a && a >= 0 && b <= e.length()) e.setSpan(span, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // =====================================================================
    // Rendered (read mode, PDF)
    // =====================================================================

    public interface OnCheck { void toggle(int checkOffset); }

    /**
     * @param unit pixels per dp (screen) or 1 for PDF points
     * @param cb   called when a task is tapped (null = not clickable)
     */
    public static SpannableStringBuilder render(String text, Ui.Pal p, float unit, OnCheck cb) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        List<Md.Block> blocks = Md.toBlocks(text);
        int indent = Math.round(20 * unit);
        for (int i = 0; i < blocks.size(); i++) {
            Md.Block b = blocks.get(i);
            int st = sb.length();
            if (b.type == Md.HR) {
                sb.append(' ');
            } else {
                appendRuns(sb, b.runs, p);
                if (b.type == Md.TASK && b.runs.isEmpty()) sb.append(' ');
            }
            int en = sb.length();
            if (i < blocks.size() - 1) sb.append('\n');
            int pe = sb.length();
            switch (b.type) {
                case Md.H:
                    set(sb, new RelativeSizeSpan(H_SIZES[b.level]), st, en);
                    set(sb, new StyleSpan(Typeface.BOLD), st, en);
                    break;
                case Md.BULLET:
                    set(sb, new ListMargin(b.level * indent, indent, "•", false, false, p), st, pe);
                    break;
                case Md.NUMBER:
                    set(sb, new ListMargin(b.level * indent, Math.round(indent * 1.3f), b.number + ".", false, false, p), st, pe);
                    break;
                case Md.TASK:
                    set(sb, new ListMargin(b.level * indent, Math.round(indent * 1.4f), null, true, b.checked, p), st, pe);
                    if (b.checked) {
                        set(sb, new StrikethroughSpan(), st, en);
                        set(sb, new ForegroundColorSpan(p.sub), st, en);
                    }
                    if (cb != null) {
                        final int off = b.checkOffset;
                        set(sb, new ClickableSpan() {
                            @Override public void onClick(View v) { cb.toggle(off); }
                            @Override public void updateDrawState(TextPaint ds) {}
                        }, st, en);
                    }
                    break;
                case Md.QUOTE:
                    set(sb, new BarMargin(p.accent, unit), st, pe);
                    set(sb, new ForegroundColorSpan(p.sub), st, en);
                    set(sb, new StyleSpan(Typeface.ITALIC), st, en);
                    break;
                case Md.CODEBLOCK:
                    set(sb, new TypefaceSpan("monospace"), st, en);
                    set(sb, new BackgroundColorSpan(p.codeBg), st, en);
                    break;
                case Md.HR:
                    set(sb, new HrLine(p.border, unit), st, pe);
                    break;
                default: break;
            }
        }
        return sb;
    }

    private static void appendRuns(SpannableStringBuilder sb, List<Md.Run> runs, Ui.Pal p) {
        for (Md.Run r : runs) {
            int a = sb.length();
            sb.append(r.text);
            int b = sb.length();
            int st = r.style;
            if ((st & Md.BOLD) != 0 && (st & Md.ITALIC) != 0) set(sb, new StyleSpan(Typeface.BOLD_ITALIC), a, b);
            else if ((st & Md.BOLD) != 0) set(sb, new StyleSpan(Typeface.BOLD), a, b);
            else if ((st & Md.ITALIC) != 0) set(sb, new StyleSpan(Typeface.ITALIC), a, b);
            if ((st & Md.STRIKE) != 0) set(sb, new StrikethroughSpan(), a, b);
            if ((st & Md.UNDERLINE) != 0) set(sb, new UnderlineSpan(), a, b);
            if ((st & Md.HIGHLIGHT) != 0) set(sb, new BackgroundColorSpan(p.highlight), a, b);
            if ((st & Md.CODE) != 0) {
                set(sb, new TypefaceSpan("monospace"), a, b);
                set(sb, new BackgroundColorSpan(p.codeBg), a, b);
            }
            if ((st & Md.LINK) != 0 && r.url != null) {
                set(sb, new URLSpan(r.url), a, b);
                set(sb, new ForegroundColorSpan(p.accent), a, b);
            }
        }
    }

    // =====================================================================
    // Custom spans
    // =====================================================================

    static void drawBox(Canvas c, float left, float top, float size, boolean checked, Ui.Pal p, Paint src) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float sw = Math.max(1.5f, size * 0.1f);
        RectF r = new RectF(left + sw / 2, top + sw / 2, left + size - sw / 2, top + size - sw / 2);
        float rad = size * 0.22f;
        if (checked) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(p.accent);
            c.drawRoundRect(r, rad, rad, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(sw * 1.2f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(p.onAccent);
            Path ck = new Path();
            ck.moveTo(left + size * 0.25f, top + size * 0.52f);
            ck.lineTo(left + size * 0.43f, top + size * 0.7f);
            ck.lineTo(left + size * 0.76f, top + size * 0.32f);
            c.drawPath(ck, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(sw);
            paint.setColor(p.sub);
            c.drawRoundRect(r, rad, rad, paint);
        }
    }

    /** Draws "[ ]" / "[x]" as a checkbox inside the editor. */
    static final class CheckboxSpan extends ReplacementSpan implements Live {
        final boolean checked; final Ui.Pal p;
        CheckboxSpan(boolean checked, Ui.Pal p) { this.checked = checked; this.p = p; }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(paint.getTextSize() * 1.25f);
        }

        @Override public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            float size = paint.getTextSize() * 0.95f;
            Paint.FontMetrics fm = paint.getFontMetrics();
            float mid = y + (fm.ascent + fm.descent) / 2f;
            drawBox(c, x + paint.getTextSize() * 0.05f, mid - size / 2f, size, checked, p, paint);
        }
    }

    /** Hanging-indent margin that draws a bullet, number or checkbox on the first line. */
    static final class ListMargin implements LeadingMarginSpan {
        final int indent, gutter; final String glyph; final boolean box, checked; final Ui.Pal p;
        ListMargin(int indent, int gutter, String glyph, boolean box, boolean checked, Ui.Pal p) {
            this.indent = indent; this.gutter = gutter; this.glyph = glyph; this.box = box; this.checked = checked; this.p = p;
        }
        @Override public int getLeadingMargin(boolean first) { return indent + gutter; }

        @Override public void drawLeadingMargin(Canvas c, Paint paint, int x, int dir, int top, int baseline, int bottom,
                                                CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!first || !(text instanceof Spanned) || ((Spanned) text).getSpanStart(this) != start) return;
            float left = dir > 0 ? x + indent : x - indent - gutter;
            if (box) {
                float size = paint.getTextSize() * 0.95f;
                Paint.FontMetrics fm = paint.getFontMetrics();
                float mid = baseline + (fm.ascent + fm.descent) / 2f;
                drawBox(c, left, mid - size / 2f, size, checked, p, paint);
            } else {
                int oc = paint.getColor();
                Paint.Style os = paint.getStyle();
                boolean ob = paint.isFakeBoldText();
                paint.setColor(p.accent);
                paint.setStyle(Paint.Style.FILL);
                paint.setFakeBoldText(true);
                c.drawText(glyph, left + (glyph.length() == 1 ? paint.getTextSize() * 0.15f : 0), baseline, paint);
                paint.setColor(oc); paint.setStyle(os); paint.setFakeBoldText(ob);
            }
        }
    }

    /** Vertical bar for block quotes. */
    static final class BarMargin implements LeadingMarginSpan {
        final int color; final float unit;
        BarMargin(int color, float unit) { this.color = color; this.unit = unit; }
        @Override public int getLeadingMargin(boolean first) { return Math.round(16 * unit); }
        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                                CharSequence text, int start, int end, boolean first, Layout layout) {
            int oc = p.getColor(); Paint.Style os = p.getStyle();
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            float w = 3 * unit;
            float l = dir > 0 ? x + 2 * unit : x - 2 * unit - w;
            c.drawRect(l, top, l + w, bottom, p);
            p.setColor(oc); p.setStyle(os);
        }
    }

    /** Horizontal rule. */
    static final class HrLine implements LineBackgroundSpan {
        final int color; final float unit;
        HrLine(int color, float unit) { this.color = color; this.unit = unit; }
        @Override public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline, int bottom,
                                             CharSequence text, int start, int end, int lnum) {
            int oc = p.getColor(); Paint.Style os = p.getStyle();
            p.setColor(color); p.setStyle(Paint.Style.FILL);
            float mid = (top + bottom) / 2f;
            c.drawRect(left, mid - unit * 0.75f, right, mid + unit * 0.75f, p);
            p.setColor(oc); p.setStyle(os);
        }
    }
}
