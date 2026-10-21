package com.jot.notes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small markdown engine (pure Java, no Android deps) shared by the live editor,
 * read mode and every exporter.
 *
 * Supported: # headings, - / * bullets, 1. numbers, - [ ] tasks, > quotes, ``` code,
 * --- rules, **bold**, *italic* / _italic_, ~~strike~~, <u>underline</u>,
 * ==highlight==, `code`, [links](url), \escapes.
 */
public final class Md {
    private Md() {}

    // ---- inline styles (bit flags) ----
    public static final int BOLD = 1, ITALIC = 2, STRIKE = 4, UNDERLINE = 8, HIGHLIGHT = 16, CODE = 32, LINK = 64;

    // ---- line / block types ----
    public static final int P = 0, H = 1, BULLET = 2, NUMBER = 3, TASK = 4, QUOTE = 5, CODEBLOCK = 6, FENCE = 7, HR = 8, BLANK = 9;

    /** A styled range in the source string (content only, markers excluded). */
    public static final class Span {
        public final int start, end, style;
        public final String url;
        Span(int start, int end, int style, String url) { this.start = start; this.end = end; this.style = style; this.url = url; }
    }

    public static final class Inline {
        public final List<Span> spans = new ArrayList<>();
        /** [start,end) ranges of syntax characters that disappear when rendered. */
        public final List<int[]> markers = new ArrayList<>();
    }

    /** One source line, classified. Offsets are absolute in the parsed text. */
    public static final class Line {
        public int type = P;
        public int start, end;          // line range (without '\n')
        public int prefixStart, contentStart; // syntax prefix [prefixStart, contentStart)
        public int level;               // heading level, or indent level for lists
        public boolean checked;
        public int checkOffset = -1;    // index of ' ' / 'x' inside "[ ]"
        public String number;           // for NUMBER lines
    }

    /** Rendered run (markers removed) used by exporters. */
    public static final class Run {
        public final String text; public final int style; public final String url;
        Run(String text, int style, String url) { this.text = text; this.style = style; this.url = url; }
    }

    /** Rendered block (one per visible line) used by exporters & read mode. */
    public static final class Block {
        public int type, level;
        public boolean checked;
        public int checkOffset = -1;
        public String number;
        public final List<Run> runs = new ArrayList<>();
        public String plain() { StringBuilder b = new StringBuilder(); for (Run r : runs) b.append(r.text); return b.toString(); }
    }

    private static final Pattern RX_H = Pattern.compile("^(#{1,6})[ \\t]+");
    private static final Pattern RX_TASK = Pattern.compile("^([ \\t]*)[-*+][ \\t]+\\[([ xX])\\](?:[ \\t]+|$)");
    private static final Pattern RX_BULLET = Pattern.compile("^([ \\t]*)[-*+][ \\t]+");
    private static final Pattern RX_NUMBER = Pattern.compile("^([ \\t]*)(\\d{1,9})[.)][ \\t]+");
    private static final Pattern RX_QUOTE = Pattern.compile("^>[ \\t]?");
    private static final Pattern RX_HR = Pattern.compile("^[ ]{0,3}([-*_])([ \\t]*\\1){2,}[ \\t]*$");
    private static final Pattern RX_FENCE = Pattern.compile("^[ \\t]*```");

    // =====================================================================
    // Lines
    // =====================================================================

    public static List<Line> parseLines(String text) {
        List<Line> out = new ArrayList<>();
        boolean inFence = false;
        int i = 0, n = text.length();
        while (true) {
            int nl = text.indexOf('\n', i);
            int end = nl < 0 ? n : nl;
            Line l = classify(text, i, end, inFence);
            if (l.type == FENCE) inFence = !inFence;
            out.add(l);
            if (nl < 0) break;
            i = nl + 1;
        }
        return out;
    }

