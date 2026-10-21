package com.jot.notes;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends BaseActivity {

    private boolean trashMode;
    private final List<Note> notes = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();
    private Adapter adapter;
    private EditText search;
    private TextView empty;
    private LinearLayout chips;
    private FrameLayout updateBox;
    private ImageView layoutBtn;
    private String folder = null; // null = all
    private int columns = 1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        trashMode = getIntent().getBooleanExtra("trash", false);
        folder = trashMode ? null : Store.prefs(this).getString("lastFolder", null);
        int d16 = Ui.dp(this, 16);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(pal.bg);
        root.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, trashMode ? 4 : 20), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 2));
        if (trashMode) header.addView(Ui.iconButton(this, pal, Ui.I_BACK, pal.text, "Back", v -> finish()), sq(48));
        TextView title = new TextView(this);
        title.setText(trashMode ? "Trash" : "Jot");
        title.setTextSize(trashMode ? 22 : 28);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(pal.text);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (!trashMode) {
            layoutBtn = Ui.iconButton(this, pal, Ui.I_GRID, pal.sub, "Switch layout", v -> {
                Prefs.put(this, "layout", Prefs.layout(this) == Prefs.LAYOUT_GRID ? Prefs.LAYOUT_LIST : Prefs.LAYOUT_GRID);
                updateLayoutIcon();
                rebuildRows();
            });
            header.addView(layoutBtn, sq(48));
            header.addView(Ui.iconButton(this, pal, Ui.I_SETTINGS, pal.sub, "Settings",
                    v -> startActivity(new Intent(this, SettingsActivity.class))), sq(48));
        }
        header.addView(Ui.iconButton(this, pal, Ui.I_MORE, pal.sub, "More options", this::showMenu), sq(48));
        col.addView(header);

        if (trashMode) {
            TextView info = new TextView(this);
            info.setText("Notes here are deleted forever after " + Store.TRASH_DAYS + " days. Tap a note to restore it.");
            info.setTextColor(pal.sub);
            info.setTextSize(13);
            info.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
            col.addView(info);
        }

        // search
        search = new EditText(this);
        search.setHint(trashMode ? "Search trash" : "Search notes");
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setTextSize(15);
        search.setTextColor(pal.text);
        search.setHintTextColor(pal.faint);
        search.setBackground(Ui.round(pal.card, Ui.dp(this, 14), pal.border, Ui.dp(this, 1)));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        Ui.Icon si = new Ui.Icon(Ui.I_SEARCH, pal.faint, Ui.dp(this, 1.6f));
        si.setBounds(0, 0, Ui.dp(this, 18), Ui.dp(this, 18));
        search.setCompoundDrawables(si, null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(this, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(d16, Ui.dp(this, 6), d16, Ui.dp(this, 6));
        col.addView(search, slp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable s) { rebuildRows(); }
        });

        // folder chips
        if (!trashMode) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            chips = new LinearLayout(this);
            chips.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 4));
            hs.addView(chips);
            col.addView(hs);
            updateBox = new FrameLayout(this);
            col.addView(updateBox);
        }

        FrameLayout listWrap = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 100));
        adapter = new Adapter();
        list.setAdapter(adapter);
        listWrap.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = new TextView(this);
        empty.setTextColor(pal.faint);
        empty.setTextSize(15);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), Ui.dp(this, 80));
        listWrap.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        col.addView(listWrap, new LinearLayout.LayoutParams(-1, 0, 1));

        if (!trashMode) {
            ImageView fab = new ImageView(this);
            fab.setImageDrawable(new Ui.Icon(Ui.I_PLUS, pal.onAccent, Ui.dp(this, 2.4f)));
            fab.setScaleType(ImageView.ScaleType.CENTER);
            GradientDrawable fabBg = new GradientDrawable();
            fabBg.setShape(GradientDrawable.OVAL);
            fabBg.setColor(pal.accent);
            fab.setBackground(Ui.ripple(pal, fabBg, null));
            fab.setElevation(Ui.dp(this, 6));
            fab.setContentDescription("New note");
            fab.setOnClickListener(v -> openNote(null));
            fab.setOnLongClickListener(v -> {
                startActivity(new Intent(this, EditorActivity.class).putExtra("start", "hand").putExtra("folder", folder == null ? "" : folder));
                return true;
            });
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60), Gravity.BOTTOM | Gravity.END);
            flp.setMargins(0, 0, Ui.dp(this, 22), Ui.dp(this, 26));
            root.addView(fab, flp);
        }

        setContentView(root);
        updateLayoutIcon();
    }

    private LinearLayout.LayoutParams sq(int dp) { return new LinearLayout.LayoutParams(Ui.dp(this, dp), Ui.dp(this, dp)); }

    private void updateLayoutIcon() {
        if (layoutBtn == null) return;
        boolean grid = Prefs.layout(this) == Prefs.LAYOUT_GRID;
        layoutBtn.setImageDrawable(new Ui.Icon(grid ? Ui.I_LIST : Ui.I_GRID, pal.sub, Ui.dp(this, 2)));
        layoutBtn.setContentDescription(grid ? "Show as list" : "Show as grid");
    }

    @Override protected void onResume() {
        super.onResume();
        updateLayoutIcon();
        reload();
        new Thread(() -> { Store.housekeeping(getApplicationContext()); }).start();
        if (!trashMode && Store.syncFolder(this) != null) SettingsActivity.syncInBackground(this, false, this::reload);
        if (!trashMode) {
            showUpdateBanner();
            if (UpdateCheck.enabled(this)) UpdateCheck.check(this, false, (latest, newer, err) -> showUpdateBanner());
        }
    }

    /** Small card under the folder chips when a newer release exists on GitHub. */
    private void showUpdateBanner() {
        if (updateBox == null) return;
        updateBox.removeAllViews();
        final String v = UpdateCheck.enabled(this) ? UpdateCheck.pendingUpdate(this) : null;
        if (v == null) return;
        int d = Ui.dp(this, 1);
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(16 * d, 8 * d, 6 * d, 8 * d);
        card.setBackground(Ui.round(pal.card, 14 * d, pal.accent, Math.max(1, d)));
        TextView t = new TextView(this);
        t.setText("Jot " + v + " is available");
        t.setTextColor(pal.text);
        t.setTextSize(15);
        card.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView later = new TextView(this);
        later.setText("Later");
        later.setTextColor(pal.sub);
        later.setTextSize(14);
        later.setPadding(12 * d, 8 * d, 12 * d, 8 * d);
        later.setOnClickListener(x -> { UpdateCheck.dismiss(this, v); showUpdateBanner(); });
        card.addView(later);
        TextView upd = new TextView(this);
        upd.setText("Update");
        upd.setTextColor(pal.onAccent);
        upd.setTextSize(14);
        upd.setTypeface(Typeface.DEFAULT_BOLD);
        upd.setPadding(14 * d, 8 * d, 14 * d, 8 * d);
        upd.setBackground(Ui.ripple(pal, Ui.round(pal.accent, 18 * d, 0, 0), null));
        upd.setOnClickListener(x -> { BaseActivity.externalAction = true; UpdateCheck.openUpdater(this); });
        card.addView(upd);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.setMargins(14 * d, 4 * d, 14 * d, 6 * d);
        updateBox.addView(card, lp);
    }

    @Override protected void onDataChanged() { reload(); }

    private List<String> folderList = new ArrayList<>();

    private void reload() {
        notes.clear();
        List<Note> everything = Store.all(this);
        folderList = Store.folders(this, everything);
        for (Note n : everything) if ((n.trashed != 0) == trashMode) notes.add(n);
        if (trashMode) java.util.Collections.sort(notes, (a, b) -> Long.compare(b.trashed, a.trashed));
        else Store.sort(notes, Prefs.sort(this));
        if (folder != null && !folderList.contains(folder)) folder = null;
        buildChips();
        rebuildRows();
    }

    // ------------------------------------------------------------------ folders

    private void buildChips() {
        if (chips == null) return;
        chips.removeAllViews();
        chips.addView(chip("All", folder == null, v -> selectFolder(null), null));
        for (String f : folderList) {
            chips.addView(chip(f, f.equals(folder), v -> selectFolder(f), v -> { folderMenu(v, f); return true; }));
        }
        chips.addView(chip("+ Folder", false, v -> NoteDialogs.askName(this, "New folder", "", name -> {
            Store.addFolder(this, name);
            if (!folderList.contains(name)) folderList.add(name);
            selectFolder(name);
        }), null));
    }

    private View chip(String label, boolean on, View.OnClickListener l, View.OnLongClickListener ll) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(on ? pal.onAccent : pal.text);
        int d = Ui.dp(this, 1);
        t.setPadding(14 * d, 7 * d, 14 * d, 7 * d);
        t.setBackground(Ui.ripple(pal, Ui.round(on ? pal.accent : pal.card, 18 * d, on ? pal.accent : pal.border, d),
                Ui.round(0xFFFFFFFF, 18 * d, 0, 0)));
        t.setOnClickListener(l);
        if (ll != null) t.setOnLongClickListener(ll);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(4 * d, 4 * d, 4 * d, 4 * d);
        t.setLayoutParams(lp);
        return t;
    }

    private void selectFolder(String f) {
        folder = f;
        Store.prefs(this).edit().putString("lastFolder", f).apply();
        buildChips();
        rebuildRows();
    }

    private void folderMenu(View anchor, String f) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Rename folder");
        pm.getMenu().add(0, 2, 0, "Delete folder (keeps notes)");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                NoteDialogs.askName(this, "Rename folder", f, name -> {
                    Store.renameFolder(this, f, name);
                    if (f.equals(folder)) folder = name;
                    reload();
                    sync();
                });
            } else {
                dialog().setTitle("Delete “" + f + "”?")
                        .setMessage("The folder is removed; its notes stay and move to “All”.")
                        .setPositiveButton("Delete", (d, w) -> {
                            Store.deleteFolder(this, f);
                            if (f.equals(folder)) folder = null;
                            reload();
                            sync();
                        }).setNegativeButton("Cancel", null).show();
            }
            return true;
        });
        pm.show();
    }

    // ------------------------------------------------------------------ list

    private void rebuildRows() {
        rows.clear();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Note> pinned = new ArrayList<>(), others = new ArrayList<>();
        for (Note n : notes) {
            if (!trashMode && folder != null && !folder.equals(n.folder)) continue;
            if (!q.isEmpty() && !(n.title.toLowerCase(Locale.ROOT).contains(q) || n.markdownBody().toLowerCase(Locale.ROOT).contains(q))) continue;
            (n.pinned && !trashMode ? pinned : others).add(n);
        }
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        columns = Prefs.layout(this) == Prefs.LAYOUT_GRID && !trashMode ? (widthDp >= 840 ? 4 : widthDp >= 600 ? 3 : 2) : 1;
        if (!pinned.isEmpty()) { rows.add("Pinned"); addGroup(pinned); if (!others.isEmpty()) rows.add("Notes"); }
        addGroup(others);
        if (trashMode) empty.setText("Trash is empty.");
        else if (notes.isEmpty()) empty.setText("No notes yet.\nTap + to write, list or sketch something.\n(Long-press + for a handwriting page.)");
        else if (folder != null && q.isEmpty()) empty.setText("No notes in “" + folder + "” yet.");
        else empty.setText("Nothing matches your search.");
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void addGroup(List<Note> group) {
        if (columns <= 1) { rows.addAll(group); return; }
        for (int i = 0; i < group.size(); i += columns) rows.add(new ArrayList<>(group.subList(i, Math.min(group.size(), i + columns))));
    }

    private void openNote(String id) {
        Intent i = new Intent(this, EditorActivity.class);
        if (id != null) i.putExtra("id", id);
        else if (folder != null) i.putExtra("folder", folder);
        startActivity(i);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int p) { Object o = rows.get(p); return o instanceof String ? 0 : o instanceof Note ? 1 : 2; }
        @Override public boolean isEnabled(int p) { return false; }

        @SuppressWarnings("unchecked")
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Object o = rows.get(pos);
            if (o instanceof String) {
                TextView h = new TextView(MainActivity.this);
                h.setText(((String) o).toUpperCase(Locale.ROOT));
                h.setTextSize(12);
                h.setLetterSpacing(0.08f);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setTextColor(pal.sub);
                h.setPadding(Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 14), 0, Ui.dp(MainActivity.this, 6));
                return h;
            }
            if (o instanceof Note) return wrap(card((Note) o, false));
            LinearLayout row = new LinearLayout(MainActivity.this);
            List<Note> group = (List<Note>) o;
            for (int i = 0; i < columns; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                if (i < group.size()) row.addView(wrap(card(group.get(i), true)), lp);
                else row.addView(new View(MainActivity.this), lp);
            }
            return row;
        }
    }

    private View wrap(View card) {
        FrameLayout w = new FrameLayout(this);
        int d = Ui.dp(this, 1);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.setMargins(6 * d, 5 * d, 6 * d, 5 * d);
        w.addView(card, lp);
        return w;
    }

    private View card(Note n, boolean grid) {
        int d = Ui.dp(this, 1);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(16 * d, 14 * d, 16 * d, 12 * d);
        int bgc = Ui.noteBg(pal, n.color, pal.card);
        GradientDrawable bg = Ui.round(bgc, 16 * d, n.color == 0 ? pal.border : bgc, d);
        card.setBackground(Ui.ripple(pal, bg, Ui.round(0xFFFFFFFF, 16 * d, 0, 0)));
        if (grid) card.setMinimumHeight(110 * d);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(n.title.trim().isEmpty() ? "Untitled" : n.title.trim());
        t.setTextColor(n.title.trim().isEmpty() ? pal.sub : pal.text);
        t.setTextSize(grid ? 16 : 17);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMaxLines(grid ? 2 : 1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (n.pinned && !trashMode) {
            ImageView pin = new ImageView(this);
            pin.setImageDrawable(new Ui.Icon(Ui.I_PIN_ON, pal.accent, 1.6f * d));
            top.addView(pin, new LinearLayout.LayoutParams(18 * d, 18 * d));
        }
        card.addView(top);

        String plain = Md.toPlain(n.markdownBody()).trim();
        if (plain.length() > 400) plain = plain.substring(0, 400);
        if (!plain.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(plain);
            s.setTextColor(pal.sub);
            s.setTextSize(14);
            s.setMaxLines(grid ? 6 : 3);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setLineSpacing(0, 1.1f);
            s.setPadding(0, 4 * d, 0, 0);
            card.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
        } else if (grid) {
            card.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        }

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, 8 * d, 0, 0);
        TextView date = new TextView(this);
        date.setText(trashMode ? "Deleted " + relative(n.trashed).toLowerCase(Locale.ROOT) : relative(n.updated));
        date.setTextSize(12);
        date.setTextColor(pal.faint);
        date.setSingleLine(true);
        meta.addView(date);
        if (n.hasSketch()) meta.addView(metaIcon(Ui.I_SKETCH));
        if (n.hasImage()) meta.addView(metaIcon(Ui.I_IMAGE));
        if (!n.folder.isEmpty() && folder == null && !grid) {
            TextView f = new TextView(this);
            f.setText(n.folder);
            f.setTextSize(12);
            f.setTextColor(pal.faint);
            f.setSingleLine(true);
            f.setEllipsize(TextUtils.TruncateAt.END);
            f.setPadding(10 * d, 0, 0, 0);
            Ui.Icon fi = new Ui.Icon(Ui.I_FOLDER, pal.faint, 1.3f * d);
            fi.setBounds(0, 0, 14 * d, 14 * d);
            f.setCompoundDrawables(fi, null, null, null);
            f.setCompoundDrawablePadding(4 * d);
            meta.addView(f);
        }
        card.addView(meta);

        if (trashMode) {
            card.setOnClickListener(v -> trashActions(n));
        } else {
            card.setOnClickListener(v -> openNote(n.id));
            card.setOnLongClickListener(v -> { noteMenu(v, n); return true; });
        }
        return card;
    }

    private View metaIcon(int icon) {
        int d = Ui.dp(this, 1);
        ImageView sk = new ImageView(this);
        sk.setImageDrawable(new Ui.Icon(icon, pal.faint, 1.5f * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(16 * d, 16 * d);
        lp.setMargins(10 * d, 0, 0, 0);
        sk.setLayoutParams(lp);
        return sk;
    }

    private String relative(long t) {
        long now = System.currentTimeMillis();
        if (now - t < 60_000) return "Just now";
        if (now - t < 7L * 24 * 3600_000) return DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
        return DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private void saveAndRefresh(Note n) {
        Store.save(this, n);
        reload();
        sync();
    }

    private void sync() { SettingsActivity.syncInBackground(this, false, this::reload); }

    private void noteMenu(View anchor, Note n) {
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.END);
        pm.getMenu().add(0, 1, 0, n.pinned ? "Unpin" : "Pin to top");
        pm.getMenu().add(0, 2, 0, "Move to folder…");
        pm.getMenu().add(0, 3, 0, "Colour…");
        pm.getMenu().add(0, 4, 0, "Share…");
        pm.getMenu().add(0, 5, 0, "Export…");
        pm.getMenu().add(0, 6, 0, "Move to trash");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: n.pinned = !n.pinned; n.updated = System.currentTimeMillis(); saveAndRefresh(n); return true;
                case 2: NoteDialogs.folder(this, n, () -> saveAndRefresh(n)); return true;
                case 3: NoteDialogs.color(this, n, () -> saveAndRefresh(n)); return true;
                case 4: shareDialog(n); return true;
                case 5: exportDialog(n); return true;
                case 6:
                    Store.trash(this, n);
                    reload();
                    sync();
                    toast("Moved to trash");
                    return true;
            }
            return false;
        });
        pm.show();
    }

    private void trashActions(Note n) {
        String name = n.title.trim().isEmpty() ? "Untitled" : n.title.trim();
        dialog().setTitle(name)
                .setItems(new String[]{"Restore", "Delete forever"}, (d, w) -> {
                    if (w == 0) { Store.restore(this, n); reload(); sync(); toast("Restored"); }
                    else dialog().setTitle("Delete forever?").setMessage("“" + name + "” can't be recovered after this.")
                            .setPositiveButton("Delete", (d2, w2) -> { Store.deleteForever(this, n.id); reload(); sync(); })
                            .setNegativeButton("Cancel", null).show();
                }).show();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        if (trashMode) {
            pm.getMenu().add(0, 10, 0, "Empty trash");
        } else {
            pm.getMenu().add(0, 1, 0, "Sort by…");
            pm.getMenu().add(0, 2, 0, "Trash");
            pm.getMenu().add(0, 3, 0, "Settings");
        }
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: {
                    String[] sorts = {"Last edited", "Date created", "Title (A–Z)"};
                    dialog().setTitle("Sort notes by").setSingleChoiceItems(sorts, Prefs.sort(this), (d, w) -> {
                        Prefs.put(this, "sort", w); d.dismiss(); reload();
                    }).show();
                    return true;
                }
                case 2: startActivity(new Intent(this, MainActivity.class).putExtra("trash", true)); return true;
                case 3: startActivity(new Intent(this, SettingsActivity.class)); return true;
                case 10:
                    if (notes.isEmpty()) { toast("Trash is already empty"); return true; }
                    dialog().setTitle("Empty trash?").setMessage(notes.size() + (notes.size() == 1 ? " note" : " notes") + " will be deleted forever.")
                            .setPositiveButton("Empty", (d, w) -> { for (Note n : notes) Store.deleteForever(this, n.id); reload(); sync(); })
                            .setNegativeButton("Cancel", null).show();
                    return true;
            }
            return false;
        });
        pm.show();
    }
}
