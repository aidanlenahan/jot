package com.jot.notes;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

/** Undo/redo for one EditText (Android text fields don't have it built in). Typing is grouped into words. */
final class TextHistory implements TextWatcher {
    private static final class Edit { int start; String before, after; long time; }

    private final EditText et;
    private final List<Edit> undo = new ArrayList<>(), redo = new ArrayList<>();
    boolean applying;
    private String pendingBefore = "";

    TextHistory(EditText et) { this.et = et; }

    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }

    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (applying) return;
        pendingBefore = s.subSequence(start, start + count).toString();
    }

    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (applying) return;
        String after = s.subSequence(start, start + count).toString();
        if (pendingBefore.equals(after)) return;
        long now = System.currentTimeMillis();
        Edit last = undo.isEmpty() ? null : undo.get(undo.size() - 1);
        if (last != null && now - last.time < 1500) {
            // keep typing a word as one step
            if (pendingBefore.isEmpty() && last.before.isEmpty() && start == last.start + last.after.length()
                    && !after.contains("\n") && !last.after.endsWith("\n")
                    && !(endsWithSpace(last.after) && !after.isEmpty() && !Character.isWhitespace(after.charAt(0)))) {
                last.after += after; last.time = now; redo.clear(); return;
            }
            // keep backspacing as one step
            if (after.isEmpty() && last.after.isEmpty() && pendingBefore.length() == 1 && start + 1 == last.start) {
                last.before = pendingBefore + last.before; last.start = start; last.time = now; redo.clear(); return;
            }
        }
        Edit e = new Edit();
        e.start = start; e.before = pendingBefore; e.after = after; e.time = now;
        undo.add(e);
        if (undo.size() > 300) undo.remove(0);
        redo.clear();
    }

    private static boolean endsWithSpace(String s) { return !s.isEmpty() && Character.isWhitespace(s.charAt(s.length() - 1)); }

    @Override public void afterTextChanged(Editable s) {}

    void undo() {
        if (undo.isEmpty()) return;
        Edit e = undo.remove(undo.size() - 1);
        apply(e.start, e.after.length(), e.before);
        redo.add(e);
    }

    void redo() {
        if (redo.isEmpty()) return;
        Edit e = redo.remove(redo.size() - 1);
        apply(e.start, e.before.length(), e.after);
        e.time = 0; // don't merge new typing into a redone step
        undo.add(e);
    }

    private void apply(int start, int len, String text) {
        Editable ed = et.getText();
        int end = Math.min(ed.length(), start + len);
        start = Math.min(start, ed.length());
        applying = true;
        try { ed.replace(start, end, text); }
        finally { applying = false; }
        et.setSelection(Math.min(ed.length(), start + text.length()));
    }
}
