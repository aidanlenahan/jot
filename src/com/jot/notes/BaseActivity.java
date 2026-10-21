package com.jot.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Theme, app lock and Storage Access Framework / sharing helpers shared by every screen. */
public abstract class BaseActivity extends Activity {

    protected Ui.Pal pal;
    protected final Handler main = new Handler(Looper.getMainLooper());
    private boolean wasDark;

    public interface Writer { void write(OutputStream os) throws Exception; }
    public interface Reader { String read(InputStream in) throws Exception; }
    public interface UriCallback { void got(Uri uri); }

    private static final int RC_CREATE = 41, RC_OPEN = 42, RC_TREE = 43, RC_IMAGE = 44, RC_CAMERA = 45;
    private Writer pendingWriter;
    private Reader pendingReader;
    private UriCallback pendingTree, pendingImage;

    // ---- app lock state (process-wide)
    private static int started;
    private static long backgroundAt;
    static boolean unlocked;
    /** Set while we are away in a picker/camera/share sheet we launched, so returning doesn't re-lock. */
    static boolean externalAction;
    private View lockView;

    @Override protected void onCreate(Bundle b) {
        Ui.applyTheme(this);
        super.onCreate(b);
        pal = Ui.pal(this);
        wasDark = pal.dark;
        Ui.styleBars(this, pal);
    }

    @Override protected void onStart() {
        super.onStart();
        started++;
        if (Prefs.lockOn(this)) {
            long away = backgroundAt == 0 ? 0 : System.currentTimeMillis() - backgroundAt;
            boolean expired = backgroundAt != 0 && !externalAction && away >= Prefs.lockAfter(this) * 60_000L;
            if (!unlocked || expired) { unlocked = false; showLock(); }
        }
        externalAction = false;
        backgroundAt = 0;
    }

    @Override protected void onStop() {
        super.onStop();
        started--;
        if (started <= 0 && !isChangingConfigurations()) backgroundAt = System.currentTimeMillis();
    }

    @Override protected void onResume() {
        super.onResume();
        if (Ui.isDark(this) != wasDark) recreate();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (Ui.isDark(this) != wasDark) recreate();
    }

    protected void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    protected AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, pal.dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    protected boolean isLocked() { return lockView != null; }

    // =====================================================================
    // App lock
    // =====================================================================

