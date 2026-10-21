package com.jot.notes;

import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Colour picker and folder picker shared by the list and the editor. */
final class NoteDialogs {
    private NoteDialogs() {}

    interface Done { void done(); }

    static void color(BaseActivity a, Note n, Done done) {
        int d = Ui.dp(a, 1);
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(5);
        grid.setPadding(16 * d, 16 * d, 16 * d, 0);
        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        for (int i = 0; i < Ui.NOTE_COLOR_NAMES.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(6 * d, 6 * d, 6 * d, 6 * d);
            View dot = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == 0 ? a.pal.card : Ui.noteBg(a.pal, i, a.pal.card));
            g.setStroke(n.color == i ? 3 * d : d, n.color == i ? a.pal.accent : (i == 0 ? a.pal.faint : Ui.noteSwatch(i)));
            dot.setBackground(g);
            cell.addView(dot, new LinearLayout.LayoutParams(40 * d, 40 * d));
            TextView t = new TextView(a);
            t.setText(Ui.NOTE_COLOR_NAMES[i]);
            t.setTextSize(11);
            t.setTextColor(a.pal.sub);
            t.setGravity(Gravity.CENTER);
            cell.addView(t);
            cell.setOnClickListener(v -> {
                n.color = idx;
                n.updated = System.currentTimeMillis();
                if (dlg[0] != null) dlg[0].dismiss();
                done.done();
            });
            grid.addView(cell);
        }
        dlg[0] = a.dialog().setTitle("Note colour").setView(grid).setNegativeButton("Cancel", null).show();
    }

    static void folder(BaseActivity a, Note n, Done done) {
        List<String> folders = Store.folders(a);
        String[] items = new String[folders.size() + 2];
        items[0] = "No folder";
        for (int i = 0; i < folders.size(); i++) items[i + 1] = folders.get(i);
        items[items.length - 1] = "+ New folder…";
        int cur = n.folder.isEmpty() ? 0 : folders.indexOf(n.folder) + 1;
        a.dialog().setTitle("Move to folder").setSingleChoiceItems(items, cur, (dlg, w) -> {
            dlg.dismiss();
            if (w == items.length - 1) {
                askName(a, "New folder", "", name -> {
                    Store.addFolder(a, name);
                    n.folder = name; n.updated = System.currentTimeMillis();
                    done.done();
                });
                return;
            }
            n.folder = w == 0 ? "" : folders.get(w - 1);
            n.updated = System.currentTimeMillis();
            done.done();
        }).show();
    }

    interface NameCb { void got(String name); }

    static void askName(BaseActivity a, String title, String initial, NameCb cb) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setText(initial);
        e.setSelection(initial.length());
        e.setTextColor(a.pal.text);
        e.setHint("Folder name");
        e.setHintTextColor(a.pal.faint);
        LinearLayout box = new LinearLayout(a);
        int d = Ui.dp(a, 1);
        box.setPadding(24 * d, 8 * d, 24 * d, 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        a.dialog().setTitle(title).setView(box).setPositiveButton("OK", (dlg, w) -> {
            String name = e.getText().toString().trim().replace(",", " ");
            if (!name.isEmpty()) cb.got(name);
        }).setNegativeButton("Cancel", null).show();
    }
}
