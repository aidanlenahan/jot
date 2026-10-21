package com.jot.notes;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Asks GitHub for the newest published release and compares it with the installed version.
 * The request carries no note data: it's a plain GET of the public "latest release" info.
 */
public final class UpdateCheck {
    private UpdateCheck() {}

    public static final String REPO = "aidanlenahan/jot";
    public static final String RELEASES_URL = "https://github.com/" + REPO + "/releases/latest";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long DAY = 24L * 3600_000L;
    private static final String[] OBTAINIUM = {"dev.imranr.obtainium", "dev.imranr.obtainium.fdroid"};

    public interface Result {
        /** latest = newest version name (null if unknown), error = message when the check failed. */
        void done(String latest, boolean newer, String error);
    }

    public static boolean enabled(Context c) { return Prefs.p(c).getBoolean("updateCheck", true); }

    public static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    /** Newest version seen by the last successful check, if it's newer than what's installed and not dismissed. */
    public static String pendingUpdate(Context c) {
        String latest = Prefs.p(c).getString("latestVersion", null);
        if (latest == null || compare(latest, installed(c)) <= 0) return null;
        if (latest.equals(Prefs.p(c).getString("dismissedVersion", null))) return null;
        return latest;
    }

    public static void dismiss(Context c, String version) {
        Prefs.p(c).edit().putString("dismissedVersion", version).apply();
    }

    /** Checks at most once a day unless forced. The callback runs on the main thread. */
    public static void check(Context ctx, boolean force, Result cb) {
        final Context c = ctx.getApplicationContext();
        long last = Prefs.p(c).getLong("lastUpdateCheck", 0);
        if (!force && System.currentTimeMillis() - last < DAY) return;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String latest = null, error = null;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(API_URL).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                con.setRequestProperty("Accept", "application/vnd.github+json");
                con.setRequestProperty("User-Agent", "Jot/" + installed(c));
                int code = con.getResponseCode();
                if (code == 404) error = "No releases published yet";
                else if (code == 403 || code == 429) error = "GitHub is rate-limiting this network; try again later";
                else if (code != 200) error = "GitHub answered " + code;
                else {
                    String body;
                    try (InputStream in = con.getInputStream()) { body = Store.readAll(in); }
                    String tag = new JSONObject(body).optString("tag_name", "");
                    latest = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                    if (latest.isEmpty()) { latest = null; error = "Couldn't read the latest version"; }
                }
            } catch (Exception e) {
                error = "Couldn't reach GitHub (offline?)";
            } finally {
                if (con != null) con.disconnect();
            }
            if (latest != null) {
                Prefs.p(c).edit().putString("latestVersion", latest).putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
            }
            final String l = latest, err = error;
            final boolean newer = l != null && compare(l, installed(c)) > 0;
            if (cb != null) main.post(() -> cb.done(l, newer, err));
        }).start();
    }

    /** Opens Obtainium if it's installed (it does the actual update), otherwise the release page. */
    public static void openUpdater(Context c) {
        for (String pkg : OBTAINIUM) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(i); return; } catch (Exception ignored) {}
            }
        }
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    /** Numeric dotted-version comparison: "1.10" > "1.9", "1.2" == "1.2.0". */
    public static int compare(String a, String b) {
        String[] x = a.trim().split("[.\\-+ ]"), y = b.trim().split("[.\\-+ ]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        int n = 0, i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) { n = n * 10 + (s.charAt(i) - '0'); i++; }
        return n;
    }
}
