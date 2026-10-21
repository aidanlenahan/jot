package com.jot.notes;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;

/** Theme, palette and hand-drawn icons (no support libraries). */
public final class Ui {
    private Ui() {}

    public static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    public static final class Pal {
        public boolean dark;
        public int bg, card, border, text, sub, faint, accent, onAccent, highlight, codeBg, ripple, danger;
    }

    public static int themeMode(Context c) { return Store.prefs(c).getInt("theme", THEME_SYSTEM); }

    public static boolean isDark(Context c) {
        int m = themeMode(c);
        if (m == THEME_LIGHT) return false;
        if (m == THEME_DARK) return true;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public static Pal pal(Context c) { return pal(isDark(c)); }

    public static Pal pal(boolean dark) {
        Pal p = new Pal();
        p.dark = dark;
        if (!dark) {
            p.bg = 0xFFFAFAF7; p.card = 0xFFFFFFFF; p.border = 0xFFE6E5DF; p.text = 0xFF1D1D1B; p.sub = 0xFF6B6B66;
            p.faint = 0xFFAAA9A3; p.accent = 0xFF2F6F5E; p.onAccent = 0xFFFFFFFF; p.highlight = 0xFFFFE58A;
            p.codeBg = 0xFFF0EFEA; p.ripple = 0x22000000; p.danger = 0xFFB3261E;
        } else {
            p.bg = 0xFF151515; p.card = 0xFF1F1F1E; p.border = 0xFF2E2E2C; p.text = 0xFFECECE8; p.sub = 0xFFA3A39D;
            p.faint = 0xFF6C6C67; p.accent = 0xFF7CC4AE; p.onAccent = 0xFF0E2A22; p.highlight = 0xFF6B5A1E;
            p.codeBg = 0xFF2A2A28; p.ripple = 0x33FFFFFF; p.danger = 0xFFF2B8B5;
        }
        return p;
    }

    /** Print palette (always light) for PDF / DOCX rendering. */
    public static Pal print() {
        Pal p = pal(false);
        p.text = 0xFF000000; p.accent = 0xFF2F6F5E;
        return p;
    }

    public static void applyTheme(Activity a) {
        int m = themeMode(a);
        a.setTheme(m == THEME_LIGHT ? R.style.AppTheme_Light : m == THEME_DARK ? R.style.AppTheme_Dark : R.style.AppTheme);
    }

    public static void styleBars(Activity a, Pal p) {
        Window w = a.getWindow();
        w.setStatusBarColor(p.bg);
        w.setNavigationBarColor(p.bg);
        View d = w.getDecorView();
        int flags = d.getSystemUiVisibility();
        if (p.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (p.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(flags);
        w.getDecorView().setBackgroundColor(p.bg);
    }

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static GradientDrawable round(int fill, float radiusPx, int stroke, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }

    public static Drawable ripple(Pal p, Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(p.ripple), content, mask);
    }

    /** A borderless circular-ripple icon button. */
    public static ImageView iconButton(Context c, Pal p, int icon, int color, String desc, View.OnClickListener l) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new Icon(icon, color, dp(c, 2)));
        int pad = dp(c, 12);
        iv.setPadding(pad, pad, pad, pad);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        iv.setBackground(ripple(p, null, mask));
        iv.setContentDescription(desc);
        iv.setOnClickListener(l);
        return iv;
    }

    // =====================================================================
    // Icons drawn from simple paths on a 24x24 grid
    // =====================================================================

    public static final int I_BACK = 1, I_MORE = 2, I_PLUS = 3, I_PIN = 4, I_PIN_ON = 5, I_SEARCH = 6, I_CLOSE = 7,
            I_CHECK = 8, I_TRASH = 9, I_PEN = 10, I_MARKER = 11, I_ERASER = 12, I_UNDO = 13, I_EYE = 14,
            I_SKETCH = 15, I_EXPORT = 16, I_GROW = 17, I_LINES = 18, I_REDO = 19, I_GRID = 20, I_IMAGE = 21,
            I_LASSO = 22, I_SETTINGS = 23, I_FOLDER = 24, I_SHARE = 25, I_ZOOM = 26, I_UP = 27, I_DOWN = 28,
            I_LIST = 29;

    // ---- note colours (index 0 = none)
    public static final String[] NOTE_COLOR_NAMES = {"None", "Red", "Orange", "Yellow", "Green", "Teal", "Blue", "Purple", "Pink", "Grey"};
    private static final int[] NOTE_LIGHT = {0, 0xFFFBE4E1, 0xFFFDEBD8, 0xFFFBF3CC, 0xFFE3F2DF, 0xFFDDF1EE, 0xFFE0EAF8, 0xFFECE4F7, 0xFFF9E1EC, 0xFFEDEDEA};
    private static final int[] NOTE_DARK = {0, 0xFF3A2523, 0xFF3A2C1E, 0xFF37321C, 0xFF243223, 0xFF1F3330, 0xFF212C3B, 0xFF2D2639, 0xFF382430, 0xFF2B2B29};
    private static final int[] NOTE_SWATCH = {0, 0xFFE57368, 0xFFF0A257, 0xFFE8CB4A, 0xFF7DBD6E, 0xFF5BB5A8, 0xFF6C9AE0, 0xFFA486DB, 0xFFE38AB3, 0xFFA3A39D};

