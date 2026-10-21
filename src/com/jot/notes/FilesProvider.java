package com.jot.notes;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Tiny file provider (no support library) so other apps can read a shared PDF or write a camera photo.
 * Only files inside the app's cache "share" and "camera" folders are reachable, and only via granted URIs.
 */
public class FilesProvider extends ContentProvider {
    public static final String AUTHORITY = "com.jot.notes.files";

    public static Uri uriFor(String dir, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(dir).appendPath(name).build();
    }

    public static File fileFor(Context c, String dir, String name) {
        File d = new File(c.getCacheDir(), dir);
        if (!d.exists()) d.mkdirs();
        return new File(d, name);
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri.getPathSegments().size() != 2) throw new FileNotFoundException();
        String dir = uri.getPathSegments().get(0), name = uri.getPathSegments().get(1);
        if (!dir.equals("share") && !dir.equals("camera")) throw new FileNotFoundException();
        try {
            File f = fileFor(getContext(), dir, name).getCanonicalFile();
            File root = new File(getContext().getCacheDir(), dir).getCanonicalFile();
            if (!f.getPath().startsWith(root.getPath() + File.separator)) throw new FileNotFoundException();
            return f;
        } catch (java.io.IOException e) { throw new FileNotFoundException(); }
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try { f = resolve(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public String getType(Uri uri) {
        String p = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".pdf")) return "application/pdf";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".docx")) return Exporter.MIME[Exporter.DOCX];
        if (p.endsWith(".md")) return "text/markdown";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
