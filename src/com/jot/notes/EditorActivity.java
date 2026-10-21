package com.jot.notes;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EditorActivity extends BaseActivity implements SketchView.Listener {

    private Note note;
    private boolean existed, dirty, readMode;
    private LinearLayout root;
    private ScrollView scroll;
    private LinearLayout blocksBox;
    private EditText titleEt;
    private TextView metaTv;
    private ImageView pinBtn, modeBtn;
    private FrameLayout bottom;
    private LinearLayout bottomCol;
    private View formatBar, sketchBar;
    private final List<View> blockViews = new ArrayList<>();
    private EditText focusedText;
    private SketchView activeSketch;
    private boolean styling;

    // sketch bar widgets we update
    private final List<ImageView> toolButtons = new ArrayList<>();
    private final List<View> colorDots = new ArrayList<>();
    private TextView sizeBtn, smoothBtn, paperBtn;
    private final List<View> selectionOnly = new ArrayList<>();

    private static final int[] INK = {0, 0xFFD84343, 0xFF2F6FD8, 0xFF2E9A5B, 0xFF8A4FD8, 0xFFE0832F};
    private static final int[] MARK = {0xFFFFD54F, 0xFFFF8A80, 0xFF82B1FF, 0xFFB9F6CA, 0xFFE1BEE7, 0xFFFFCC80};

    private final Runnable saveRun = this::saveNow;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadPenPrefs();
        Intent in = getIntent();
        String id = in.getStringExtra("id");
        note = Store.load(this, id);
        existed = note != null;
        if (note == null) {
            note = Note.create();
            String f = in.getStringExtra("folder");
            if (f != null) note.folder = f;
        }
        buildUi();
        buildBlocks();
        handleIncoming(in);
    }

    private void loadPenPrefs() {
        SketchView.smoothing = Prefs.smoothing(this);
        SketchView.palmRejection = Prefs.palmRejection(this);
        SketchView.shapeSnap = Prefs.shapeSnap(this);
        SketchView.partialEraser = Prefs.partialEraser(this);
        if (!Prefs.pressure(this)) SketchView.pressureMode = false;
    }

    @Override protected void onResume() {
        super.onResume();
        loadPenPrefs();
        if (bottom != null) rebuildBars(); // toolbar layout may have changed in Settings
    }

    /** New note from a launcher shortcut or another app's Share menu. */
    private void handleIncoming(Intent in) {
        String action = in.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            String subject = in.getStringExtra(Intent.EXTRA_SUBJECT);
            CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (subject != null && note.title.isEmpty()) { note.title = subject.trim(); titleEt.setText(note.title); }
            if (text != null) {
                Note.NBlock first = note.blocks.get(0);
                first.text = text.toString();
                markDirty();
                buildBlocks();
            }
            List<Uri> uris = new ArrayList<>();
            if (Intent.ACTION_SEND.equals(action)) {
                Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            } else {
                ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) uris.addAll(list);
            }
            if (!uris.isEmpty()) importImages(uris);
            return;
        }
        String start = in.getStringExtra("start");
        if ("hand".equals(start)) { main.postDelayed(() -> insertSketch(Note.BG_LINED), 150); return; }
        if ("canvas".equals(start)) { main.postDelayed(() -> insertSketch(Note.BG_BLANK), 150); return; }
        if (!existed) main.postDelayed(() -> {
            EditText first = firstText();
            if (first != null && !isLocked()) { first.requestFocus(); showKeyboard(first); }
        }, 250);
    }

    // =====================================================================
    // Layout
    // =====================================================================

    private int dp(float v) { return Ui.dp(this, v); }

    private int noteBg() { return Ui.noteBg(pal, note.color, pal.bg); }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(4), dp(4), 0);
        top.addView(Ui.iconButton(this, pal, Ui.I_BACK, pal.text, "Back", v -> onBackPressed()), sq(48));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        pinBtn = Ui.iconButton(this, pal, Ui.I_PIN, pal.sub, "Pin", v -> togglePin());
        top.addView(pinBtn, sq(48));
        modeBtn = Ui.iconButton(this, pal, Ui.I_EYE, pal.sub, "Reading view", v -> setReadMode(!readMode));
        top.addView(modeBtn, sq(48));
        top.addView(Ui.iconButton(this, pal, Ui.I_MORE, pal.sub, "More", this::moreMenu), sq(48));
        root.addView(top);
        updatePin();

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), 0);

        titleEt = new EditText(this);
        titleEt.setBackground(null);
        titleEt.setHint("Title");
        titleEt.setHintTextColor(pal.faint);
        titleEt.setTextColor(pal.text);
        titleEt.setTextSize(25);
        titleEt.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleEt.setPadding(0, dp(6), 0, dp(2));
        titleEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleEt.setHorizontallyScrolling(false);
        titleEt.setMaxLines(4);
        titleEt.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        titleEt.setText(note.title);
        titleEt.setOnEditorActionListener((v, a, e) -> {
            EditText first = firstText();
            if (first != null) { first.requestFocus(); first.setSelection(0); }
            return true;
        });
        titleEt.setOnFocusChangeListener((v, has) -> { if (has) { focusedText = null; deactivateSketch(); } });
        titleEt.addTextChangedListener(new Watcher() {
            @Override public void afterTextChanged(Editable s) {
                String t = s.toString().replace('\n', ' ');
                if (!t.equals(note.title)) { note.title = t; markDirty(); }
            }
        });
        content.addView(titleEt, new LinearLayout.LayoutParams(-1, -2));

        metaTv = new TextView(this);
        metaTv.setTextSize(12);
        metaTv.setTextColor(pal.faint);
        metaTv.setPadding(0, 0, 0, dp(10));
        content.addView(metaTv);
        updateMeta();

        blocksBox = new LinearLayout(this);
        blocksBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(blocksBox, new LinearLayout.LayoutParams(-1, -2));

        View tail = new View(this);
        tail.setMinimumHeight(dp(220));
        tail.setOnClickListener(v -> {
            if (readMode) return;
            if (activeSketch != null) { deactivateSketch(); return; }
            EditText last = lastText();
            if (last != null) { last.requestFocus(); last.setSelection(last.length()); showKeyboard(last); }
        });
        content.addView(tail, new LinearLayout.LayoutParams(-1, 0, 1));

        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        bottom = new FrameLayout(this);
        bottom.setBackgroundColor(pal.card);
        View line = new View(this);
        line.setBackgroundColor(pal.border);
        bottomCol = new LinearLayout(this);
        bottomCol.setOrientation(LinearLayout.VERTICAL);
        bottomCol.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
        bottomCol.addView(bottom, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(bottomCol);
        rebuildBars();

        applyNoteColor();
        setContentView(root);
    }

    private LinearLayout.LayoutParams sq(int v) { return new LinearLayout.LayoutParams(dp(v), dp(v)); }

    private void applyNoteColor() {
        int bg = noteBg();
        root.setBackgroundColor(bg);
        getWindow().setStatusBarColor(bg);
        getWindow().getDecorView().setBackgroundColor(bg);
    }

    private void rebuildBars() {
        bottom.removeAllViews();
        formatBar = buildFormatBar();
        sketchBar = buildSketchBar();
        bottom.addView(formatBar, new FrameLayout.LayoutParams(-1, -1));
        bottom.addView(sketchBar, new FrameLayout.LayoutParams(-1, -1));
        showBars();
    }

    private void updatePin() {
        pinBtn.setImageDrawable(new Ui.Icon(note.pinned ? Ui.I_PIN_ON : Ui.I_PIN, note.pinned ? pal.accent : pal.sub, dp(2)));
        pinBtn.setContentDescription(note.pinned ? "Unpin" : "Pin");
    }

    private void updateMeta() {
        StringBuilder sb = new StringBuilder();
        sb.append(existed || dirty
                ? "Edited " + DateUtils.formatDateTime(this, note.updated, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH)
                : "New note");
        String plain = Md.toPlain(note.markdownBody()).trim();
        int words = plain.isEmpty() ? 0 : plain.split("\\s+").length;
        int chars = plain.replaceAll("\\s", "").length();
        sb.append("  ·  ").append(words).append(words == 1 ? " word" : " words")
          .append("  ·  ").append(chars).append(chars == 1 ? " character" : " characters");
        if (!note.folder.isEmpty()) sb.append("  ·  ").append(note.folder);
        metaTv.setText(sb.toString());
    }

    // =====================================================================
    // Blocks
    // =====================================================================

    private void buildBlocks() {
        blocksBox.removeAllViews();
        blockViews.clear();
        focusedText = null;
        activeSketch = null;
        for (int i = 0; i < note.blocks.size(); i++) {
            Note.NBlock b = note.blocks.get(i);
            View v;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (b.kind == Note.SKETCH) {
                SketchView sv = new SketchView(this, b, pal, this);
                sv.setReadOnly(readMode);
                lp.setMargins(0, dp(8), 0, dp(8));
                v = sv;
            } else if (b.kind == Note.IMAGE) {
                v = imageView(b);
                lp.setMargins(0, dp(8), 0, dp(8));
            } else {
                v = readMode ? readView(b) : textEditor(b);
            }
            blocksBox.addView(v, lp);
            blockViews.add(v);
        }
        showBars();
    }

    private View imageView(Note.NBlock b) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription("Image");
        final float radius = dp(12);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        iv.setClipToOutline(true);
        int w = getResources().getDisplayMetrics().widthPixels;
        // reserve the right height before the bitmap arrives
        iv.setMinimumHeight(Math.min(dp(600), Math.round((w - dp(40)) * Math.max(1, b.imgH) / (float) Math.max(1, b.imgW))));
        iv.setBackgroundColor(pal.card);
        final Context app = getApplicationContext();
        new Thread(() -> {
            Bitmap bm = Images.load(app, b.img, Math.min(w, 1600));
            main.post(() -> {
                if (bm != null) { iv.setImageBitmap(bm); iv.setMinimumHeight(0); iv.setBackground(null); }
                else iv.setContentDescription("Missing image");
            });
        }).start();
        iv.setOnClickListener(v -> {
            if (readMode) return;
            PopupMenu pm = new PopupMenu(this, v, Gravity.CENTER);
            pm.getMenu().add(0, 1, 0, "Remove image");
            pm.setOnMenuItemClickListener(item -> { removeBlock(b); return true; });
            pm.show();
        });
        return iv;
    }

    private TextView readView(Note.NBlock b) {
        TextView tv = new TextView(this);
        styleBody(tv);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(0);
        renderRead(tv, b);
        return tv;
    }

    private void renderRead(TextView tv, Note.NBlock b) {
        tv.setText(MdStyle.render(b.text, pal, getResources().getDisplayMetrics().density, off -> {
            if (off < 0 || off >= b.text.length()) return;
            char c = b.text.charAt(off);
            b.text = b.text.substring(0, off) + (c == ' ' ? 'x' : ' ') + b.text.substring(off + 1);
            markDirty();
            renderRead(tv, b);
        }));
    }

    private void styleBody(TextView tv) {
        tv.setTextColor(pal.text);
        tv.setTextSize(16.5f);
        tv.setLineSpacing(0, 1.25f);
        tv.setPadding(0, dp(2), 0, dp(2));
        tv.setLinkTextColor(pal.accent);
    }

    private TextHistory history(EditText et) {
        Object t = et.getTag();
        return t instanceof TextHistory ? (TextHistory) t : null;
    }

    private EditText textEditor(Note.NBlock b) {
        EditText et = new EditText(this);
        et.setBackground(null);
        styleBody(et);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setHintTextColor(pal.faint);
        if (note.blocks.size() == 1) et.setHint("Start writing…");
        et.setText(b.text);
        MdStyle.live(et.getText(), pal);
        final TextHistory hist = new TextHistory(et);
        et.setTag(hist);
        et.addTextChangedListener(hist);
        final int[] pendingEnter = {-1};
        final int[] downCheck = {-1};
        et.addTextChangedListener(new Watcher() {
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                pendingEnter[0] = (!styling && !hist.applying && count == 1 && before == 0 && start < s.length() && s.charAt(start) == '\n') ? start : -1;
            }
            @Override public void afterTextChanged(Editable e) {
                if (styling) return;
                styling = true;
                try {
                    if (pendingEnter[0] >= 0) { continueList(e, pendingEnter[0]); pendingEnter[0] = -1; }
                    MdStyle.live(e, pal);
                } finally { styling = false; }
                String t = e.toString();
                if (!t.equals(b.text)) { b.text = t; markDirty(); }
            }
        });
        et.setOnFocusChangeListener((v, has) -> {
            if (has) { focusedText = et; deactivateSketch(); }
        });
        et.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN || !ev.isCtrlPressed()) return false;
            switch (code) {
                case KeyEvent.KEYCODE_Z: if (ev.isShiftPressed()) hist.redo(); else hist.undo(); return true;
                case KeyEvent.KEYCODE_Y: hist.redo(); return true;
                case KeyEvent.KEYCODE_B: wrap("**", "**"); return true;
                case KeyEvent.KEYCODE_I: wrap("*", "*"); return true;
                case KeyEvent.KEYCODE_U: wrap("<u>", "</u>"); return true;
            }
            return false;
        });
        et.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                downCheck[0] = -1;
                if (et.getLayout() == null) return false;
                int off = et.getOffsetForPosition(ev.getX(), ev.getY());
                int co = checkAt(et.getText().toString(), off);
                if (co >= 0) { downCheck[0] = co; return true; }
                return false;
            }
            if (downCheck[0] >= 0) {
                if (act == MotionEvent.ACTION_UP) {
                    Editable e = et.getText();
                    int co = downCheck[0];
                    if (co < e.length()) e.replace(co, co + 1, e.charAt(co) == ' ' ? "x" : " ");
                    v.performClick();
                }
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) downCheck[0] = -1;
                return true;
            }
            return false;
        });
        return et;
    }

    /** If 'off' lands on a task checkbox, returns the index of its ' '/'x' char. */
    private static int checkAt(String s, int off) {
        if (off < 0 || off > s.length()) return -1;
        int ls = s.lastIndexOf('\n', off - 1) + 1;
        int le = s.indexOf('\n', ls);
        if (le < 0) le = s.length();
        Md.Line l = Md.classify(s, ls, le, false);
        if (l.type != Md.TASK) return -1;
        return (off >= l.checkOffset - 2 && off <= l.checkOffset + 2) ? l.checkOffset : -1;
    }

    /** Auto-continues lists when Enter is pressed; Enter on an empty item ends the list. */
    private void continueList(Editable e, int nl) {
        String s = e.toString();
        int ls = s.lastIndexOf('\n', nl - 1) + 1;
        if (nl < ls) return;
        String line = s.substring(ls, nl);
        Md.Line l = Md.classify(line, 0, line.length(), false);
        if (l.type != Md.BULLET && l.type != Md.NUMBER && l.type != Md.TASK && l.type != Md.QUOTE) return;
        if (insideFence(s, ls)) return;
        if (line.substring(Math.min(l.contentStart, line.length())).trim().isEmpty()) {
            e.delete(ls, nl + 1);
            return;
        }
        String indent = line.substring(0, l.prefixStart);
        String prefix;
        switch (l.type) {
            case Md.BULLET: prefix = line.substring(0, l.contentStart); break;
            case Md.TASK: prefix = indent + line.charAt(l.prefixStart) + " [ ] "; break;
            case Md.NUMBER: {
                long n;
                try { n = Long.parseLong(l.number) + 1; } catch (Exception ex) { n = 1; }
                char sep = line.charAt(l.prefixStart + l.number.length());
                prefix = indent + n + sep + " ";
                break;
            }
            default: prefix = "> ";
        }
        e.insert(nl + 1, prefix);
    }

    private static boolean insideFence(String s, int pos) {
        boolean in = false;
        for (Md.Line l : Md.parseLines(s.substring(0, pos))) if (l.type == Md.FENCE) in = !in;
        return in;
    }

    private EditText firstText() {
        for (View v : blockViews) if (v instanceof EditText) return (EditText) v;
        return null;
    }

    private EditText lastText() {
        for (int i = blockViews.size() - 1; i >= 0; i--) if (blockViews.get(i) instanceof EditText) return (EditText) blockViews.get(i);
        return null;
    }

    private EditText target() {
        if (focusedText != null && blockViews.contains(focusedText)) return focusedText;
        EditText last = lastText();
        if (last != null) { last.requestFocus(); last.setSelection(last.length()); }
        return last;
    }

    /** Inserts a block where the cursor is (splitting the text there). Returns the new block's index. */
    private int insertBlock(Note.NBlock nb) {
        int idx = -1, cursor = 0;
        if (focusedText != null && blockViews.contains(focusedText)) {
            idx = blockViews.indexOf(focusedText);
            cursor = Math.max(0, focusedText.getSelectionEnd());
        } else {
            for (int i = note.blocks.size() - 1; i >= 0; i--) if (note.blocks.get(i).kind == Note.TEXT) { idx = i; break; }
            if (idx >= 0) cursor = note.blocks.get(idx).text.length();
        }
        if (idx < 0) { note.blocks.add(Note.NBlock.text("")); idx = note.blocks.size() - 1; }
        Note.NBlock tb = note.blocks.get(idx);
        cursor = Math.min(cursor, tb.text.length());
        String before = tb.text.substring(0, cursor), after = tb.text.substring(cursor);
        if (before.endsWith("\n")) before = before.substring(0, before.length() - 1);
        if (after.startsWith("\n")) after = after.substring(1);
        tb.text = before;
        note.blocks.add(idx + 1, nb);
        note.blocks.add(idx + 2, Note.NBlock.text(after));
        markDirty();
        View f = getCurrentFocus();
        if (f != null) hideKeyboard(f);
        buildBlocks();
        return idx + 1;
    }

    private void removeBlock(Note.NBlock blk) {
        int i = note.blocks.indexOf(blk);
        if (i < 0) return;
        Note.NBlock prev = i > 0 ? note.blocks.get(i - 1) : null;
        Note.NBlock next = i + 1 < note.blocks.size() ? note.blocks.get(i + 1) : null;
        note.blocks.remove(i);
        if (prev != null && next != null && prev.kind == Note.TEXT && next.kind == Note.TEXT) {
            String a = Note.trimEnd(prev.text), b = next.text;
            prev.text = a.isEmpty() ? b : b.trim().isEmpty() ? a : a + "\n" + b;
            note.blocks.remove(next);
        }
        if (note.blocks.isEmpty() || note.blocks.get(note.blocks.size() - 1).kind != Note.TEXT) note.blocks.add(Note.NBlock.text(""));
        markDirty();
        buildBlocks();
    }

    // =====================================================================
    // Images
    // =====================================================================

    private void insertImageDialog() {
        dialog().setTitle("Insert image").setItems(new String[]{"From gallery / files", "Take a photo"}, (d, w) -> {
            UriCallback cb = uri -> { List<Uri> l = new ArrayList<>(); l.add(uri); importImages(l); };
            if (w == 0) pickImage(cb); else takePhoto(cb);
        }).show();
    }

    private void importImages(List<Uri> uris) {
        final Context app = getApplicationContext();
        toast(uris.size() == 1 ? "Adding image…" : "Adding " + uris.size() + " images…");
        new Thread(() -> {
            List<Object[]> done = new ArrayList<>();
            String err = null;
            for (Uri u : uris) {
                try { done.add(Images.importImage(app, u)); } catch (Exception e) { err = e.getMessage(); }
            }
            final String fe = err;
            main.post(() -> {
                for (Object[] r : done) insertBlock(Note.NBlock.image((String) r[0], (Integer) r[1], (Integer) r[2]));
                if (fe != null) toast("Couldn't add an image: " + fe);
                saveNow();
            });
        }).start();
    }

    // =====================================================================
    // Sketch blocks
    // =====================================================================

    @Override public void onActivate(SketchView v) {
        if (readMode) return;
        if (activeSketch != null && activeSketch != v) activeSketch.setActive(false);
        activeSketch = v;
        v.setActive(true);
        View f = getCurrentFocus();
        if (f != null) { hideKeyboard(f); f.clearFocus(); }
        focusedText = null;
        showBars();
    }

    @Override public void onChanged(SketchView v) { markDirty(); }

    @Override public void onSelectionChanged(SketchView v) { if (v == activeSketch) refreshSketchBar(); }

    private void deactivateSketch() {
        if (activeSketch != null) { activeSketch.setActive(false); activeSketch = null; }
        showBars();
    }

    private void insertSketch(int bg) {
        int i = insertBlock(Note.NBlock.sketch(bg, bg == Note.BG_LINED ? 720 : 1300));
        final SketchView sv = (SketchView) blockViews.get(i);
        onActivate(sv);
        scroll.postDelayed(() -> scroll.smoothScrollTo(0, Math.max(0, sv.getTop() + blocksBox.getTop() - dp(40))), 120);
    }

    private void deleteSketch(SketchView sv) {
        if (sv.block.strokes.isEmpty()) { removeBlock(sv.block); return; }
        dialog().setTitle("Delete this drawing?").setPositiveButton("Delete", (d, w) -> removeBlock(sv.block))
                .setNegativeButton("Cancel", null).show();
    }

    // =====================================================================
    // Bottom bars
    // =====================================================================

    private void showBars() {
        if (bottomCol == null || formatBar == null) return;
        bottomCol.setVisibility(readMode ? View.GONE : View.VISIBLE);
        boolean sk = activeSketch != null;
        formatBar.setVisibility(sk ? View.GONE : View.VISIBLE);
        sketchBar.setVisibility(sk ? View.VISIBLE : View.GONE);
        if (sk) refreshSketchBar();
    }

    private TextView textButton(CharSequence label, String desc, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(pal.text);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(44));
        t.setPadding(dp(6), 0, dp(6), 0);
        t.setContentDescription(desc);
        t.setBackground(Ui.ripple(pal, null, Ui.round(0xFFFFFFFF, dp(10), 0, 0)));
        t.setOnClickListener(l);
        return t;
    }

    private ImageView iconBtn(int icon, String desc, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Ui.Icon(icon, pal.text, dp(1.8f)));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setContentDescription(desc);
        iv.setBackground(Ui.ripple(pal, null, Ui.round(0xFFFFFFFF, dp(10), 0, 0)));
        iv.setOnClickListener(l);
        iv.setMinimumWidth(dp(44));
        return iv;
    }

    private static CharSequence styled(String s, Object span) {
        SpannableString ss = new SpannableString(s);
        ss.setSpan(span, 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private void textUndo(boolean redo) {
        EditText et = focusedText != null && blockViews.contains(focusedText) ? focusedText : null;
        if (et == null) { toast("Tap into the text first"); return; }
        TextHistory h = history(et);
        if (h == null) return;
        if (redo ? !h.canRedo() : !h.canUndo()) { toast(redo ? "Nothing to redo" : "Nothing to undo"); return; }
        if (redo) h.redo(); else h.undo();
    }

    private View formatItem(String id) {
        switch (id) {
            case "undo": return iconBtn(Ui.I_UNDO, "Undo", v -> textUndo(false));
            case "redo": return iconBtn(Ui.I_REDO, "Redo", v -> textUndo(true));
            case "hand": return iconBtn(Ui.I_LINES, "Insert handwriting (lined page)", v -> insertSketch(Note.BG_LINED));
            case "canvas": return iconBtn(Ui.I_SKETCH, "Insert freewrite canvas", v -> insertSketch(Note.BG_BLANK));
            case "image": return iconBtn(Ui.I_IMAGE, "Insert image", v -> insertImageDialog());
            case "bold": return textButton(styled("B", new StyleSpan(Typeface.BOLD)), "Bold", v -> wrap("**", "**"));
            case "italic": return textButton(styled("I", new StyleSpan(Typeface.ITALIC)), "Italic", v -> wrap("*", "*"));
            case "underline": return textButton(styled("U", new UnderlineSpan()), "Underline", v -> wrap("<u>", "</u>"));
            case "strike": return textButton(styled("S", new StrikethroughSpan()), "Strikethrough", v -> wrap("~~", "~~"));
            case "highlight": return textButton(styled(" A ", new BackgroundColorSpan(pal.highlight)), "Highlight", v -> wrap("==", "=="));
            case "heading": return textButton(styled("H", new StyleSpan(Typeface.BOLD)), "Heading", v -> linePrefix(Md.H));
            case "bullet": return textButton("•", "Bullet list", v -> linePrefix(Md.BULLET));
            case "number": return textButton("1.", "Numbered list", v -> linePrefix(Md.NUMBER));
            case "check": return textButton("☑", "Checklist", v -> linePrefix(Md.TASK));
            case "quote": return textButton("❝", "Quote", v -> linePrefix(Md.QUOTE));
            case "code": {
                TextView code = textButton("</>", "Code", v -> wrap("`", "`"));
                code.setTypeface(Typeface.MONOSPACE);
                code.setTextSize(14);
                return code;
            }
            case "rule": return textButton("—", "Divider line", v -> insertRule());
        }
        return null;
    }

    private View buildFormatBar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), 0, dp(6), 0);
        for (String id : Prefs.toolbar(this, "fmtBar", Prefs.FORMAT_ITEMS)) {
            if (id.startsWith("-")) continue;
            View v = formatItem(id);
            if (v != null) row.addView(v, new LinearLayout.LayoutParams(-2, dp(44)));
        }
        hs.addView(row);
        return hs;
    }

    private View buildSketchBar() {
        toolButtons.clear(); colorDots.clear(); selectionOnly.clear();
        sizeBtn = smoothBtn = paperBtn = null;
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));

        // contextual selection actions (only visible while strokes are lasso-selected)
        TextView selDel = textButton("Delete sel.", "Delete selection", v -> { if (activeSketch != null) activeSketch.deleteSelection(); });
        selDel.setTextSize(14);
        selDel.setTextColor(pal.danger);
        TextView selDup = textButton("Duplicate", "Duplicate selection", v -> { if (activeSketch != null) activeSketch.duplicateSelection(); });
        selDup.setTextSize(14);
        selectionOnly.add(selDel); selectionOnly.add(selDup);

        for (String id : Prefs.toolbar(this, "drawBar", Prefs.DRAW_ITEMS)) {
            if (id.startsWith("-")) continue;
            switch (id) {
                case "done": {
                    ImageView done = iconBtn(Ui.I_CHECK, "Done drawing", v -> deactivateSketch());
                    ((Ui.Icon) done.getDrawable()).setColor(pal.accent);
                    row.addView(done, lp);
                    row.addView(selDel, lp);
                    row.addView(selDup, lp);
                    break;
                }
                case "pen": case "marker": case "eraser": case "lasso": {
                    final int tool = id.equals("pen") ? SketchView.PEN : id.equals("marker") ? SketchView.HIGHLIGHTER
                            : id.equals("eraser") ? SketchView.ERASER : SketchView.LASSO;
                    int icon = tool == SketchView.PEN ? Ui.I_PEN : tool == SketchView.HIGHLIGHTER ? Ui.I_MARKER
                            : tool == SketchView.ERASER ? Ui.I_ERASER : Ui.I_LASSO;
                    ImageView b = iconBtn(icon, Prefs.label(Prefs.DRAW_ITEMS, id), v -> selectTool(tool));
                    b.setTag(tool);
                    toolButtons.add(b);
                    row.addView(b, lp);
                    break;
                }
                case "colors":
                    for (int i = 0; i < INK.length; i++) {
                        final int idx = i;
                        View dot = new View(this);
                        dot.setContentDescription("Colour " + (i + 1));
                        dot.setOnClickListener(v -> pickColor(idx));
                        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(26), dp(26));
                        dl.setMargins(dp(5), 0, dp(5), 0);
                        dl.gravity = Gravity.CENTER_VERTICAL;
                        row.addView(dot, dl);
                        colorDots.add(dot);
                    }
                    break;
                case "size":
                    sizeBtn = textButton("M", "Stroke size", v -> cycleSize());
                    sizeBtn.setTextSize(14);
                    sizeBtn.setTypeface(Typeface.DEFAULT_BOLD);
                    row.addView(sizeBtn, lp);
                    break;
                case "smooth":
                    smoothBtn = textButton(smoothLabel(), "Stroke smoothing", v -> {
                        SketchView.smoothing = (SketchView.smoothing + 1) % 3;
                        Prefs.put(this, "smoothing", SketchView.smoothing);
                        smoothBtn.setText(smoothLabel());
                        toast(SketchView.smoothing == 0 ? "Smoothing off" : SketchView.smoothing == 1 ? "Smooth strokes" : "Extra-smooth strokes");
                    });
                    smoothBtn.setTextSize(14);
                    row.addView(smoothBtn, lp);
                    break;
                case "undo": row.addView(iconBtn(Ui.I_UNDO, "Undo", v -> { if (activeSketch != null) activeSketch.undo(); }), lp); break;
                case "redo": row.addView(iconBtn(Ui.I_REDO, "Redo", v -> { if (activeSketch != null) activeSketch.redo(); }), lp); break;
                case "zoom": row.addView(iconBtn(Ui.I_ZOOM, "Reset zoom", v -> { if (activeSketch != null) activeSketch.resetZoom(); }), lp); break;
                case "paper":
                    paperBtn = textButton(paperLabel(), "Paper style", v -> {
                        if (activeSketch != null) { activeSketch.cycleBackground(); paperBtn.setText(paperLabel()); }
                    });
                    paperBtn.setTextSize(14);
                    row.addView(paperBtn, lp);
                    break;
                case "space": row.addView(iconBtn(Ui.I_GROW, "Add space", v -> { if (activeSketch != null) activeSketch.addSpace(); }), lp); break;
                case "trim": {
                    TextView trim = textButton("Trim", "Trim empty space", v -> { if (activeSketch != null) activeSketch.trim(); });
                    trim.setTextSize(14);
                    row.addView(trim, lp);
                    break;
                }
                case "svg": {
                    TextView svg = textButton("SVG", "Save drawing as SVG", v -> saveSvg());
                    svg.setTextSize(14);
                    row.addView(svg, lp);
                    break;
                }
                case "clear": {
                    TextView clear = textButton("Clear", "Clear drawing", v -> {
                        if (activeSketch != null && !activeSketch.block.strokes.isEmpty())
                            dialog().setTitle("Clear this drawing?").setMessage("You can undo this.")
                                    .setPositiveButton("Clear", (d, w) -> activeSketch.clearAll()).setNegativeButton("Cancel", null).show();
                    });
                    clear.setTextSize(14);
                    row.addView(clear, lp);
                    break;
                }
                case "delete": {
                    ImageView del = iconBtn(Ui.I_TRASH, "Delete drawing", v -> { if (activeSketch != null) deleteSketch(activeSketch); });
                    ((Ui.Icon) del.getDrawable()).setColor(pal.danger);
                    row.addView(del, lp);
                    break;
                }
            }
        }
        if (selDel.getParent() == null) { row.addView(selDel, 0, lp); row.addView(selDup, 1, lp); }
        hs.addView(row);
        return hs;
    }

    private void selectTool(int tool) {
        if (tool == SketchView.ERASER && SketchView.tool == SketchView.ERASER) {
            SketchView.partialEraser = !SketchView.partialEraser;
            Prefs.put(this, "partialEraser", SketchView.partialEraser);
            toast(SketchView.partialEraser ? "Eraser: partial (rub out bits)" : "Eraser: whole strokes");
        }
        if (tool != SketchView.LASSO && activeSketch != null) activeSketch.clearSelection();
        SketchView.tool = tool;
        refreshSketchBar();
    }

    private void pickColor(int idx) {
        if (activeSketch != null && activeSketch.hasSelection()) {
            activeSketch.recolorSelection(INK[idx]);
            return;
        }
        if (SketchView.tool == SketchView.HIGHLIGHTER) SketchView.markerColor = MARK[idx];
        else {
            SketchView.penColor = INK[idx];
            if (SketchView.tool != SketchView.PEN) SketchView.tool = SketchView.PEN;
        }
        refreshSketchBar();
    }

    private static String smoothLabel() {
        return SketchView.smoothing == 0 ? "Raw" : SketchView.smoothing == 1 ? "Smooth" : "Smooth+";
    }

    private String paperLabel() {
        int bg = activeSketch != null ? activeSketch.block.bg : Note.BG_LINED;
        return bg == Note.BG_LINED ? "Lined" : bg == Note.BG_GRID ? "Grid" : "Blank";
    }

    private void refreshSketchBar() {
        for (ImageView b : toolButtons) {
            boolean on = SketchView.tool == (Integer) b.getTag();
            ((Ui.Icon) b.getDrawable()).setColor(on ? pal.onAccent : pal.text);
            b.setBackground(Ui.ripple(pal, on ? Ui.round(pal.accent, dp(10), 0, 0) : null, Ui.round(0xFFFFFFFF, dp(10), 0, 0)));
        }
        boolean marker = SketchView.tool == SketchView.HIGHLIGHTER;
        boolean sel = activeSketch != null && activeSketch.hasSelection();
        for (int i = 0; i < colorDots.size(); i++) {
            int c = marker ? MARK[i] : (INK[i] == 0 ? pal.text : INK[i]);
            boolean on = !sel && (marker ? SketchView.markerColor == MARK[i] : (SketchView.tool == SketchView.PEN && SketchView.penColor == INK[i]));
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(dp(on ? 3 : 1), on ? pal.accent : pal.border);
            colorDots.get(i).setBackground(g);
            colorDots.get(i).setAlpha(SketchView.tool == SketchView.ERASER && !sel ? 0.4f : 1f);
        }
        for (View v : selectionOnly) v.setVisibility(sel ? View.VISIBLE : View.GONE);
        if (sizeBtn != null) {
            if (SketchView.pressureMode && !marker) sizeBtn.setText("P");
            else {
                float w = marker ? SketchView.markerWidth : SketchView.penWidth;
                float[] sizes = marker ? new float[]{14, 22, 34} : new float[]{2.5f, 4, 7.5f};
                sizeBtn.setText(w <= sizes[0] ? "S" : w <= sizes[1] ? "M" : "L");
            }
        }
        if (smoothBtn != null) smoothBtn.setText(smoothLabel());
        if (paperBtn != null) paperBtn.setText(paperLabel());
    }

    private void cycleSize() {
        if (SketchView.tool == SketchView.HIGHLIGHTER) {
            float w = SketchView.markerWidth;
            SketchView.markerWidth = w <= 14 ? 22 : w <= 22 ? 34 : 14;
        } else {
            boolean pressureOk = Prefs.pressure(this);
            float w = SketchView.penWidth;
            if (SketchView.pressureMode) { SketchView.pressureMode = false; SketchView.penWidth = 2.5f; }
            else if (w <= 2.5f) SketchView.penWidth = 4;
            else if (w <= 4) SketchView.penWidth = 7.5f;
            else if (pressureOk) { SketchView.pressureMode = true; toast("Pressure: press harder for thicker lines"); }
            else SketchView.penWidth = 2.5f;
            if (SketchView.tool != SketchView.PEN && SketchView.tool != SketchView.HIGHLIGHTER) SketchView.tool = SketchView.PEN;
        }
        refreshSketchBar();
    }

    private void saveSvg() {
        if (activeSketch == null || activeSketch.block.strokes.isEmpty()) { toast("Nothing drawn yet"); return; }
        final Note.NBlock b = activeSketch.block;
        String base = Exporter.fileName(note, Exporter.TXT);
        base = base.substring(0, base.length() - 4);
        saveAs(base + " - drawing.svg", "image/svg+xml", os -> os.write(Exporter.svg(b, false).getBytes(Store.UTF8)));
    }

    // =====================================================================
    // Text formatting
    // =====================================================================

    private void wrap(String open, String close) {
        EditText et = target();
        if (et == null) return;
        Editable e = et.getText();
        int a = Math.min(et.getSelectionStart(), et.getSelectionEnd());
        int b = Math.max(et.getSelectionStart(), et.getSelectionEnd());
        if (a < 0) { a = b = e.length(); }
        if (a == b) {
            int s = a, t = b;
            while (s > 0 && Character.isLetterOrDigit(e.charAt(s - 1))) s--;
            while (t < e.length() && Character.isLetterOrDigit(e.charAt(t))) t++;
            if (t > s && (s < a || t > b)) { a = s; b = t; }
        }
        int ol = open.length(), cl = close.length();
        if (open.equals("*") || open.equals("**")) {
            int rb = 0, ra = 0;
            while (a - rb - 1 >= 0 && e.charAt(a - rb - 1) == '*') rb++;
            while (b + ra < e.length() && e.charAt(b + ra) == '*') ra++;
            boolean italic = rb % 2 == 1 && ra % 2 == 1;
            boolean bold = rb >= 2 && ra >= 2;
            boolean present = open.length() == 1 ? italic : bold;
            if (present && a != b) {
                e.delete(b, b + ol);
                e.delete(a - ol, a);
                et.setSelection(a - ol, b - ol);
                return;
            }
        } else if (a >= ol && b + cl <= e.length() && e.subSequence(a - ol, a).toString().equals(open)
                && e.subSequence(b, b + cl).toString().equals(close)) {
            e.delete(b, b + cl);
            e.delete(a - ol, a);
            et.setSelection(a - ol, b - ol);
            return;
        }
        String sel = e.subSequence(a, b).toString();
        if (sel.length() >= ol + cl && sel.startsWith(open) && sel.endsWith(close)) {
            e.replace(a, b, sel.substring(ol, sel.length() - cl));
            et.setSelection(a, b - ol - cl);
            return;
        }
        e.insert(b, close);
        e.insert(a, open);
        et.setSelection(a + ol, b + ol);
    }

    private void linePrefix(int type) {
        EditText et = target();
        if (et == null) return;
        Editable e = et.getText();
        String s = e.toString();
        int a = Math.max(0, Math.min(et.getSelectionStart(), et.getSelectionEnd()));
        int b = Math.max(0, Math.max(et.getSelectionStart(), et.getSelectionEnd()));
        int ls = a == 0 ? 0 : s.lastIndexOf('\n', a - 1) + 1;
        int le = s.indexOf('\n', b);
        if (le < 0) le = s.length();

        List<int[]> lines = new ArrayList<>();
        int i = ls;
        while (true) {
            int nl = s.indexOf('\n', i);
            int end = (nl < 0 || nl > le) ? le : nl;
            lines.add(new int[]{i, end});
            if (nl < 0 || nl >= le) break;
            i = nl + 1;
        }
        boolean all = true;
        for (int[] r : lines) if (Md.classify(s, r[0], r[1], false).type != type) { all = false; break; }

        int delta = 0;
        for (int k = lines.size() - 1; k >= 0; k--) {
            int[] r = lines.get(k);
            Md.Line l = Md.classify(s, r[0], r[1], false);
            int stripFrom = r[0], stripTo = r[0];
            switch (l.type) {
                case Md.BULLET: case Md.NUMBER: case Md.TASK: stripFrom = l.prefixStart; stripTo = l.contentStart; break;
                case Md.QUOTE: case Md.H: stripTo = l.contentStart; break;
                default: break;
            }
            String repl;
            if (type == Md.H) {
                int lvl = l.type == Md.H ? l.level : 0;
                int next = lvl >= 3 ? 0 : lvl + 1;
                repl = next == 0 ? "" : Md.repeat("#", next) + " ";
            } else if (all) {
                repl = "";
            } else {
                switch (type) {
                    case Md.BULLET: repl = "- "; break;
                    case Md.NUMBER: repl = (k + 1) + ". "; break;
                    case Md.TASK: repl = "- [ ] "; break;
                    default: repl = "> ";
                }
            }
            e.replace(stripFrom, stripTo, repl);
            delta += repl.length() - (stripTo - stripFrom);
        }
        et.setSelection(Math.max(0, Math.min(e.length(), le + delta)));
    }

    private void insertRule() {
        EditText et = target();
        if (et == null) return;
        Editable e = et.getText();
        int p = Math.max(0, et.getSelectionEnd());
        String pre = (p > 0 && e.charAt(p - 1) != '\n') ? "\n" : "";
        String ins = pre + "---\n";
        e.insert(p, ins);
        et.setSelection(Math.min(e.length(), p + ins.length()));
    }

    // =====================================================================
    // Modes, menu, saving
    // =====================================================================

    private void setReadMode(boolean r) {
        readMode = r;
        View f = getCurrentFocus();
        if (f != null) { hideKeyboard(f); f.clearFocus(); }
        titleEt.setFocusable(!r);
        titleEt.setFocusableInTouchMode(!r);
        titleEt.setCursorVisible(!r);
        titleEt.setHint(r ? "" : "Title");
        modeBtn.setImageDrawable(new Ui.Icon(r ? Ui.I_PEN : Ui.I_EYE, r ? pal.accent : pal.sub, dp(2)));
        modeBtn.setContentDescription(r ? "Edit" : "Reading view");
        buildBlocks();
    }

    private void togglePin() {
        note.pinned = !note.pinned;
        updatePin();
        markDirty();
        toast(note.pinned ? "Pinned" : "Unpinned");
    }

    private void moreMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Share…");
        pm.getMenu().add(0, 2, 0, "Export…");
        pm.getMenu().add(0, 3, 0, "Move to folder…");
        pm.getMenu().add(0, 4, 0, "Colour…");
        pm.getMenu().add(0, 5, 0, "Formatting help");
        pm.getMenu().add(0, 6, 0, "Move to trash");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: saveNow(); shareDialog(note); return true;
                case 2: saveNow(); exportDialog(note); return true;
                case 3: NoteDialogs.folder(this, note, () -> { markDirty(); updateMeta(); }); return true;
                case 4: NoteDialogs.color(this, note, () -> { markDirty(); applyNoteColor(); }); return true;
                case 5: help(); return true;
                case 6:
                    main.removeCallbacks(saveRun);
                    if (!note.isEmpty()) { Store.trash(this, note); toast("Moved to trash"); }
                    else if (existed) Store.deleteForever(this, note.id);
                    dirty = false;
                    note = null;
                    finish();
                    return true;
            }
            return false;
        });
        pm.show();
    }

    private void help() {
        dialog().setTitle("Formatting")
                .setMessage("Type markdown or use the toolbar:\n\n"
                        + "**bold**   *italic*   <u>underline</u>\n~~strikethrough~~   ==highlight==   `code`\n\n"
                        + "# Heading  (## smaller, ### smaller)\n- bullet   1. numbered   - [ ] checklist\n> quote   --- divider\n[link text](https://…)\n\n"
                        + "Tap a checkbox to tick it. Enter continues a list; Enter on an empty item ends it.\n\n"
                        + "Handwriting: the lined-page and squiggle buttons insert a drawing where your cursor is. "
                        + "Scroll past it with a finger; tap it to write. A stylus writes immediately. Two fingers zoom. "
                        + "Hold the pen still at the end of a stroke to snap it into a shape. Tap the eraser twice to switch "
                        + "between whole-stroke and partial erasing. Use the lasso to select, then drag to move, drag the dot "
                        + "to resize, or tap a colour to recolour.\n\n"
                        + "Keyboard: Ctrl+B / I / U, Ctrl+Z undo, Ctrl+Y redo.")
                .setPositiveButton("OK", null).show();
    }

    private void markDirty() {
        if (note == null) return;
        dirty = true;
        note.updated = System.currentTimeMillis();
        main.removeCallbacks(saveRun);
        main.postDelayed(saveRun, 700);
    }

    private void saveNow() {
        main.removeCallbacks(saveRun);
        if (note == null || !dirty) return;
        if (note.isEmpty()) {
            if (existed) { Store.deleteForever(this, note.id); existed = false; }
        } else {
            Store.save(this, note);
            existed = true;
        }
        dirty = false;
        if (metaTv != null) updateMeta();
    }

    @Override protected void onPause() {
        boolean changed = dirty;
        saveNow();
        super.onPause();
        if (changed && Store.syncFolder(this) != null) {
            final Context app = getApplicationContext();
            new Thread(() -> { try { Store.syncNow(app); } catch (Exception ignored) {} }).start();
        }
    }

    @Override public void onBackPressed() {
        if (!isLocked() && activeSketch != null) { deactivateSketch(); return; }
        super.onBackPressed();
    }

    private void showKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** TextWatcher with empty defaults. */
    static class Watcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) {}
    }

    @SuppressWarnings("unused")
    private static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