    /** Background for a note card / editor, or the default card colour when the note has none. */
    public static int noteBg(Pal p, int idx, int fallback) {
        if (idx <= 0 || idx >= NOTE_LIGHT.length) return fallback;
        return p.dark ? NOTE_DARK[idx] : NOTE_LIGHT[idx];
    }

    public static int noteSwatch(int idx) { return idx <= 0 || idx >= NOTE_SWATCH.length ? 0 : NOTE_SWATCH[idx]; }

    public static final class Icon extends Drawable {
        final int type; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); final float strokePx;
        int color;

        public Icon(int type, int color, float strokePx) {
            this.type = type; this.color = color; this.strokePx = strokePx;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        public void setColor(int c) { color = c; invalidateSelf(); }

        @Override public int getIntrinsicWidth() { return Math.round(strokePx * 12); }
        @Override public int getIntrinsicHeight() { return Math.round(strokePx * 12); }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            if (s <= 0) return;
            c.save();
            c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
            c.scale(s, s);
            paint.setColor(color);
            paint.setStrokeWidth(strokePx / s);
            paint.setStyle(Paint.Style.STROKE);
            Path p = new Path();
            switch (type) {
                case I_BACK: p.moveTo(15, 5); p.lineTo(8, 12); p.lineTo(15, 19); break;
                case I_MORE:
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(12, 5.5f, 1.9f, paint); c.drawCircle(12, 12, 1.9f, paint); c.drawCircle(12, 18.5f, 1.9f, paint);
                    break;
                case I_PLUS: p.moveTo(12, 5); p.lineTo(12, 19); p.moveTo(5, 12); p.lineTo(19, 12); break;
                case I_CLOSE: p.moveTo(6.5f, 6.5f); p.lineTo(17.5f, 17.5f); p.moveTo(17.5f, 6.5f); p.lineTo(6.5f, 17.5f); break;
                case I_CHECK: p.moveTo(5, 12.5f); p.lineTo(10, 17.5f); p.lineTo(19, 7); break;
                case I_SEARCH: p.addCircle(10.5f, 10.5f, 6, Path.Direction.CW); p.moveTo(15, 15); p.lineTo(20, 20); break;
                case I_PIN:
                case I_PIN_ON:
                    p.moveTo(9, 4); p.lineTo(15, 4); p.lineTo(14, 10); p.lineTo(17, 13); p.lineTo(17, 14.5f);
                    p.lineTo(7, 14.5f); p.lineTo(7, 13); p.lineTo(10, 10); p.close();
                    if (type == I_PIN_ON) {
                        paint.setStyle(Paint.Style.FILL_AND_STROKE);
                        c.drawPath(p, paint);
                        paint.setStyle(Paint.Style.STROKE);
                        p.reset();
                    }
                    p.moveTo(12, 14.5f); p.lineTo(12, 20.5f);
                    break;
                case I_TRASH:
                    p.moveTo(4.5f, 7); p.lineTo(19.5f, 7);
                    p.moveTo(9.5f, 7); p.lineTo(9.5f, 4.5f); p.lineTo(14.5f, 4.5f); p.lineTo(14.5f, 7);
                    p.moveTo(6.5f, 7); p.lineTo(7.5f, 20); p.lineTo(16.5f, 20); p.lineTo(17.5f, 7);
                    break;
                case I_PEN:
                    p.moveTo(5, 19); p.lineTo(6, 15); p.lineTo(16, 5); p.lineTo(19, 8); p.lineTo(9, 18); p.close();
                    p.moveTo(14, 7); p.lineTo(17, 10);
                    break;
                case I_MARKER:
                    p.moveTo(9, 13.5f); p.lineTo(15, 5); p.lineTo(19, 8.5f); p.lineTo(13, 16.5f); p.close();
                    p.moveTo(9, 13.5f); p.lineTo(7, 18); p.lineTo(11, 18); p.lineTo(13, 16.5f);
                    p.moveTo(4, 21); p.lineTo(20, 21);
                    break;
                case I_ERASER:
                    p.moveTo(4, 15); p.lineTo(12, 6); p.lineTo(19, 12.5f); p.lineTo(12, 20); p.lineTo(8, 20); p.close();
                    p.moveTo(8, 10.5f); p.lineTo(15, 16.5f);
                    p.moveTo(12, 20); p.lineTo(20, 20);
                    break;
                case I_UNDO:
                    p.moveTo(9, 5); p.lineTo(4.5f, 9.5f); p.lineTo(9, 14);
                    p.moveTo(4.5f, 9.5f); p.lineTo(14, 9.5f);
                    p.cubicTo(17.5f, 9.5f, 19.5f, 12, 19.5f, 15); p.cubicTo(19.5f, 18, 17.5f, 20, 14, 20); p.lineTo(9, 20);
                    break;
                case I_EYE:
                    p.moveTo(2.5f, 12); p.cubicTo(5.5f, 6, 18.5f, 6, 21.5f, 12); p.cubicTo(18.5f, 18, 5.5f, 18, 2.5f, 12); p.close();
                    p.addCircle(12, 12, 3, Path.Direction.CW);
                    break;
                case I_SKETCH:
                    p.moveTo(3.5f, 16); p.cubicTo(6.5f, 6, 9, 6, 10, 12.5f); p.cubicTo(11, 19, 14, 19, 15, 12);
                    p.cubicTo(16, 6, 19, 6, 20.5f, 10);
                    break;
                case I_EXPORT:
                    p.moveTo(12, 15); p.lineTo(12, 4); p.moveTo(8, 8); p.lineTo(12, 4); p.lineTo(16, 8);
                    p.moveTo(5, 13); p.lineTo(5, 19.5f); p.lineTo(19, 19.5f); p.lineTo(19, 13);
                    break;
                case I_GROW:
                    p.moveTo(5, 9); p.lineTo(19, 9); p.moveTo(12, 12); p.lineTo(12, 20); p.moveTo(8.5f, 16.5f);
                    p.lineTo(12, 20); p.lineTo(15.5f, 16.5f);
                    break;
                case I_LINES:
                    p.moveTo(5, 7); p.lineTo(19, 7); p.moveTo(5, 12); p.lineTo(19, 12); p.moveTo(5, 17); p.lineTo(19, 17);
                    break;
                case I_REDO:
                    p.moveTo(15, 5); p.lineTo(19.5f, 9.5f); p.lineTo(15, 14);
                    p.moveTo(19.5f, 9.5f); p.lineTo(10, 9.5f);
                    p.cubicTo(6.5f, 9.5f, 4.5f, 12, 4.5f, 15); p.cubicTo(4.5f, 18, 6.5f, 20, 10, 20); p.lineTo(15, 20);
                    break;
                case I_GRID:
                    p.addRect(4, 4, 10.5f, 10.5f, Path.Direction.CW); p.addRect(13.5f, 4, 20, 10.5f, Path.Direction.CW);
                    p.addRect(4, 13.5f, 10.5f, 20, Path.Direction.CW); p.addRect(13.5f, 13.5f, 20, 20, Path.Direction.CW);
                    break;
                case I_LIST:
                    p.addRect(4, 4.5f, 20, 10.5f, Path.Direction.CW); p.addRect(4, 13.5f, 20, 19.5f, Path.Direction.CW);
                    break;
                case I_IMAGE:
                    p.addRect(3.5f, 5, 20.5f, 19, Path.Direction.CW);
                    p.moveTo(3.5f, 17); p.lineTo(9, 11.5f); p.lineTo(13, 15.5f); p.lineTo(15.5f, 13); p.lineTo(20.5f, 18);
                    p.addCircle(15.5f, 9, 1.6f, Path.Direction.CW);
                    break;
                case I_LASSO:
                    p.addOval(new android.graphics.RectF(3.5f, 3.5f, 20.5f, 14.5f), Path.Direction.CW);
                    p.moveTo(8, 13.5f); p.cubicTo(5.5f, 16.5f, 9.5f, 18.5f, 7, 21);
                    break;
                case I_SETTINGS:
                    p.moveTo(4, 7); p.lineTo(20, 7); p.moveTo(4, 12); p.lineTo(20, 12); p.moveTo(4, 17); p.lineTo(20, 17);
                    c.drawPath(p, paint); p.reset();
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(9, 7, 2.3f, paint); c.drawCircle(15, 12, 2.3f, paint); c.drawCircle(8, 17, 2.3f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    break;
                case I_FOLDER:
                    p.moveTo(3.5f, 6); p.lineTo(9.5f, 6); p.lineTo(11.5f, 8.5f); p.lineTo(20.5f, 8.5f);
                    p.lineTo(20.5f, 18.5f); p.lineTo(3.5f, 18.5f); p.close();
                    break;
                case I_SHARE:
                    p.addCircle(17, 5.5f, 2.3f, Path.Direction.CW); p.addCircle(7, 12, 2.3f, Path.Direction.CW);
                    p.addCircle(17, 18.5f, 2.3f, Path.Direction.CW);
                    p.moveTo(9, 10.8f); p.lineTo(15, 6.8f); p.moveTo(9, 13.2f); p.lineTo(15, 17.2f);
                    break;
                case I_ZOOM:
                    p.addCircle(10.5f, 10.5f, 6, Path.Direction.CW); p.moveTo(15, 15); p.lineTo(20, 20);
                    p.moveTo(7.5f, 10.5f); p.lineTo(13.5f, 10.5f);
                    break;
                case I_UP: p.moveTo(6, 15); p.lineTo(12, 9); p.lineTo(18, 15); break;
                case I_DOWN: p.moveTo(6, 9); p.lineTo(12, 15); p.lineTo(18, 9); break;
            }
            c.drawPath(p, paint);
            c.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
