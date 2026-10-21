package com.jot.notes;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything is stored on the device: one JSON file per note, images as files.
 * "Sync" is local only: an optional folder you pick (e.g. one Syncthing / a USB-synced
 * folder watches) gets a single Jot-sync.json that is merged both ways, newest edit wins.
 */
public final class Store {
    private Store() {}

    static final Charset UTF8 = Charset.forName("UTF-8");
    public static final String SYNC_FILE = "Jot-sync.json";
    public static final long TRASH_DAYS = 30;
    private static final Object LOCK = new Object();

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "notes");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File imageDir(Context c) {
        File d = new File(c.getFilesDir(), "images");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File imageFile(Context c, String name) { return new File(imageDir(c), safe(name)); }

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("jot", Context.MODE_PRIVATE); }

    // ------------------------------------------------------------------ notes

    /** Every stored note, including trashed ones. */
    public static List<Note> all(Context c) {
        List<Note> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try { out.add(Note.fromJson(new JSONObject(read(f)))); } catch (Exception ignored) {}
        }
        return out;
    }

    public static final int SORT_EDITED = 0, SORT_CREATED = 1, SORT_TITLE = 2;

    public static void sort(List<Note> notes, int mode) {
        Collections.sort(notes, (a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            switch (mode) {
                case SORT_CREATED: return Long.compare(b.created, a.created);
                case SORT_TITLE: {
                    String ta = a.title.trim().toLowerCase(Locale.ROOT), tb = b.title.trim().toLowerCase(Locale.ROOT);
                    if (ta.isEmpty() != tb.isEmpty()) return ta.isEmpty() ? 1 : -1;
                    int r = ta.compareTo(tb);
                    return r != 0 ? r : Long.compare(b.updated, a.updated);
                }
                default: return Long.compare(b.updated, a.updated);
            }
        });
    }

    public static Note load(Context c, String id) {
        if (id == null) return null;
        File f = new File(dir(c), safe(id) + ".json");
        if (!f.exists()) return null;
        try { return Note.fromJson(new JSONObject(read(f))); } catch (Exception e) { return null; }
    }

    public static void save(Context c, Note n) {
        synchronized (LOCK) {
            try {
                String json = n.toJson().toString();
                File f = new File(dir(c), safe(n.id) + ".json");
                File tmp = new File(dir(c), safe(n.id) + ".tmp");
                write(tmp, json);
                if (!tmp.renameTo(f)) { write(f, json); tmp.delete(); }
            } catch (Exception ignored) {}
        }
    }

    /** Moves a note to the trash (restorable for 30 days). */
    public static void trash(Context c, Note n) {
        n.trashed = n.updated = System.currentTimeMillis();
        save(c, n);
    }

    public static void restore(Context c, Note n) {
        n.trashed = 0;
        n.updated = System.currentTimeMillis();
        save(c, n);
    }

    /** Permanently deletes a note and its images; leaves a tombstone so sync removes it elsewhere too. */
    public static void deleteForever(Context c, String id) {
        synchronized (LOCK) {
            Note n = load(c, id);
            new File(dir(c), safe(id) + ".json").delete();
            if (n != null) for (String img : n.images()) imageFile(c, img).delete();
            try {
                JSONObject t = tombs(c);
                t.put(id, System.currentTimeMillis());
                write(new File(c.getFilesDir(), "deleted.json"), t.toString());
            } catch (Exception ignored) {}
        }
    }

    /** Empties notes trashed more than 30 days ago and removes orphaned image files. */
    public static void housekeeping(Context c) {
        long cutoff = System.currentTimeMillis() - TRASH_DAYS * 24L * 3600_000L;
        Set<String> used = new HashSet<>();
        for (Note n : all(c)) {
            if (n.trashed != 0 && n.trashed < cutoff) { deleteForever(c, n.id); continue; }
            used.addAll(n.images());
        }
        File[] imgs = imageDir(c).listFiles();
        long day = System.currentTimeMillis() - 24L * 3600_000L;
        if (imgs != null) for (File f : imgs) if (!used.contains(f.getName()) && f.lastModified() < day) f.delete();
    }

    static JSONObject tombs(Context c) {
        try {
            File f = new File(c.getFilesDir(), "deleted.json");
            if (f.exists()) return new JSONObject(read(f));
        } catch (Exception ignored) {}
        return new JSONObject();
    }

    private static String safe(String id) { return id.replaceAll("[^A-Za-z0-9.-]", "_"); }

    // ------------------------------------------------------------------ folders

    public static List<String> folders(Context c) { return folders(c, all(c)); }

    public static List<String> folders(Context c, List<Note> notes) {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            JSONArray a = new JSONArray(prefs(c).getString("folders", "[]"));
            for (int i = 0; i < a.length(); i++) if (!a.getString(i).trim().isEmpty()) set.add(a.getString(i).trim());
        } catch (Exception ignored) {}
        for (Note n : notes) if (!n.folder.isEmpty()) set.add(n.folder);
        return new ArrayList<>(set);
    }

    private static void saveFolderList(Context c, List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        prefs(c).edit().putString("folders", a.toString()).apply();
    }

    public static void addFolder(Context c, String name) {
        List<String> f = folders(c);
        if (!f.contains(name)) f.add(name);
        saveFolderList(c, f);
    }

    public static void renameFolder(Context c, String from, String to) {
        List<String> f = folders(c);
        f.remove(from);
        if (!f.contains(to)) f.add(to);
        saveFolderList(c, f);
        for (Note n : all(c)) if (from.equals(n.folder)) { n.folder = to; n.updated = System.currentTimeMillis(); save(c, n); }
    }

    /** Removes the folder; its notes stay but become unfiled. */
    public static void deleteFolder(Context c, String name) {
        List<String> f = folders(c);
        f.remove(name);
        saveFolderList(c, f);
        for (Note n : all(c)) if (name.equals(n.folder)) { n.folder = ""; n.updated = System.currentTimeMillis(); save(c, n); }
    }

    // ------------------------------------------------------------------ bundle (backup + sync)

    public static String exportBundle(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "jot").put("version", 2).put("exported", System.currentTimeMillis());
        JSONArray arr = new JSONArray();
        JSONObject images = new JSONObject();
        for (Note n : all(c)) {
            arr.put(n.toJson());
            for (String img : n.images()) {
                File f = imageFile(c, img);
                if (f.exists() && !images.has(img)) images.put(img, Base64.encodeToString(readBytes(f), Base64.NO_WRAP));
            }
        }
        o.put("notes", arr);
        o.put("images", images);
        JSONArray folders = new JSONArray();
        for (String f : folders(c)) folders.put(f);
        o.put("folders", folders);
        o.put("deleted", tombs(c));
        return o.toString();
    }

    /** Merges a bundle into local storage. Returns number of notes added or updated. */
    public static int mergeBundle(Context c, String json, boolean applyDeletes) throws Exception {
        JSONObject o = new JSONObject(json);
        int changed = 0;
        synchronized (LOCK) {
            JSONObject images = o.optJSONObject("images");
            if (images != null) {
                Iterator<String> it = images.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    File f = imageFile(c, name);
                    if (!f.exists()) {
                        try (OutputStream os = new FileOutputStream(f)) { os.write(Base64.decode(images.getString(name), Base64.DEFAULT)); }
                        catch (Exception ignored) {}
                    }
                }
            }
            JSONObject localTombs = tombs(c);
            JSONObject remoteTombs = o.optJSONObject("deleted");
            JSONArray arr = o.optJSONArray("notes");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                Note r;
                try { r = Note.fromJson(arr.getJSONObject(i)); } catch (Exception e) { continue; }
                long tomb = localTombs.optLong(r.id, 0);
                if (tomb >= r.updated) continue;
                Note l = load(c, r.id);
                if (l == null || r.updated > l.updated) { saveRaw(c, r); changed++; }
            }
            if (applyDeletes && remoteTombs != null) {
                Iterator<String> it = remoteTombs.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    long t = remoteTombs.optLong(id, 0);
                    Note l = load(c, id);
                    if (l != null && t >= l.updated) {
                        new File(dir(c), safe(id) + ".json").delete();
                        for (String img : l.images()) imageFile(c, img).delete();
                        changed++;
                    }
                    if (t > localTombs.optLong(id, 0)) localTombs.put(id, t);
                }
                write(new File(c.getFilesDir(), "deleted.json"), localTombs.toString());
            }
        }
        JSONArray folders = o.optJSONArray("folders");
        if (folders != null) {
            List<String> mine = folders(c);
            for (int i = 0; i < folders.length(); i++) {
                String f = folders.optString(i, "").trim();
                if (!f.isEmpty() && !mine.contains(f)) mine.add(f);
            }
            saveFolderList(c, mine);
        }
        return changed;
    }

    private static void saveRaw(Context c, Note n) throws Exception {
        write(new File(dir(c), safe(n.id) + ".json"), n.toJson().toString());
    }

    // ------------------------------------------------------------------ folder sync

    public static Uri syncFolder(Context c) {
        String s = prefs(c).getString("syncTree", null);
        return s == null ? null : Uri.parse(s);
    }

    /** Two-way merge with Jot-sync.json in the chosen folder. Returns notes changed locally, or -1 if no folder. */
    public static int syncNow(Context c) throws Exception {
        Uri tree = syncFolder(c);
        if (tree == null) return -1;
        ContentResolver cr = c.getContentResolver();
        Uri doc = findChild(cr, tree, SYNC_FILE);
        int changed = 0;
        if (doc != null) {
            String remote;
            try (InputStream in = cr.openInputStream(doc)) { remote = readAll(in); }
            if (remote != null && !remote.trim().isEmpty()) changed = mergeBundle(c, remote, true);
        } else {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            doc = DocumentsContract.createDocument(cr, parent, "application/json", SYNC_FILE);
            if (doc == null) throw new IllegalStateException("Couldn't create the sync file in that folder");
        }
        byte[] out = exportBundle(c).getBytes(UTF8);
        OutputStream os;
        try { os = cr.openOutputStream(doc, "wt"); } catch (Exception e) { os = cr.openOutputStream(doc, "w"); }
        try (OutputStream o = os) { o.write(out); }
        prefs(c).edit().putLong("lastSync", System.currentTimeMillis()).apply();
        return changed;
    }

    private static Uri findChild(ContentResolver cr, Uri tree, String name) {
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        try (Cursor cur = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                if (name.equals(cur.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ------------------------------------------------------------------ io

    static String read(File f) throws Exception { try (InputStream in = new FileInputStream(f)) { return readAll(in); } }

    static byte[] readBytes(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        return new String(bo.toByteArray(), UTF8);
    }

    static void write(File f, String s) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(UTF8)); }
    }
}
