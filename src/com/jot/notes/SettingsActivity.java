package com.jot.notes;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends BaseActivity {

    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.bg);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(4), dp(4), dp(4));
        top.addView(Ui.iconButton(this, pal, Ui.I_BACK, pal.text, "Back", v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = new TextView(this);
        t.setText("Settings");
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(pal.text);
        top.addView(t);
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(40));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        build();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void build() {
        list.removeAllViews();

        header("Appearance");
        String[] themes = {"Follow system", "Light", "Dark"};
        row("Theme", themes[Ui.themeMode(this)], v -> dialog().setTitle("Theme")
                .setSingleChoiceItems(themes, Ui.themeMode(this), (d, w) -> {
                    Store.prefs(this).edit().putInt("theme", w).apply();
                    d.dismiss();
                    recreate();
                }).show());

        header("Home screen");
        String[] layouts = {"List", "Grid"};
        row("Layout", layouts[Prefs.layout(this)], v -> choose("Layout", layouts, Prefs.layout(this), w -> Prefs.put(this, "layout", w)));
        String[] sorts = {"Last edited", "Date created", "Title (A–Z)"};
        row("Sort notes by", sorts[Prefs.sort(this)], v -> choose("Sort notes by", sorts, Prefs.sort(this), w -> Prefs.put(this, "sort", w)));

        header("Handwriting");
        toggle("Pressure-sensitive pen",
                "Adds a “P” size to the drawing toolbar: press harder for thicker lines. Only works with an active "
                        + "stylus that reports pressure (e.g. Made for Amazon Stylus, USI 2.0 pens, Samsung S Pen). Fingers and "
                        + "rubber-tip styluses draw at a fixed thickness.",
                Prefs.pressure(this), on -> { Prefs.put(this, "pressure", on); if (!on) SketchView.pressureMode = false; });
        toggle("Palm rejection",
                "Once a stylus has touched a drawing, single-finger touches on drawings are ignored so your hand can rest on the "
                        + "screen. Two fingers still zoom; tap ✓ Done (or scroll outside the drawing) to scroll with a finger.",
                Prefs.palmRejection(this), on -> Prefs.put(this, "palm", on));
        toggle("Shape snap",
                "Hold the pen still at the end of a stroke to turn a rough line, box, circle or oval into a clean one.",
                Prefs.shapeSnap(this), on -> Prefs.put(this, "shapeSnap", on));
        toggle("Partial eraser",
                "The eraser removes only what you rub over instead of whole strokes. (Tapping the eraser button twice also switches.)",
                Prefs.partialEraser(this), on -> Prefs.put(this, "partialEraser", on));
        String[] smooth = {"Raw", "Smooth", "Extra smooth"};
        row("Stroke smoothing", smooth[Prefs.smoothing(this)], v ->
                choose("Stroke smoothing", smooth, Prefs.smoothing(this), w -> Prefs.put(this, "smoothing", w)));

        header("Toolbars");
        row("Note toolbar", "Choose and reorder the formatting buttons", v -> customize("Note toolbar", "fmtBar", Prefs.FORMAT_ITEMS));
        row("Drawing toolbar", "Choose and reorder the drawing buttons", v -> customize("Drawing toolbar", "drawBar", Prefs.DRAW_ITEMS));

        header("Security");
        toggle("App lock", "Ask for a PIN when Jot is opened. If you forget the PIN the only way back in is clearing the "
                        + "app's data, which deletes notes that aren't in a backup or sync folder.",
                Prefs.lockOn(this), on -> {
                    if (on) setPinDialog(); else { Prefs.clearPin(this); toast("App lock off"); build(); }
                });
        if (Prefs.lockOn(this)) {
            row("Change PIN", null, v -> setPinDialog());
            if (Build.VERSION.SDK_INT >= 28) {
                toggle("Unlock with fingerprint / face", "Uses the biometrics set up on this device; the PIN always works too.",
                        Prefs.biometric(this), on -> Prefs.put(this, "biometric", on));
            }
            String[] after = {"Immediately", "After 1 minute", "After 5 minutes", "After 15 minutes"};
            int[] mins = {0, 1, 5, 15};
            int cur = 0;
            for (int i = 0; i < mins.length; i++) if (mins[i] == Prefs.lockAfter(this)) cur = i;
            row("Lock again", after[cur], v -> choose("Lock again", after, indexOf(mins, Prefs.lockAfter(this)),
                    w -> Prefs.put(this, "lockAfter", mins[w])));
        }

        header("Your data");
        row("Export everything", "One backup file with every note, drawing (still editable) and image", v -> backup());
        row("Import backup", "Merge a Jot backup from this or another device", v -> restore());
        Uri f = Store.syncFolder(this);
        row("Local sync folder", f == null ? "Off" : Uri.decode(f.getLastPathSegment()), v -> syncDialog());
        row("Trash", "Deleted notes are kept for " + Store.TRASH_DAYS + " days", v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra("trash", true));
        });

        header("About");
        row("Jot " + versionName(), "Free, offline notes. No accounts, ads, tracking or internet permission.", null);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    private static int indexOf(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return 0; }

    interface IntCb { void on(int v); }
    interface BoolCb { void on(boolean v); }

    private void choose(String title, String[] items, int cur, IntCb cb) {
        dialog().setTitle(title).setSingleChoiceItems(items, cur, (d, w) -> { cb.on(w); d.dismiss(); build(); }).show();
    }

    // ---------------------------------------------------------------- rows

    private void header(String s) {
        TextView h = new TextView(this);
        h.setText(s.toUpperCase(Locale.ROOT));
        h.setTextSize(12);
        h.setLetterSpacing(0.08f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(pal.accent);
        h.setPadding(dp(20), dp(22), dp(20), dp(6));
        list.addView(h);
    }

    private LinearLayout textCol(String title, String sub) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(pal.text);
        col.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(13);
            s.setTextColor(pal.sub);
            s.setPadding(0, dp(2), 0, 0);
            s.setLineSpacing(0, 1.1f);
            col.addView(s);
        }
        return col;
    }

    private void row(String title, String sub, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(dp(20), dp(12), dp(20), dp(12));
        r.addView(textCol(title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        if (l != null) {
            r.setBackground(Ui.ripple(pal, null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
            r.setOnClickListener(l);
        }
        list.addView(r);
    }

    private void toggle(String title, String sub, boolean on, BoolCb cb) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(12), dp(14), dp(12));
        r.addView(textCol(title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setPadding(dp(12), 0, 0, 0);
        r.addView(sw);
        r.setBackground(Ui.ripple(pal, null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        r.setOnClickListener(v -> sw.toggle());
        sw.setOnCheckedChangeListener((b, c) -> cb.on(c));
        list.addView(r);
    }

    // ---------------------------------------------------------------- toolbars

    private void customize(String title, String key, String[][] items) {
        final List<String> ids = Prefs.toolbar(this, key, items);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            box.removeAllViews();
            TextView hint = new TextView(this);
            hint.setText("Untick to hide a button. Use the arrows to change the order.");
            hint.setTextColor(pal.sub);
            hint.setTextSize(13);
            hint.setPadding(dp(12), 0, dp(12), dp(8));
            box.addView(hint);
            for (int i = 0; i < ids.size(); i++) {
                final int idx = i;
                String tok = ids.get(i);
                boolean hidden = tok.startsWith("-");
                String id = hidden ? tok.substring(1) : tok;
                LinearLayout r = new LinearLayout(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                CheckBox cb = new CheckBox(this);
                cb.setText(Prefs.label(items, id));
                cb.setTextColor(pal.text);
                cb.setChecked(!hidden);
                cb.setOnCheckedChangeListener((b, c) -> {
                    ids.set(idx, c ? id : "-" + id);
                    Prefs.saveToolbar(this, key, ids);
                });
                r.addView(cb, new LinearLayout.LayoutParams(0, -2, 1));
                ImageView up = Ui.iconButton(this, pal, Ui.I_UP, i == 0 ? pal.border : pal.sub, "Move up", v -> {
                    if (idx == 0) return;
                    ids.add(idx - 1, ids.remove(idx));
                    Prefs.saveToolbar(this, key, ids);
                    rebuild[0].run();
                });
                ImageView down = Ui.iconButton(this, pal, Ui.I_DOWN, i == ids.size() - 1 ? pal.border : pal.sub, "Move down", v -> {
                    if (idx >= ids.size() - 1) return;
                    ids.add(idx + 1, ids.remove(idx));
                    Prefs.saveToolbar(this, key, ids);
                    rebuild[0].run();
                });
                r.addView(up, new LinearLayout.LayoutParams(dp(44), dp(44)));
                r.addView(down, new LinearLayout.LayoutParams(dp(44), dp(44)));
                box.addView(r);
            }
        };
        rebuild[0].run();
        dialog().setTitle(title).setView(sv).setPositiveButton("Done", null)
                .setNeutralButton("Reset", (d, w) -> { Prefs.resetToolbar(this, key); toast("Toolbar reset"); })
                .show();
    }

    // ---------------------------------------------------------------- lock

    private void setPinDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        EditText a = pinField("New PIN (4–8 digits)"), b = pinField("Repeat PIN");
        box.addView(a);
        box.addView(b);
        dialog().setTitle(Prefs.lockOn(this) ? "Change PIN" : "Set a PIN").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String p1 = a.getText().toString(), p2 = b.getText().toString();
                    if (p1.length() < 4 || p1.length() > 8) toast("Use 4 to 8 digits");
                    else if (!p1.equals(p2)) toast("The PINs don't match");
                    else { Prefs.setPin(this, p1); unlocked = true; toast("PIN saved"); }
                    build();
                })
                .setNegativeButton("Cancel", (d, w) -> build())
                .setOnCancelListener(d -> build())
                .show();
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setTextColor(pal.text);
        e.setHintTextColor(pal.faint);
        return e;
    }

    // ---------------------------------------------------------------- data

    private void backup() {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        saveAs("Jot-backup-" + date + ".json", "application/json",
                os -> os.write(Store.exportBundle(this).getBytes(Store.UTF8)));
    }

    private void restore() {
        dialog().setTitle("Import backup")
                .setMessage("Pick a Jot backup (.json) from this or another device. Notes are merged in: new notes are added and, "
                        + "when both copies exist, the most recently edited one is kept. Drawings come back fully editable.")
                .setPositiveButton("Choose file", (d, w) -> openFile(in -> {
                    String json = Store.readAll(in);
                    if (json == null || !json.contains("\"notes\"")) return "That doesn't look like a Jot backup";
                    int n = Store.mergeBundle(this, json, false);
                    syncInBackground(this, false, null);
                    return n == 0 ? "Everything was already up to date" : "Imported " + n + (n == 1 ? " note" : " notes");
                }))
                .setNegativeButton("Cancel", null).show();
    }

    private void syncDialog() {
        Uri f = Store.syncFolder(this);
        long last = Store.prefs(this).getLong("lastSync", 0);
        String msg = "Jot never uses the internet. To keep devices in step, pick a folder that a local sync tool "
                + "(Syncthing, an SD card, a NAS share…) already syncs. Jot keeps a single " + Store.SYNC_FILE
                + " there and merges it both ways whenever you open the app or leave a note.\n\n"
                + (f == null ? "No folder chosen." : "Folder: " + Uri.decode(f.getLastPathSegment())
                + (last > 0 ? "\nLast synced " + android.text.format.DateUtils.getRelativeTimeSpanString(last) : ""));
        android.app.AlertDialog.Builder b = dialog().setTitle("Local sync folder").setMessage(msg)
                .setPositiveButton(f == null ? "Choose folder" : "Change folder", (d, w) -> pickFolder(uri -> {
                    Store.prefs(this).edit().putString("syncTree", uri.toString()).apply();
                    syncInBackground(this, true, null);
                    build();
                }))
                .setNegativeButton("Close", null);
        if (f != null) {
            b.setNeutralButton("Stop syncing", (d, w) -> {
                Store.prefs(this).edit().remove("syncTree").apply();
                try { getContentResolver().releasePersistableUriPermission(f, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) {}
                toast("Sync turned off");
                build();
            });
        }
        b.show();
    }

    /** Runs a folder sync off the main thread. */
    static void syncInBackground(BaseActivity a, boolean announce, Runnable onChanged) {
        if (Store.syncFolder(a) == null) return;
        final android.content.Context app = a.getApplicationContext();
        new Thread(() -> {
            String msg = null;
            int changed = 0;
            try { changed = Store.syncNow(app); if (announce) msg = "Synced"; }
            catch (Exception e) { msg = "Sync failed: " + e.getMessage(); }
            final String m = msg; final int c = changed;
            a.main.post(() -> {
                if (m != null) a.toast(m);
                if (c > 0 && onChanged != null) onChanged.run();
            });
        }).start();
    }
}
