package com.pour.dictation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Bucket piece editor. Unified with the project section editor:
 * top bar (back | title | share | overflow), large body, bottom bar
 * (Dictate | Copy). Edits auto-save on back and on pause — there is
 * no Save button.
 */
public class NoteActivity extends Activity {

    private NotesDb db;
    private Note note;
    private EditText titleBox;
    private EditText transcriptBox;
    private TextView metaView;
    // Debounce for word count: recount 500ms after typing stops,
    // not on every keystroke.
    private final android.os.Handler metaHandler =
            new android.os.Handler();
    private final Runnable metaRunnable = new Runnable() {
        @Override public void run() {
            refreshMeta();
        }
    };
    /** A hand-typed note just created from the Bucket "+": if the
     * writer leaves it empty, it is deleted instead of littering the
     * Bucket with a blank "Untitled piece". */
    private boolean freshManual = false;
    /** Set when the note is deleted so onPause doesn't resurrect it. */
    private boolean deleted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.initTheme(this);
        db = new NotesDb(this);
        long id = getIntent().getLongExtra("note_id", -1);
        note = db.get(id);
        if (note == null) {
            finish();
            return;
        }
        freshManual = getIntent().getBooleanExtra("fresh_manual", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        Ui.applySafeInsets(this, root, 20);

        titleBox = new EditText(this);
        titleBox.setText(note.title);
        titleBox.setSingleLine(true);
        titleBox.setTextSize(20);
        titleBox.setTextColor(Ui.TEXT);
        titleBox.setHint("Untitled piece");
        titleBox.setHintTextColor(Ui.MUTED);
        titleBox.setBackgroundColor(0x00000000);
        root.addView(Ui.editorTopBar(this, titleBox,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onBackPressed(); }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onShare(); }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onOverflow(); }
                }));

        metaView = new TextView(this);
        metaView.setTextColor(Ui.MUTED);
        metaView.setTextSize(14);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = Ui.dp(this, 2);
        root.addView(metaView, metaParams);

        LinearLayout card = new LinearLayout(this);
        card.setBackground(Ui.card(this, 16));
        int cp = Ui.dp(this, 16);
        card.setPadding(cp, cp, cp, cp);
        ScrollView scroll = new ScrollView(this);
        transcriptBox = new EditText(this);
        transcriptBox.setText(note.transcript);
        transcriptBox.setTextColor(Ui.TEXT);
        transcriptBox.setTextSize(16 * Ui.textScale(this));
        transcriptBox.setLineSpacing(0, 1.3f);
        transcriptBox.setHintTextColor(Ui.MUTED);
        transcriptBox.setMinLines(10);
        transcriptBox.setGravity(Gravity.TOP);
        transcriptBox.setBackgroundColor(0x00000000);
        transcriptBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a,
                                                   int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b,
                                               int c) { }
            @Override public void afterTextChanged(
                    android.text.Editable s) {
                // Debounce: cancel pending recount, schedule a new one
                // 500ms out. Typing stays smooth; the count catches up.
                metaHandler.removeCallbacks(metaRunnable);
                metaHandler.postDelayed(metaRunnable, 500);
            }
        });
        scroll.addView(transcriptBox);
        card.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardParams.topMargin = Ui.dp(this, 12);
        root.addView(card, cardParams);

        // Bottom bar: the two things you do with a piece — dictate
        // more into it, or copy it out. Everything else lives in the
        // overflow.
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(this, 12);
        bottomBar.setLayoutParams(bp);
        Button dictateBtn = new Button(this);
        dictateBtn.setText("Dictate");
        Ui.stylePrimaryBtn(this, dictateBtn);
        android.graphics.drawable.Drawable mic =
                getResources().getDrawable(R.drawable.ic_mic);
        mic.setColorFilter(Ui.ON_ACCENT,
                android.graphics.PorterDuff.Mode.SRC_IN);
        dictateBtn.setCompoundDrawablesWithIntrinsicBounds(
                mic, null, null, null);
        dictateBtn.setCompoundDrawablePadding(Ui.dp(this, 8));
        dictateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onDictateMore(); }
        });
        bottomBar.addView(dictateBtn, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button copyBtn = new Button(this);
        copyBtn.setText("Copy");
        Ui.styleBtn(this, copyBtn);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onCopy(); }
        });
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cbp.leftMargin = Ui.dp(this, 8);
        bottomBar.addView(copyBtn, cbp);
        root.addView(bottomBar);

        // Backspace at the very start merges up (outliner convention).
        transcriptBox.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode,
                                           android.view.KeyEvent e) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL
                        && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && transcriptBox.getSelectionStart() == 0
                        && transcriptBox.getSelectionEnd() == 0) {
                    onMergeUp();
                    return true;
                }
                return false;
            }
        });

        setContentView(root);
        refreshMeta();
        // Hand-typed note from the Bucket "+": land in the body with
        // the keyboard up, ready to write.
        if (getIntent().getBooleanExtra("focus_body", false)) {
            transcriptBox.requestFocus();
            transcriptBox.post(new Runnable() {
                @Override public void run() {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(
                                            Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(transcriptBox, 0);
                    }
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.syncTheme(this)) return;
        // Re-apply text size in case the setting changed in Settings.
        transcriptBox.setTextSize(16 * Ui.textScale(this));
        // A "Dictate more" session appends to this note in the database
        // while this screen is away. Reload so the new words are visible
        // the moment the session ends — but unsaved typing always wins,
        // so a return from anywhere else never wipes the screen.
        Note fresh = db.get(note.id);
        if (fresh == null) {
            finish();
            return;
        }
        if (isDirty()) return;
        boolean changed = !eq(fresh.title, note.title)
                || !eq(fresh.transcript, note.transcript);
        note = fresh;
        if (changed) {
            titleBox.setText(note.title);
            transcriptBox.setText(note.transcript);
            // The new words land at the end — put the cursor there so
            // they're on screen instead of scrolled out of sight.
            transcriptBox.setSelection(transcriptBox.getText().length());
            refreshMeta();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Auto-save: no Save button, edits just persist.
        // Also handles the onBackPressed cleanup on API 36+ where
        // onBackPressed() is no longer called: a fresh empty note is
        // removed quietly instead of littering the Bucket. Guarded by
        // `deleted` so it can't run twice.
        if (!deleted && freshManual && isPristineNew()) {
            db.delete(note.id);
            deleted = true;
            return;
        }
        if (!deleted && isDirty()) onSave();
    }

    private static boolean eq(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private void refreshMeta() {
        refreshMeta(NotesDb.countWords(
                transcriptBox.getText().toString()));
    }

    private void refreshMeta(int words) {
        String meta = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(new Date(note.created))
                + " \u00B7 " + words
                + (words == 1 ? " word" : " words");
        metaView.setText(meta);
    }

    /** Split this note into two at the cursor: the text before the cursor
     * stays, the text after becomes a new note right below it — in the
     * same section, or as a new Bin note when splitting from the Bin.
     * The new note is inserted first, so a database failure leaves the
     * original untouched. */
    private void onSplit() {
        int at = transcriptBox.getSelectionStart();
        String full = transcriptBox.getText().toString();
        if (at <= 0 || at >= full.length()) {
            Toast.makeText(this,
                    "Put the cursor where the split should go.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String first = full.substring(0, at).trim();
        String second = full.substring(at).trim();
        if (first.isEmpty() || second.isEmpty()) {
            Toast.makeText(this, "Nothing on one side of the cursor.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String title = titleBox.getText().toString().trim();
        note.title = title.isEmpty() ? "Untitled piece" : title;
        // A Bin note has no position; the new half simply becomes a
        // new Bin note on top.
        long newId = db.insert(
                note.title + " (part 2)",
                second,
                note.tags,
                System.currentTimeMillis(),
                null,
                NotesDb.countWords(second));
        if (newId == -1) {
            Toast.makeText(this, "Couldn't split. Try again.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // Splitting adds no words, so the new half keeps the
        // source's reviewed state.
        db.setDone(newId, note.done);
        final long splitId = newId;
        final String beforeSplit = full;
        note.transcript = first;
        note.words = NotesDb.countWords(first);
        db.update(note);
        transcriptBox.setText(first);
        refreshMeta();
        UndoBar.undo(this, "Split into two pieces.",
                new UndoBar.Action() {
                    @Override public void run() {
                        db.delete(splitId);
                        note.transcript = beforeSplit;
                        note.words = NotesDb.countWords(beforeSplit);
                        db.update(note);
                        transcriptBox.setText(beforeSplit);
                        refreshMeta();
                    }
                });
    }

    /**
     * Fold this note into the previous one (same section, or the Bin's
     * order): the outliner merge. The editor stays on the merged note.
     */
    private void onMergeUp() {
        final Note prev = previousNote();
        if (prev == null) {
            Toast.makeText(this, "No previous piece to merge into.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // Save first, silently: the merge works on the on-screen text.
        String title = titleBox.getText().toString().trim();
        note.title = title.isEmpty() ? "Untitled piece" : title;
        note.transcript = transcriptBox.getText().toString();
        note.words = NotesDb.countWords(note.transcript);
        db.update(note);
        refreshMeta();
        final Note gone = db.get(note.id);
        final String prevBody = prev.transcript;
        final int prevWords = prev.words;
        String mine = note.transcript == null ? ""
                : note.transcript.trim();
        String theirs = prev.transcript == null ? ""
                : prev.transcript.trim();
        prev.transcript = theirs.isEmpty() ? mine : theirs + "\n\n" + mine;
        prev.words = NotesDb.countWords(prev.transcript);
        db.update(prev);
        db.delete(note.id);
        note = prev;
        titleBox.setText(note.title);
        transcriptBox.setText(note.transcript);
        refreshMeta();
        UndoBar.undo(this, "Merged into \u201c" + prev.title + "\u201d.",
                new UndoBar.Action() {
                    @Override public void run() {
                        Note p = db.get(prev.id);
                        if (p != null) {
                            p.transcript = prevBody;
                            p.words = prevWords;
                            db.update(p);
                        }
                        if (gone != null) {
                            long rid = db.insert(gone.title, gone.transcript,
                                    gone.tags, gone.created, null,
                                    gone.words);
                            if (rid != -1) {
                                note = db.get(rid);
                                titleBox.setText(note.title);
                                transcriptBox.setText(note.transcript);
                                refreshMeta();
                            }
                        }
                    }
                });
    }

    /** The note above this one in the Bin's order. */
    private Note previousNote() {
        List<Note> bin = db.binNotes(null, Ui.newestFirst(this));
        for (int i = 0; i < bin.size(); i++) {
            if (bin.get(i).id == note.id) {
                // Newest-first: the "previous" (older) note is next in
                // the list. Oldest-first: it is the one before.
                int j = Ui.newestFirst(this) ? i + 1 : i - 1;
                if (j >= 0 && j < bin.size()) return db.get(bin.get(j).id);
                return null;
            }
        }
        return null;
    }

    /** The note as a document: title header plus body. */
    private String docText() {
        String title = titleBox.getText().toString().trim();
        String body = transcriptBox.getText().toString().trim();
        if (!title.isEmpty() && !body.isEmpty()) return title + "\n\n" + body;
        return body.isEmpty() ? title : body;
    }

    /** The three overflow actions, same order as the section editor. */
    private void onOverflow() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        content.addView(Sheet.row(this, "Split at cursor",
                "Split into two pieces where the cursor is",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        onSplit();
                    }
                }));
        content.addView(Sheet.row(this,
                "Move to project",
                "File this piece into a project",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        Note fresh = db.get(note.id);
                        if (fresh == null) return;
                        LibraryActivity.sendToProject(NoteActivity.this,
                                db, fresh,
                                new Runnable() {
                                    @Override public void run() {
                                        // The note left the Bucket.
                                        finish();
                                    }
                                });
                    }
                }));
        content.addView(Sheet.row(this, "Move to trash",
                "Move this piece to trash",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        onDeleteConfirm();
                    }
                }));
        sheet[0] = Sheet.show(this, "Piece actions", content);
    }

    private void onCopy() {
        String t = docText();
        if (t.isEmpty()) {
            Toast.makeText(this, "Nothing to copy.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            cm.setPrimaryClip(ClipData.newPlainText("Pour dictation", t));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't copy. Try again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Android 13+ shows its own "Copied" overlay; doubling it is a
        // documented annoyance.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show();
        }
    }

    private void onShare() {
        String t = docText();
        if (t.isEmpty()) {
            Toast.makeText(this, "Nothing to share.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, t);
        startActivity(Intent.createChooser(send, "Share piece"));
    }

    /** Silent save: the editor auto-saves, so no toast. */
    private void onSave() {
        String title = titleBox.getText().toString().trim();
        note.title = title.isEmpty() ? "Untitled piece" : title;
        note.transcript = transcriptBox.getText().toString();
        note.words = NotesDb.countWords(note.transcript);
        db.update(note);
        refreshMeta();
    }

    /** Keep dictating into this note: pending edits are saved first so
     * nothing is lost, then capture opens with the note's text preloaded
     * and stopping appends instead of creating a new note. */
    private void onDictateMore() {
        onSave();
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("append_note_id", note.id);
        i.putExtra("append_seed", note.transcript);
        i.putExtra("append_title", note.title);
        startActivity(i);
    }

    @Override
    public void onBackPressed() {
        // A fresh hand-typed note left empty is not a note at all:
        // remove it quietly instead of littering the Bucket.
        if (freshManual && isPristineNew()) {
            db.delete(note.id);
            deleted = true;
            finish();
            return;
        }
        // Auto-save: edits just persist, no save/discard prompt.
        if (isDirty()) onSave();
        finish();
    }

    /** True when a fresh hand-typed note was never written in:
     * empty body and the title still blank or default. */
    private boolean isPristineNew() {
        String title = titleBox.getText().toString().trim();
        String body = transcriptBox.getText().toString().trim();
        return body.isEmpty()
                && (title.isEmpty() || title.equals("Untitled piece"));
    }

    /** True when the on-screen edits differ from the saved note. */
    private boolean isDirty() {
        String title = titleBox.getText().toString().trim();
        // onSave maps an empty title to "Untitled piece" — compare fairly.
        if (title.isEmpty()) title = "Untitled piece";
        String body = transcriptBox.getText().toString();
        String noteTitle = note.title == null ? "" : note.title;
        String noteBody = note.transcript == null ? "" : note.transcript;
        return !title.equals(noteTitle) || !body.equals(noteBody);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void onDeleteConfirm() {
        String title = note.title == null || note.title.trim().isEmpty()
                ? "this piece" : "\u201c" + note.title.trim() + "\u201d";
        new AlertDialog.Builder(this)
                .setMessage("Move " + title + " to trash?")
                .setPositiveButton("Move to trash",
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        db.trashNote(note.id);
                        deleted = true;
                        Toast.makeText(NoteActivity.this,
                                "Moved to trash.", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
