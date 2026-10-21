package com.jot.notes;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** All user settings in one place. */
public final class Prefs {
    private Prefs() {}

    static SharedPreferences p(Context c) { return Store.prefs(c); }

    // ---- pen
    public static boolean pressure(Context c) { return p(c).getBoolean("pressure", false); }
    public static boolean palmRejection(Context c) { return p(c).getBoolean("palm", false); }
    public static boolean shapeSnap(Context c) { return p(c).getBoolean("shapeSnap", true); }
    public static int smoothing(Context c) { return p(c).getInt("smoothing", 1); }
    public static boolean partialEraser(Context c) { return p(c).getBoolean("partialEraser", false); }

    // ---- home
    public static final int LAYOUT_LIST = 0, LAYOUT_GRID = 1;
    public static int layout(Context c) { return p(c).getInt("layout", LAYOUT_LIST); }
    public static int sort(Context c) { return p(c).getInt("sort", Store.SORT_EDITED); }

    public static void put(Context c, String k, boolean v) { p(c).edit().putBoolean(k, v).apply(); }
    public static void put(Context c, String k, int v) { p(c).edit().putInt(k, v).apply(); }

    // ---- toolbars: {id, label}
    public static final String[][] FORMAT_ITEMS = {
            {"undo", "Undo"}, {"redo", "Redo"},
            {"hand", "Handwriting page"}, {"canvas", "Freewrite canvas"}, {"image", "Insert image"},
            {"bold", "Bold"}, {"italic", "Italic"}, {"underline", "Underline"}, {"strike", "Strikethrough"},
            {"highlight", "Highlight"}, {"heading", "Heading"}, {"bullet", "Bullet list"}, {"number", "Numbered list"},
            {"check", "Checklist"}, {"quote", "Quote"}, {"code", "Code"}, {"rule", "Divider line"},
    };
    public static final String[][] DRAW_ITEMS = {
            {"done", "Done"}, {"pen", "Pen"}, {"marker", "Highlighter"}, {"eraser", "Eraser"}, {"lasso", "Lasso select"},
            {"colors", "Colours"}, {"size", "Stroke size"}, {"smooth", "Smoothing"},
            {"undo", "Undo"}, {"redo", "Redo"}, {"zoom", "Reset zoom"},
            {"paper", "Paper style"}, {"space", "Add space"}, {"trim", "Trim empty space"},
            {"svg", "Save as SVG"}, {"clear", "Clear drawing"}, {"delete", "Delete drawing"},
    };

    public static String label(String[][] items, String id) {
        for (String[] it : items) if (it[0].equals(id)) return it[1];
        return id;
    }

    /** Ordered ids; hidden ones are prefixed with "-". Unknown/new items are appended visible. */
    public static List<String> toolbar(Context c, String key, String[][] items) {
        List<String> out = new ArrayList<>();
        String saved = p(c).getString(key, "");
        for (String tok : saved.split(",")) {
            String id = tok.startsWith("-") ? tok.substring(1) : tok;
            boolean known = false;
            for (String[] it : items) if (it[0].equals(id)) known = true;
            if (known && !out.contains(id) && !out.contains("-" + id)) out.add(tok);
        }
        for (String[] it : items) if (!out.contains(it[0]) && !out.contains("-" + it[0])) out.add(it[0]);
        return out;
    }

    public static void saveToolbar(Context c, String key, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String s : ids) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        p(c).edit().putString(key, sb.toString()).apply();
    }

    public static void resetToolbar(Context c, String key) { p(c).edit().remove(key).apply(); }

    // ---- app lock
    public static boolean lockOn(Context c) { return p(c).getString("pinHash", null) != null; }
    public static boolean biometric(Context c) { return p(c).getBoolean("biometric", true); }
    /** Minutes in the background before locking again (0 = immediately). */
    public static int lockAfter(Context c) { return p(c).getInt("lockAfter", 1); }

    public static void setPin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        p(c).edit().putString("pinSalt", s).putString("pinHash", hash(s, pin)).apply();
    }

    public static void clearPin(Context c) { p(c).edit().remove("pinSalt").remove("pinHash").apply(); }

    public static boolean checkPin(Context c, String pin) {
        String s = p(c).getString("pinSalt", ""), h = p(c).getString("pinHash", null);
        return h != null && h.equals(hash(s, pin));
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = (salt + ":" + pin).getBytes(Store.UTF8);
            for (int i = 0; i < 20000; i++) d = md.digest(d);
            return hex(d);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