    private void showLock() {
        if (lockView != null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(pal.bg);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int d = Ui.dp(this, 1);
        col.setPadding(32 * d, 0, 32 * d, 0);

        TextView t = new TextView(this);
        t.setText("Jot is locked");
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(pal.text);
        t.setGravity(Gravity.CENTER);
        col.addView(t);

        final EditText pin = new EditText(this);
        pin.setHint("PIN");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setGravity(Gravity.CENTER);
        pin.setTextSize(22);
        pin.setTextColor(pal.text);
        pin.setHintTextColor(pal.faint);
        pin.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pin.setBackground(Ui.round(pal.card, 14 * d, pal.border, d));
        pin.setPadding(16 * d, 12 * d, 16 * d, 12 * d);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(220 * d, -2);
        plp.setMargins(0, 24 * d, 0, 8 * d);
        col.addView(pin, plp);

        final TextView err = new TextView(this);
        err.setTextColor(pal.danger);
        err.setTextSize(13);
        col.addView(err);

        TextView unlock = new TextView(this);
        unlock.setText("Unlock");
        unlock.setTextColor(pal.onAccent);
        unlock.setTextSize(16);
        unlock.setGravity(Gravity.CENTER);
        unlock.setBackground(Ui.ripple(pal, Ui.round(pal.accent, 24 * d, 0, 0), null));
        unlock.setPadding(28 * d, 12 * d, 28 * d, 12 * d);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.setMargins(0, 12 * d, 0, 0);
        col.addView(unlock, ulp);

        Runnable tryPin = () -> {
            if (Prefs.checkPin(this, pin.getText().toString())) unlock(pin);
            else { err.setText("Wrong PIN"); pin.setText(""); }
        };
        unlock.setOnClickListener(v -> tryPin.run());
        pin.setOnEditorActionListener((v, a, e) -> { tryPin.run(); return true; });

        if (Build.VERSION.SDK_INT >= 28 && Prefs.biometric(this)) {
            TextView fp = new TextView(this);
            fp.setText("Use fingerprint / face");
            fp.setTextColor(pal.accent);
            fp.setTextSize(15);
            fp.setPadding(16 * d, 16 * d, 16 * d, 16 * d);
            fp.setOnClickListener(v -> Bio.prompt(this, () -> unlock(pin)));
            col.addView(fp);
            main.postDelayed(() -> { if (lockView != null) Bio.prompt(this, () -> unlock(pin)); }, 300);
        }

        overlay.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        addContentView(overlay, new ViewGroup.LayoutParams(-1, -1));
        lockView = overlay;
        pin.requestFocus();
    }

    private void unlock(View focus) {
        unlocked = true;
        if (lockView != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            ((ViewGroup) lockView.getParent()).removeView(lockView);
            lockView = null;
            onUnlocked();
        }
    }

    protected void onUnlocked() {}

    @Override public void onBackPressed() {
        if (lockView != null) { moveTaskToBack(true); return; }
        super.onBackPressed();
    }

    /** Biometric prompt, isolated so older Android versions never load the class. */
    static final class Bio {
        static void prompt(Activity a, Runnable ok) {
            if (Build.VERSION.SDK_INT < 28) return;
            try {
                android.hardware.biometrics.BiometricPrompt bp = new android.hardware.biometrics.BiometricPrompt.Builder(a)
                        .setTitle("Unlock Jot")
                        .setNegativeButton("Use PIN", a.getMainExecutor(), (dlg, w) -> {})
                        .build();
                bp.authenticate(new CancellationSignal(), a.getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) { ok.run(); }
                        });
            } catch (Exception ignored) {}
        }
    }

    // =====================================================================
    // Files
    // =====================================================================

    /** Asks the user where to save a file, then runs the writer on a background thread. */
    public void saveAs(String name, String mime, Writer w) {
        pendingWriter = w;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        launch(i, RC_CREATE, "No file picker available");
    }

    private void launch(Intent i, int rc, String failMsg) {
        try { externalAction = true; startActivityForResult(i, rc); }
        catch (Exception e) { externalAction = false; toast(failMsg); }
    }

    private OutputStream openOut(Uri uri) throws Exception {
        try { return getContentResolver().openOutputStream(uri, "wt"); }
        catch (Exception e) { return getContentResolver().openOutputStream(uri, "w"); }
    }

    public void openFile(Reader r) {
        pendingReader = r;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        launch(i, RC_OPEN, "No file picker available");
    }

    public void pickFolder(UriCallback cb) {
        pendingTree = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        launch(i, RC_TREE, "No folder picker available");
    }

    public void pickImage(UriCallback cb) {
        pendingImage = cb;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        launch(Intent.createChooser(i, "Choose an image"), RC_IMAGE, "No gallery app available");
    }

    public void takePhoto(UriCallback cb) {
        pendingImage = cb;
        File f = FilesProvider.fileFor(this, "camera", "capture.jpg");
        f.delete();
        Uri out = FilesProvider.uriFor("camera", "capture.jpg");
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, out);
        i.setClipData(ClipData.newRawUri("photo", out));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launch(i, RC_CAMERA, "No camera app available");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;
        if (req == RC_CAMERA && pendingImage != null) {
            UriCallback cb = pendingImage; pendingImage = null;
            File f = FilesProvider.fileFor(this, "camera", "capture.jpg");
            if (f.exists() && f.length() > 0) cb.got(Uri.fromFile(f));
            else toast("The camera didn't return a photo");
            return;
        }
        if (data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == RC_CREATE && pendingWriter != null) {
            final Writer w = pendingWriter; pendingWriter = null;
            new Thread(() -> {
                String msg;
                try (OutputStream os = openOut(uri)) {
                    w.write(os);
                    msg = "Saved";
                } catch (Throwable t) {
                    msg = "Export failed: " + t.getMessage();
                }
                final String m = msg;
                main.post(() -> toast(m));
            }).start();
        } else if (req == RC_OPEN && pendingReader != null) {
            final Reader r = pendingReader; pendingReader = null;
            new Thread(() -> {
                String msg;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    msg = r.read(in);
                } catch (Throwable t) {
                    msg = "Couldn't read that file: " + t.getMessage();
                }
                final String m = msg;
                main.post(() -> { if (m != null) toast(m); onDataChanged(); });
            }).start();
        } else if (req == RC_TREE && pendingTree != null) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            UriCallback cb = pendingTree; pendingTree = null;
            cb.got(uri);
        } else if (req == RC_IMAGE && pendingImage != null) {
            UriCallback cb = pendingImage; pendingImage = null;
            cb.got(uri);
        }
    }

    /** Called after an import changed stored notes. */
    protected void onDataChanged() {}

    // =====================================================================
    // Export & share
    // =====================================================================

    /** Shows the export-format chooser for a note. */
    public void exportDialog(Note n) {
        String[] items = {"Markdown (.md) — text only", "Plain text (.txt) — text only",
                "PDF (.pdf) — with handwriting & images", "Word (.docx) — with handwriting & images"};
        dialog().setTitle("Export note").setItems(items, (d, which) ->
                saveAs(Exporter.fileName(n, which), Exporter.MIME[which], os -> Exporter.write(this, n, which, os))).show();
    }

    /** Share a note to another app as text or as a PDF. */
    public void shareDialog(Note n) {
        String[] items = {"As text", "As PDF (with handwriting & images)"};
        dialog().setTitle("Share note").setItems(items, (d, which) -> {
            if (which == 0) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                if (!n.title.trim().isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, n.title.trim());
                i.putExtra(Intent.EXTRA_TEXT, Exporter.markdown(n).trim());
                startShare(i);
            } else {
                new Thread(() -> {
                    try {
                        String name = Exporter.fileName(n, Exporter.PDF);
                        File f = FilesProvider.fileFor(this, "share", name);
                        try (OutputStream os = new FileOutputStream(f)) { Exporter.write(this, n, Exporter.PDF, os); }
                        Uri uri = FilesProvider.uriFor("share", name);
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("application/pdf");
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        i.setClipData(ClipData.newRawUri(name, uri));
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        main.post(() -> startShare(i));
                    } catch (Exception e) {
                        main.post(() -> toast("Couldn't make the PDF: " + e.getMessage()));
                    }
                }).start();
            }
        }).show();
    }

    private void startShare(Intent i) {
        try { externalAction = true; startActivity(Intent.createChooser(i, "Share")); }
        catch (Exception e) { externalAction = false; toast("No app to share with"); }
    }

    /** Small helper for building rows with an icon on the left. */
    protected ImageView smallIcon(int icon, int color) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Ui.Icon(icon, color, Ui.dp(this, 1.8f)));
        return iv;
    }
}
