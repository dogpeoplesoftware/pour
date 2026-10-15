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

import java.util.List;

/**
 * Project piece editor. Unified with the Bucket note editor: top bar
 * (back | title | share | overflow), large body, bottom bar
 * (Dictate | Copy). Edits auto-save on back and on pause — there is
 * no Save button.
 *
 * A section is a titled block of writing; voice dictation is
 * first-class here — "Dictate" appends a fresh capture session to
 * the body, reusing the same pipeline as the Bucket notes.
 */
public class SectionActivity extends Activity {

    private NotesDb db;
    private Section section;
    private EditText titleBox;
    private EditText bodyBox;
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
    /** A section just created by "+ Piece": leaving it empty deletes
     * it quietly instead of littering the project. */
    private boolean freshSection = false;
    /** Set when the section is deleted/moved so onPause doesn't save
     * it back. */
    private boolean deleted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.initTheme(this);
        db = new NotesDb(this);
        long id = getIntent().getLongExtra("section_id", -1);
        section = db.getSection(id);
        if (section == null) {
            finish();
            return;
        }
        freshSection = getIntent().getBooleanExtra("fresh_section", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        Ui.applySafeInsets(this, root, 20);

        titleBox = new EditText(this);
        titleBox.setText(section.name);
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
        bodyBox = new EditText(this);
        bodyBox.setText(section.body);
        bodyBox.setTextColor(Ui.TEXT);
        bodyBox.setTextSize(16 * Ui.textScale(this));
        bodyBox.setLineSpacing(0, 1.3f);
        // Notion-style: the hint teaches the one action, then vanishes
        // on first input.
        bodyBox.setHint("Start dictating or type\u2026");
        bodyBox.setHintTextColor(Ui.MUTED);
        bodyBox.setMinLines(10);
        bodyBox.setGravity(Gravity.TOP);
        bodyBox.setBackgroundColor(0x00000000);
        bodyBox.addTextChangedListener(new android.text.TextWatcher() {
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
        scroll.addView(bodyBox);
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

        setContentView(root);
        refreshMeta();
        if (getIntent().getBooleanExtra("focus_body", false)) {
            bodyBox.requestFocus();
            bodyBox.post(new Runnable() {
                @Override public void run() {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(
                                            Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(bodyBox, 0);
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
        bodyBox.setTextSize(16 * Ui.textScale(this));
        // A "Dictate" session appends to this section in the
        // database while this screen is away. Reload so the new words
        // are visible the moment the session ends — but unsaved typing
        // always wins, so a return from anywhere else never wipes the
        // screen.
        Section fresh = db.getSection(section.id);
        if (fresh == null) {
            finish();
            return;
        }
        if (isDirty()) return;
        boolean changed = !eq(fresh.name, section.name)
                || !eq(fresh.body, section.body);
        section = fresh;
        if (changed) {
            titleBox.setText(section.name);
            bodyBox.setText(section.body);
            bodyBox.setSelection(bodyBox.getText().length());
            refreshMeta();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Auto-save: no Save button, edits just persist.
        // Also handles the onBackPressed cleanup on API 36+ where
        // onBackPressed() is no longer called: a fresh empty section is
        // removed quietly. Guarded by `deleted` so it can't run twice.
        if (!deleted && freshSection && isPristineNew()) {
            db.deleteSectionQuiet(section.id);
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
        int words = NotesDb.countWords(bodyBox.getText().toString());
        metaView.setText(words + (words == 1 ? " word" : " words"));
    }

    /** Title for saving: the writer's own, or the body's opening words
     * when they never named it. */
    private String resolvedTitle() {
        String t = titleBox.getText().toString().trim();
        if (!t.isEmpty()) return t;
        String auto = NotesDb.makeTitle(bodyBox.getText().toString());
        return auto.equals("Untitled piece") ? "Untitled piece" : auto;
    }

    /** Silent save: the editor auto-saves, so no toast. */
    private void onSave() {
        section.name = resolvedTitle();
        titleBox.setText(section.name);
        db.renameSection(section.id, section.name);
        String body = bodyBox.getText().toString();
        db.updateSectionBody(section.id, body);
        section.body = body;
        section.words = NotesDb.countWords(body);
        refreshMeta();
    }

    /** Keep dictating into this section: pending edits are saved first
     * so nothing is lost, then capture opens with the section's text
     * preloaded and stopping appends instead of creating a new note. */
    private void onDictateMore() {
        onSave();
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("append_section_id", section.id);
        i.putExtra("append_seed", section.body);
        i.putExtra("append_title", section.name);
        startActivity(i);
    }

    /** Split this section into two at the cursor: the text before the
     * cursor stays, the text after becomes a new piece right after it
     * — in the same folder when the section sits in one. */
    private void onSplitSection() {
        int at = bodyBox.getSelectionStart();
        String full = bodyBox.getText().toString();
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
        String name = resolvedTitle();
        long newId = db.insertSection(section.projectId, name + " (part 2)");
        if (newId == -1) {
            Toast.makeText(this, "Couldn't split. Try again.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        db.updateSectionBody(newId, second);
        // Land the new piece right after this one, in the same folder
        // when this section sits in one.
        if (section.folderId >= 0) {
            List<Section> sibs = db.sectionsInFolder(section.folderId);
            int slot = sibs.size();
            for (int i = 0; i < sibs.size(); i++) {
                if (sibs.get(i).id == section.id) {
                    slot = i + 1;
                    break;
                }
            }
            db.moveSectionToContainer(newId, section.folderId, slot);
        }
        final long splitId = newId;
        final String beforeSplit = full;
        section.name = name;
        titleBox.setText(name);
        db.renameSection(section.id, name);
        db.updateSectionBody(section.id, first);
        section.body = first;
        section.words = NotesDb.countWords(first);
        bodyBox.setText(first);
        refreshMeta();
        UndoBar.undo(this, "Split into two pieces.",
                new UndoBar.Action() {
                    @Override public void run() {
                        db.deleteSectionQuiet(splitId);
                        db.updateSectionBody(section.id, beforeSplit);
                        section.body = beforeSplit;
                        section.words = NotesDb.countWords(beforeSplit);
                        bodyBox.setText(beforeSplit);
                        refreshMeta();
                    }
                });
    }

    /** Move this piece out of the project into the Bucket. The text is
     * preserved as a Bucket note, so no writing is lost. */
    private void onMoveToBucket() {
        if (isDirty()) onSave();
        db.deleteSection(section.id); // body moves to the Bucket as a note
        deleted = true;
        Toast.makeText(this, "Moved to Bucket.", Toast.LENGTH_SHORT).show();
        finish();
    }

    private String docText() {
        String title = titleBox.getText().toString().trim();
        String body = bodyBox.getText().toString().trim();
        if (!title.isEmpty() && !body.isEmpty()) return title + "\n\n" + body;
        return body.isEmpty() ? title : body;
    }

    /** The three overflow actions, same order as the Bucket editor. */
    private void onOverflow() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        content.addView(Sheet.row(this, "Split at cursor",
                "Split into two pieces where the cursor is",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        onSplitSection();
                    }
                }));
        content.addView(Sheet.row(this, "Move to Bucket",
                "Move this piece out of the project",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        onMoveToBucket();
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
            cm.setPrimaryClip(ClipData.newPlainText("Pour piece", t));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't copy. Try again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
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

    private void onDeleteConfirm() {
        String title = section.name == null || section.name.trim().isEmpty()
                ? "this piece" : "\u201c" + section.name.trim() + "\u201d";
        new AlertDialog.Builder(this)
                .setMessage("Move " + title + " to trash?")
                .setPositiveButton("Move to trash",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d,
                                                          int which) {
                                db.trashSection(section.id);
                                deleted = true;
                                Toast.makeText(SectionActivity.this,
                                        "Moved to trash.",
                                        Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        // A fresh section left empty is not a section at all: remove it
        // quietly instead of littering the project.
        if (freshSection && isPristineNew()) {
            db.deleteSectionQuiet(section.id);
            deleted = true;
            finish();
            return;
        }
        // Auto-save: edits just persist, no save/discard prompt. An
        // unnamed-but-written section gets its title from its opening
        // words on the way out — no naming ceremony.
        if (isDirty()) onSave();
        finish();
    }

    /** True when a fresh section was never written in: empty body and
     * the title still blank. */
    private boolean isPristineNew() {
        String title = titleBox.getText().toString().trim();
        String body = bodyBox.getText().toString().trim();
        return body.isEmpty() && title.isEmpty();
    }

    /** True when the on-screen edits differ from the saved section. */
    private boolean isDirty() {
        String title = titleBox.getText().toString().trim();
        String body = bodyBox.getText().toString();
        String savedTitle = section.name == null ? "" : section.name;
        String savedBody = section.body == null ? "" : section.body;
        return !title.equals(savedTitle) || !body.equals(savedBody);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