    static Line classify(String text, int start, int end, boolean inFence) {
        Line l = new Line();
        l.start = start; l.end = end; l.prefixStart = start; l.contentStart = start;
        String s = text.substring(start, end);
        if (RX_FENCE.matcher(s).find()) { l.type = FENCE; l.contentStart = end; return l; }
        if (inFence) { l.type = CODEBLOCK; return l; }
        if (s.trim().isEmpty()) { l.type = BLANK; return l; }
        Matcher m;
        if ((m = RX_H.matcher(s)).find()) {
            l.type = H; l.level = m.group(1).length(); l.contentStart = start + m.end(); return l;
        }
        if (RX_HR.matcher(s).find()) { l.type = HR; l.contentStart = end; return l; }
        if ((m = RX_TASK.matcher(s)).find()) {
            l.type = TASK; l.level = indent(m.group(1));
            l.checked = !" ".equals(m.group(2));
            l.checkOffset = start + m.start(2);
            l.prefixStart = start + m.group(1).length();
            l.contentStart = start + m.end(); return l;
        }
        if ((m = RX_BULLET.matcher(s)).find()) {
            l.type = BULLET; l.level = indent(m.group(1));
            l.prefixStart = start + m.group(1).length();
            l.contentStart = start + m.end(); return l;
        }
        if ((m = RX_NUMBER.matcher(s)).find()) {
            l.type = NUMBER; l.level = indent(m.group(1)); l.number = m.group(2);
            l.prefixStart = start + m.group(1).length();
            l.contentStart = start + m.end(); return l;
        }
        if ((m = RX_QUOTE.matcher(s)).find()) { l.type = QUOTE; l.contentStart = start + m.end(); return l; }
        return l;
    }

    private static int indent(String ws) {
        int cols = 0;
        for (int i = 0; i < ws.length(); i++) cols += ws.charAt(i) == '\t' ? 4 : 1;
        return Math.min(cols / 2, 6);
    }

    // =====================================================================
    // Inline
    // =====================================================================

    public static Inline parseInline(String s, int from, int to) {
        Inline out = new Inline();
        inline(s, from, to, out);
        return out;
    }

    private static boolean isPunct(char c) { return "\\`*_{}[]()#+-.!~=<>|".indexOf(c) >= 0; }
    private static boolean isWordChar(char c) { return Character.isLetterOrDigit(c); }

