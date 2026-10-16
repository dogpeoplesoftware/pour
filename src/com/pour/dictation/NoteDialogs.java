package com.pour.dictation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small shared dialogs used from more than one screen. */
public class NoteDialogs {

    /** Callback for a text-input dialog. */
    public interface TextCallback {
        void onText(String text);
    }

    /** A single text field in a dialog; onDone receives the trimmed text
     * (empty string if the user cleared it). */
    public static void textInput(final Activity a, String title, String hint,
                                 String initial, final TextCallback onDone) {
        final EditText input = new EditText(a);
        input.setText(initial == null ? "" : initial);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextColor(Ui.CARD_TEXT);
        input.setHintTextColor(Ui.CARD_MUTED);
        // Underline/accent follows the theme, not the system default.
        input.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        input.selectAll();
        int p = Ui.dp(a, 20);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setPadding(p, Ui.dp(a, 8), p, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("Save",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                onDone.onText(input.getText().toString()
                                        .trim());
                            }
                        })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        // Style after show: the dialog background, title, and buttons
        // must follow the app theme, not the system dialog theme.
        Ui.styleDialog(a, dialog);
    }

    /** Rename a note without opening it; onDone runs after saving. */
    public static void rename(final Activity a, final NotesDb db,
                             final Note n, final Runnable onDone) {
        textInput(a, "Rename piece", null, n.title,
                new TextCallback() {
                    @Override public void onText(String t) {
                        n.title = t.isEmpty() ? "Untitled piece" : t;
                        db.update(n);
                        if (onDone != null) onDone.run();
                    }
                });
    }

    /** Callback after a project (with its first section) is created. */
    public interface ProjectCallback {
        void onProject(long projectId);
    }

    /** One dialog: project name + first section name. A project starts
     * with one section; the writer renames it or adds more whenever the
     * shape of the work becomes clear. */
    public static void newProject(final Activity a, final NotesDb db,
                                  final ProjectCallback cb) {
        newProject(a, db, true, cb);
    }

    /** Create a project. When withSection is true, the dialog asks for
     * a first section name and creates it. When false (sending a note
     * from the Bucket), no section is created — the sent note becomes
     * the project's first section. */
    public static void newProject(final Activity a, final NotesDb db,
                                  final boolean withSection,
                                  final ProjectCallback cb) {
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 20);
        form.setPadding(p, Ui.dp(a, 8), p, 0);
        TextView explainer = new TextView(a);
        if (withSection) {
            explainer.setText("A project starts with one piece. Rename it"
                    + " or add more whenever the shape of the work becomes"
                    + " clear.");
        } else {
            explainer.setText("Name your new project. The piece you are"
                    + " sending becomes its first piece.");
        }
        explainer.setTextColor(Ui.CARD_MUTED);
        explainer.setTextSize(14);
        form.addView(explainer);
        final EditText nameBox = new EditText(a);
        nameBox.setHint("Project name");
        nameBox.setSingleLine(true);
        nameBox.setTextColor(Ui.CARD_TEXT);
        nameBox.setHintTextColor(Ui.CARD_MUTED);
        nameBox.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        final EditText sectionBox = withSection ? new EditText(a) : null;
        if (sectionBox != null) {
            sectionBox.setHint("First piece");
            sectionBox.setSingleLine(true);
            sectionBox.setTextColor(Ui.CARD_TEXT);
            sectionBox.setHintTextColor(Ui.CARD_MUTED);
            sectionBox.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 12);
        form.addView(nameBox, lp);
        if (sectionBox != null) form.addView(sectionBox, lp);
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("New project")
                .setView(form)
                .setPositiveButton("Create",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                String name = nameBox.getText().toString()
                                        .trim();
                                long pid = db.insertProject(name.isEmpty()
                                        ? "Untitled project" : name);
                                if (withSection) {
                                    String sec = sectionBox.getText()
                                            .toString().trim();
                                    db.insertSection(pid, sec.isEmpty()
                                            ? "Untitled piece" : sec);
                                }
                                cb.onProject(pid);
                            }
                        })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        Ui.styleDialog(a, dialog);
    }
}
