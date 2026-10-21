package com.jot.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/** Importing (downscaled, rotation-corrected JPEG copies) and loading images for notes. */
public final class Images {
    private Images() {}

    static final int MAX_EDGE = 2000;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    /** Copies an image into app storage. Returns {fileName, width, height} or throws. */
    public static Object[] importImage(Context c, Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IllegalArgumentException("Not an image");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(in, null, d); }
        if (bm == null) throw new IllegalArgumentException("Couldn't read that image");

        int rotation = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            int or = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) {}

        float scale = Math.min(1f, MAX_EDGE / (float) Math.max(bm.getWidth(), bm.getHeight()));
        if (rotation != 0 || scale < 1f) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            bm = r;
        }
        String name = UUID.randomUUID().toString() + ".jpg";
        File f = Store.imageFile(c, name);
        try (FileOutputStream os = new FileOutputStream(f)) {
            Bitmap out = bm;
            if (bm.hasAlpha()) { // flatten transparency onto white for JPEG
                out = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(out);
                cv.drawColor(0xFFFFFFFF);
                cv.drawBitmap(bm, 0, 0, null);
            }
            out.compress(Bitmap.CompressFormat.JPEG, 88, os);
            if (out != bm) out.recycle();
        }
        Object[] res = {name, bm.getWidth(), bm.getHeight()};
        bm.recycle();
        return res;
    }

    /** Loads a stored image scaled down to about targetWidth pixels (cached). */
    public static Bitmap load(Context c, String name, int targetWidth) {
        String key = name + "@" + targetWidth;
        Bitmap b = CACHE.get(key);
        if (b != null && !b.isRecycled()) return b;
        File f = Store.imageFile(c, name);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (targetWidth > 0 && o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        b = BitmapFactory.decodeFile(f.getPath(), d);
        if (b != null) CACHE.put(key, b);
        return b;
    }
}