    private static void inline(String s, int from, int to, Inline out) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < to && isPunct(s.charAt(i + 1))) {
                out.markers.add(new int[]{i, i + 1});
                i += 2; continue;
            }
            if (c == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i + 1 && j < to) {
                    out.markers.add(new int[]{i, i + 1});
                    out.markers.add(new int[]{j, j + 1});
                    out.spans.add(new Span(i + 1, j, CODE, null));
                    i = j + 1; continue;
                }
            }
            if (c == '<' && s.startsWith("<u>", i)) {
                int j = s.indexOf("</u>", i + 3);
                if (j > i + 3 && j + 4 <= to) {
                    wrap(s, i, 3, j, 4, UNDERLINE, null, out);
                    i = j + 4; continue;
                }
            }
            if (c == '[') {
                int j = findLinkEnd(s, i, to);
                if (j > 0) {
                    int close = s.indexOf("](", i + 1);
                    String url = s.substring(close + 2, j);
                    out.markers.add(new int[]{i, i + 1});
                    out.markers.add(new int[]{close, j + 1});
                    out.spans.add(new Span(i + 1, close, LINK, url));
                    inline(s, i + 1, close, out);
                    i = j + 1; continue;
                }
            }
            if (c == '~' || c == '=') {
                if (i + 1 < to && s.charAt(i + 1) == c && i + 2 < to && !Character.isWhitespace(s.charAt(i + 2))) {
                    String d = c == '~' ? "~~" : "==";
                    int j = s.indexOf(d, i + 3);
                    while (j > 0 && j + 2 <= to && Character.isWhitespace(s.charAt(j - 1))) j = s.indexOf(d, j + 1);
                    if (j > 0 && j + 2 <= to) {
                        wrap(s, i, 2, j, 2, c == '~' ? STRIKE : HIGHLIGHT, null, out);
                        i = j + 2; continue;
                    }
                }
            }
            if (c == '*' || c == '_') {
                int run = runLen(s, i, to, c);
                boolean leftOk = c == '*' || i == 0 || !isWordChar(s.charAt(i - 1));
                int[] lens = run >= 3 ? new int[]{2, 1} : run == 2 ? new int[]{2} : new int[]{1};
                boolean matched = false;
                if (leftOk && i + run < to && !Character.isWhitespace(s.charAt(i + run))) {
                    for (int L : lens) {
                        int j = findClose(s, c, L, i + L + 1, to);
                        if (j > 0) {
                            wrap(s, i, L, j, L, L == 2 ? BOLD : ITALIC, null, out);
                            i = j + L; matched = true; break;
                        }
                    }
                }
                if (matched) continue;
                i += run; continue;
            }
            i++;
        }
    }

    private static void wrap(String s, int open, int ol, int close, int cl, int style, String url, Inline out) {
        out.markers.add(new int[]{open, open + ol});
        out.markers.add(new int[]{close, close + cl});
        out.spans.add(new Span(open + ol, close, style, url));
        inline(s, open + ol, close, out);
    }

    private static int runLen(String s, int i, int to, char c) {
        int j = i;
        while (j < to && s.charAt(j) == c) j++;
        return j - i;
    }

    /** Finds a closing delimiter of length L made of char c, searching from 'from'. */
    private static int findClose(String s, char c, int L, int from, int to) {
        int j = from;
        while (j < to) {
            if (s.charAt(j) == '`') { // skip code spans
                int k = s.indexOf('`', j + 1);
                if (k > 0 && k < to) { j = k + 1; continue; }
            }
            if (s.charAt(j) == c) {
                int r = runLen(s, j, to, c);
                boolean prevOk = !Character.isWhitespace(s.charAt(j - 1));
                int pos = -1;
                if (r == L) pos = j;
                else if (r == 3 && L <= 2) pos = j + r - L;
                if (pos >= 0 && prevOk) {
                    boolean rightOk = c == '*' || pos + L >= s.length() || !isWordChar(s.charAt(pos + L));
                    if (rightOk) return pos;
                }
                j += r; continue;
            }
            j++;
        }
        return -1;
    }

    private static int findLinkEnd(String s, int i, int to) {
        int close = s.indexOf("](", i + 1);
        if (close < 0 || close >= to || close == i + 1) return -1;
        int nl = s.indexOf('\n', i);
        if (nl >= 0 && nl < close) return -1;
        int end = s.indexOf(')', close + 2);
        if (end < 0 || end >= to || end == close + 2) return -1;
        for (int k = close + 2; k < end; k++) if (s.charAt(k) == ' ') return -1;
        return end;
    }

    /** Resolves an inline parse of [from,to) into runs with markers removed. */
    public static List<Run> runs(String s, int from, int to) {
        List<Run> out = new ArrayList<>();
        if (to <= from) return out;
        Inline in = parseInline(s, from, to);
        int n = to - from;
        int[] style = new int[n];
        String[] url = new String[n];
        boolean[] hidden = new boolean[n];
        for (int[] m : in.markers) for (int k = Math.max(m[0], from); k < Math.min(m[1], to); k++) hidden[k - from] = true;
        for (Span sp : in.spans) for (int k = Math.max(sp.start, from); k < Math.min(sp.end, to); k++) {
            style[k - from] |= sp.style;
            if (sp.url != null) url[k - from] = sp.url;
        }
        StringBuilder cur = new StringBuilder();
        int curStyle = -1; String curUrl = null;
        for (int k = 0; k < n; k++) {
            if (hidden[k]) continue;
            if (style[k] != curStyle || !eq(url[k], curUrl)) {
                if (cur.length() > 0) out.add(new Run(cur.toString(), curStyle, curUrl));
                cur.setLength(0); curStyle = style[k]; curUrl = url[k];
            }
            cur.append(s.charAt(from + k));
        }
        if (cur.length() > 0) out.add(new Run(cur.toString(), curStyle, curUrl));
        return out;
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    // =====================================================================
    // Blocks (rendered model)
    // =====================================================================

    public static List<Block> toBlocks(String text) {
        List<Block> out = new ArrayList<>();
        for (Line l : parseLines(text)) {
            if (l.type == FENCE) continue;
            Block b = new Block();
            b.type = l.type; b.level = l.level; b.checked = l.checked; b.checkOffset = l.checkOffset; b.number = l.number;
            if (l.type == CODEBLOCK) {
                String raw = text.substring(l.start, l.end);
                if (!raw.isEmpty()) b.runs.add(new Run(raw, CODE, null));
            } else if (l.type != HR && l.type != BLANK) {
                b.runs.addAll(runs(text, l.contentStart, l.end));
            }
            out.add(b);
        }
        // drop trailing blank lines
        while (!out.isEmpty() && out.get(out.size() - 1).type == BLANK) out.remove(out.size() - 1);
        return out;
    }

    /** Plain-text rendering (used for .txt export and list previews). */
    public static String toPlain(String text) {
        StringBuilder sb = new StringBuilder();
        List<Block> blocks = toBlocks(text);
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            String pad = repeat("  ", b.level);
            switch (b.type) {
                case BULLET: sb.append(pad).append("• "); break;
                case NUMBER: sb.append(pad).append(b.number).append(". "); break;
                case TASK: sb.append(pad).append(b.checked ? "[x] " : "[ ] "); break;
                case QUOTE: sb.append("  | "); break;
                case CODEBLOCK: sb.append("    "); break;
                case HR: sb.append("----------"); break;
                default: break;
            }
            sb.append(b.plain());
            if (i < blocks.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    public static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
