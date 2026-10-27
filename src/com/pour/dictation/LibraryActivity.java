package com.pour.dictation;

import android.util.Log;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The Bucket: every dictation lands here, unfiled and unorganized.
 * Styled like the Pour web app. */
public class LibraryActivity extends Activity {

    private static final int SCOPE_BIN = 0;
    private static final int SCOPE_PROJECTS = 1;
    private static final int SCOPE_ALL = 2;

    private NotesDb db;
    private ArrayAdapter<Object> adapter;
    /** Search results: Bucket notes, plus project sections when the
     * scope includes projects. Sections are the searchable writing
     * inside projects since 1.9.0. */
    private final List<Object> notes = new ArrayList<>();
    private EditText searchBox;
    private Button clearSearchBtn;
    private TextView countView;
    private Button sortBtn;
    private TextView todayView;
    private TextView emptyView;
    private Button emptyActionBtn;
    private int searchScope = SCOPE_BIN;
    private Button chipBin;
    private Button chipProjects;
    private Button chipAll;
    private LinearLayout chipRow;
    private ListView list;
    private android.widget.FrameLayout listSlot;
    // Bulk selection for export: in-memory, cleared when leaving the
    // page — the same model as the project view.
    private final Set<Long> selected = new HashSet<Long>();
    /** Compact floating selection strip: every bulk action as a text
     * button, delete last in red. */
    private SelectionBar selectionBar;
    // Drag to reorder: press-and-hold lifts the card; a trash bar
    // appears while dragging (Pixel-launcher style).
    private View insertLine;
    private LinearLayout trashBar;
    private TextView trashLabel;
    private android.widget.ImageView trashIcon;
    private long dragNoteId = -1;
    private float dragX, dragY;
    private boolean trashHover;
    private final android.os.Handler dragHandler =
            new android.os.Handler();
    private Runnable autoScrollTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.initTheme(this);
        db = new NotesDb(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        Ui.applySafeInsets(this, root, 20);

        root.addView(Ui.header(this, "Bucket",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onExportBucket(); }
                },
                "Write a piece",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onNewManualNote(); }
                },
                true, true, false));

        TextView purposeView = new TextView(this);
        purposeView.setText("Your thoughts, turned to text.");
        purposeView.setTextColor(Ui.MUTED);
        purposeView.setTextSize(14);
        LinearLayout.LayoutParams bpp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bpp.topMargin = Ui.dp(this, 4);
        root.addView(purposeView, bpp);

        todayView = new TextView(this);
        todayView.setTextColor(Ui.MUTED);
        todayView.setTextSize(14);
        root.addView(todayView);

        searchBox = new EditText(this);
        searchBox.setHint("Search pieces\u2026");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Ui.CARD_TEXT);
        searchBox.setHintTextColor(Ui.CARD_MUTED);
        searchBox.setBackground(Ui.card(this, 16));
        int sp = Ui.dp(this, 16);
        searchBox.setPadding(sp, Ui.dp(this, 12), sp, Ui.dp(this, 12));

        clearSearchBtn = new Button(this);
        clearSearchBtn.setText("\u2715");
        clearSearchBtn.setTextColor(Ui.MUTED);
        clearSearchBtn.setBackgroundColor(0x00000000);
        clearSearchBtn.setMinWidth(0);
        clearSearchBtn.setMinimumWidth(0);
        clearSearchBtn.setMinHeight(Ui.dp(this, 48));
        clearSearchBtn.setMinimumHeight(Ui.dp(this, 48));
        clearSearchBtn.setContentDescription("Clear search");
        clearSearchBtn.setVisibility(View.GONE);
        clearSearchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                searchBox.setText("");
            }
        });

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        searchRow.addView(clearSearchBtn);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        searchParams.topMargin = Ui.dp(this, 12);
        root.addView(searchRow, searchParams);

        // Scope chips: the Bucket alone, project sections, or everything.
        // They only mean something while searching, so they stay hidden
        // until there's a query — dead chips are worse than no chips.
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipBin = makeChip("Bucket");
        chipProjects = makeChip("Projects");
        chipAll = makeChip("All");
        chipRow.addView(chipBin);
        chipRow.addView(chipProjects);
        chipRow.addView(chipAll);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = Ui.dp(this, 8);
        root.addView(chipRow, chipParams);
        paintChips();

        countView = new TextView(this);
        countView.setTextColor(Ui.MUTED);
        countView.setTextSize(14);
        countView.setVisibility(View.GONE);
        root.addView(countView);

        // Sort control: compact right-aligned "Sort: Custom" button.
        // Custom is the manual drag order; Newest/Oldest are the
        // chronological views. A drag in another mode flips to Custom.
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(android.view.Gravity.END);
        sortBtn = new Button(this);
        sortBtn.setBackground(Ui.selectable(this));
        sortBtn.setTextColor(Ui.MUTED);
        sortBtn.setTextSize(12);
        sortBtn.setMinHeight(Ui.dp(this, 36));
        sortBtn.setMinimumHeight(Ui.dp(this, 36));
        sortBtn.setAllCaps(false);
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onSortMenu(); }
        });
        sortRow.addView(sortBtn);
        LinearLayout.LayoutParams sortParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(sortRow, sortParams);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(Ui.BG));
        list.setDividerHeight(Ui.dp(this, 12));
        list.setFastScrollEnabled(true);
        adapter = new ArrayAdapter<Object>(this, 0, notes) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout card;
                ImageButton selectBtn;
                TextView titleView;
                TextView metaView;
                TextView previewView;
                ImageButton overflowBtn;
                if (convertView == null) {
                    card = new LinearLayout(LibraryActivity.this);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    int p = Ui.dp(LibraryActivity.this, 16);
                    card.setPadding(p, p, p, p);
                    card.setBackground(Ui.card(LibraryActivity.this, 16));
                    // Selection checkbox on the left. Not focusable: a
                    // focusable child steals taps and long-presses from
                    // the ListView (1.0.1).
                    selectBtn = new ImageButton(LibraryActivity.this);
                    selectBtn.setImageResource(R.drawable.ic_box);
                    selectBtn.setContentDescription("Select piece");
                    selectBtn.setBackground(Ui.selectable(
                            LibraryActivity.this));
                    selectBtn.setColorFilter(Ui.MUTED);
                    selectBtn.setFocusable(false);
                    selectBtn.setFocusableInTouchMode(false);
                    int s = Ui.dp(LibraryActivity.this, 48);
                    selectBtn.setLayoutParams(
                            new LinearLayout.LayoutParams(s, s));
                    selectBtn.setScaleType(
                            ImageView.ScaleType.CENTER_INSIDE);
                    int pad = Ui.dp(LibraryActivity.this, 12);
                    selectBtn.setPadding(pad, pad, pad, pad);
                    selectBtn.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    Note sn = (Note) v.getTag();
                                    if (sn == null) return;
                                    if (selected.contains(sn.id)) {
                                        selected.remove(sn.id);
                                    } else {
                                        selected.add(sn.id);
                                    }
                                    refresh();
                                    list.announceForAccessibility(
                                            selected.contains(sn.id)
                                                    ? "Piece selected."
                                                    : "Piece deselected.");
                                }
                            });
                    card.addView(selectBtn);
                    LinearLayout textCol =
                            new LinearLayout(LibraryActivity.this);
                    textCol.setOrientation(LinearLayout.VERTICAL);
                    titleView = new TextView(LibraryActivity.this);
                    titleView.setTextColor(Ui.CARD_TEXT);
                    titleView.setTextSize(16);
                    titleView.setTypeface(Typeface.DEFAULT_BOLD);
                    metaView = new TextView(LibraryActivity.this);
                    metaView.setTextColor(Ui.CARD_MUTED);
                    metaView.setTextSize(14);
                    previewView = new TextView(LibraryActivity.this);
                    previewView.setTextColor(Ui.CARD_MUTED);
                    previewView.setTextSize(14);
                    previewView.setLineSpacing(0, 1.3f);
                    // Two lines of body: the triage ceiling — enough to
                    // file a note without opening it.
                    previewView.setMaxLines(2);
                    previewView.setEllipsize(
                            android.text.TextUtils.TruncateAt.END);
                    textCol.addView(titleView);
                    textCol.addView(metaView);
                    LinearLayout.LayoutParams pp =
                            new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT);
                    pp.topMargin = Ui.dp(LibraryActivity.this, 6);
                    textCol.addView(previewView, pp);
                    card.addView(textCol,
                            new LinearLayout.LayoutParams(0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f));
                    // Overflow menu on the right: Rename, Send to
                    // project, Delete. Not focusable, same as the
                    // checkbox.
                    overflowBtn =
                            new ImageButton(LibraryActivity.this);
                    overflowBtn.setImageResource(R.drawable.ic_more);
                    overflowBtn.setContentDescription("Piece options");
                    overflowBtn.setBackground(Ui.selectable(
                            LibraryActivity.this));
                    overflowBtn.setColorFilter(Ui.MUTED);
                    overflowBtn.setFocusable(false);
                    overflowBtn.setFocusableInTouchMode(false);
                    overflowBtn.setLayoutParams(
                            new LinearLayout.LayoutParams(s, s));
                    overflowBtn.setScaleType(
                            ImageView.ScaleType.CENTER_INSIDE);
                    overflowBtn.setPadding(pad, pad, pad, pad);
                    overflowBtn.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    Note on = (Note) v.getTag();
                                    if (on == null) return;
                                    onCardMenu(on, v);
                                }
                            });
                    card.addView(overflowBtn);
                    card.setTag(new Object[]{selectBtn, titleView,
                            metaView, previewView, overflowBtn});
                    card.setLayoutParams(new AbsListView.LayoutParams(
                            AbsListView.LayoutParams.MATCH_PARENT,
                            AbsListView.LayoutParams.WRAP_CONTENT));
                } else {
                    card = (LinearLayout) convertView;
                    Object[] t = (Object[]) card.getTag();
                    selectBtn = (ImageButton) t[0];
                    titleView = (TextView) t[1];
                    metaView = (TextView) t[2];
                    previewView = (TextView) t[3];
                    overflowBtn = (ImageButton) t[4];
                }
                Object item = getItem(position);
                String q = searchBox.getText().toString().trim();
                titleView.setTextSize(16);
                previewView.setTextSize(14);
                if (item instanceof Section) {
                    // A project section found via search: no checkbox
                    // (bulk selection is for Bucket notes), tap opens
                    // the section editor.
                    Section s = (Section) item;
                    selectBtn.setTag(null);
                    selectBtn.setVisibility(View.GONE);
                    overflowBtn.setTag(null);
                    overflowBtn.setVisibility(View.GONE);
                    titleView.setText(highlight(s.name, q));
                    Project pr = db.getProject(s.projectId);
                    String where = pr == null ? "Project" : pr.name;
                    metaView.setText("Piece \u00B7 " + where + " \u00B7 "
                            + s.words + (s.words == 1 ? " word" : " words"));
                    previewView.setText(highlight(previewFor(s), q));
                    return card;
                }
                selectBtn.setVisibility(View.VISIBLE);
                Note n = (Note) item;
                selectBtn.setTag(n);
                overflowBtn.setVisibility(View.VISIBLE);
                overflowBtn.setTag(n);
                boolean isSel = selected.contains(n.id);
                selectBtn.setImageResource(isSel
                        ? R.drawable.ic_box_checked : R.drawable.ic_box);
                selectBtn.setColorFilter(isSel ? Ui.ACCENT : Ui.MUTED);
                selectBtn.setContentDescription(
                        isSel ? "Deselect piece" : "Select piece");
                titleView.setText(highlight(n.title, q));
                metaView.setText(metaFor(n));
                previewView.setText(highlight(previewFor(n), q));
                // Text size is the user's Settings choice; re-apply on
                // every bind because rows are recycled. Titles stay fixed
                // so the hierarchy survives the text-size setting; only
                // reading text (the preview) scales.
                return card;
            }
        };
        list.setAdapter(adapter);
        // The list lives in a weighted slot: when the bucket empties,
        // the ListView goes GONE for its empty view, and without this
        // slot the tab row would ride up the screen. The slot holds
        // the space so the tab row stays pinned to the bottom.
        listSlot = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = Ui.dp(this, 12);
        root.addView(listSlot, listParams);
        listSlot.addView(list,
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        // Insertion line for drag-to-reorder.
        insertLine = new View(this);
        insertLine.setBackgroundColor(Ui.ACCENT);
        insertLine.setVisibility(View.GONE);
        insertLine.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        listSlot.addView(insertLine,
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        Ui.dp(this, 3),
                        android.view.Gravity.TOP));
        // Trash bar: visible only while a card is being dragged.
        // Pixel-launcher style — drop here deletes with undo.
        trashBar = new LinearLayout(this);
        trashBar.setOrientation(LinearLayout.HORIZONTAL);
        trashBar.setGravity(android.view.Gravity.CENTER);
        trashIcon = new android.widget.ImageView(this);
        trashIcon.setImageResource(R.drawable.ic_delete);
        trashIcon.setColorFilter(Ui.MUTED);
        int tis = Ui.dp(this, 24);
        trashBar.addView(trashIcon,
                new LinearLayout.LayoutParams(tis, tis));
        trashLabel = new TextView(this);
        trashLabel.setText("Trash");
        trashLabel.setTextColor(Ui.MUTED);
        trashLabel.setTextSize(14);
        LinearLayout.LayoutParams tlp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(this, 8);
        trashBar.addView(trashLabel, tlp);
        trashBar.setBackground(Ui.card(this, 20));
        int tp = Ui.dp(this, 12);
        trashBar.setPadding(tp * 2, tp, tp * 2, tp);
        trashBar.setVisibility(View.GONE);
        trashBar.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.widget.FrameLayout.LayoutParams trashParams =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM
                                | android.view.Gravity.CENTER_HORIZONTAL);
        trashParams.bottomMargin = Ui.dp(this, 16);
        listSlot.addView(trashBar, trashParams);
        TextView empty = new TextView(this);
        empty.setText("Your Bucket is clear.");
        empty.setTextColor(Ui.MUTED);
        empty.setGravity(android.view.Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams emptyParams =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER);
        listSlot.addView(empty, emptyParams);
        list.setEmptyView(empty);
        emptyView = empty;

        emptyActionBtn = new Button(this);
        emptyActionBtn.setText("Start dictating");
        emptyActionBtn.setTextColor(Ui.ON_ACCENT);
        emptyActionBtn.setBackground(Ui.filled(this, Ui.ACCENT, 14));
        emptyActionBtn.setMinHeight(Ui.dp(this, 48));
        emptyActionBtn.setMinimumHeight(Ui.dp(this, 48));
        emptyActionBtn.setVisibility(View.GONE);
        emptyActionBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(
                        new Intent(LibraryActivity.this, MainActivity.class));
            }
        });
        LinearLayout.LayoutParams eabParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        eabParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        eabParams.topMargin = Ui.dp(this, 12);
        root.addView(emptyActionBtn, eabParams);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                Object item = notes.get(position);
                if (item instanceof Section) {
                    Section s = (Section) item;
                    Intent i = new Intent(LibraryActivity.this,
                            SectionActivity.class);
                    i.putExtra("section_id", s.id);
                    startActivity(i);
                    return;
                }
                Note n = (Note) item;
                Intent i = new Intent(LibraryActivity.this, NoteActivity.class);
                i.putExtra("note_id", n.id);
                startActivity(i);
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           int position, long id) {
                Object item = notes.get(position);
                if (item instanceof Section) {
                    // A section found via search: open its project.
                    Section s = (Section) item;
                    Intent i = new Intent(LibraryActivity.this,
                            ProjectActivity.class);
                    i.putExtra("project_id", s.projectId);
                    startActivity(i);
                } else if (!searchBox.getText().toString().trim()
                        .isEmpty()) {
                    // Reordering a filtered view is confusing: the
                    // manual order applies to the full Bucket.
                    Toast.makeText(LibraryActivity.this,
                            "Clear search to reorder.",
                            Toast.LENGTH_SHORT).show();
                } else {
                    startNoteDrag((Note) item, view);
                }
                return true;
            }
        });

        list.setOnDragListener(new View.OnDragListener() {
            @Override public boolean onDrag(View v, android.view.DragEvent e) {
                return onBucketDrag(e);
            }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });

        root.addView(Ui.tabRow(this, Ui.TAB_BIN));

        // Floating selection toolbar: lives above the tab row,
        // visible only while cards are checked. Wrapped in a
        // FrameLayout so it can float over the list without taking
        // layout space.
        java.util.List<SelectionBar.Action> barActions =
                new java.util.ArrayList<SelectionBar.Action>();
        barActions.add(new SelectionBar.Action() {
            @Override public String label() { return "Copy"; }
            @Override public boolean visible() { return true; }
            @Override public boolean destructive() { return false; }
            @Override public void run() { onCopyBucket(); }
        });
        barActions.add(new SelectionBar.Action() {
            @Override public String label() { return "Share"; }
            @Override public boolean visible() { return true; }
            @Override public boolean destructive() { return false; }
            @Override public void run() { onExportBucket(); }
        });
        barActions.add(new SelectionBar.Action() {
            @Override public String label() { return "Move"; }
            @Override public boolean visible() {
                return Pro.isPro(LibraryActivity.this);
            }
            @Override public boolean destructive() { return false; }
            @Override public void run() { onSendSelectedToProject(); }
        });
        barActions.add(new SelectionBar.Action() {
            @Override public String label() { return "Move to trash"; }
            @Override public boolean visible() { return true; }
            @Override public boolean destructive() { return true; }
            @Override public void run() { onDeleteSelected(); }
        });
        selectionBar = new SelectionBar(this, barActions,
                new Runnable() {
                    @Override public void run() {
                        selected.clear();
                        refresh();
                    }
                });
        android.widget.FrameLayout frame =
                new android.widget.FrameLayout(this);
        frame.addView(root, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        int navInset = 0;
        try {
            android.view.WindowInsets insets =
                    getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                navInset = insets.getSystemWindowInsetBottom();
            }
        } catch (Exception e) {
            Log.w("LibraryActivity", "Suppressed exception", e);
        }
        android.widget.FrameLayout.LayoutParams barParams =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM
                                | android.view.Gravity.CENTER_HORIZONTAL);
        barParams.bottomMargin = navInset + Ui.dp(this, 84);
        frame.addView(selectionBar.view(), barParams);

        setContentView(frame);
    }

    private Button makeChip(String label) {
        final Button chip = new Button(this);
        chip.setText(label);
        chip.setBackground(Ui.selectable(this));
        chip.setMinWidth(0);
        chip.setMinimumWidth(0);
        chip.setMinHeight(Ui.dp(this, 48));
        chip.setMinimumHeight(Ui.dp(this, 48));
        chip.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        chip.setTag(label);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String l = (String) chip.getTag();
                searchScope = "Projects".equals(l) ? SCOPE_PROJECTS
                        : "All".equals(l) ? SCOPE_ALL : SCOPE_BIN;
                paintChips();
                refresh();
            }
        });
        return chip;
    }

    private void paintChips() {
        chipBin.setTextColor(
                searchScope == SCOPE_BIN ? Ui.ACCENT : Ui.MUTED);
        chipProjects.setTextColor(
                searchScope == SCOPE_PROJECTS ? Ui.ACCENT : Ui.MUTED);
        chipAll.setTextColor(
                searchScope == SCOPE_ALL ? Ui.ACCENT : Ui.MUTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.syncTheme(this)) return;
        refresh();
        maybeInsertWelcomePiece();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Bulk selection is per-visit, like the project view.
        selected.clear();
    }

    /** A note typed by hand: for writers who would rather not dictate.
     * Dictation stays the primary capture via the Capture tab; this is
     * the side door. The note is created up front so the editor needs
     * no new-note mode; if it is still empty when the writer leaves,
     * the editor deletes it instead of littering the Bucket. */
    private void onNewManualNote() {
        long id = db.insert("Untitled piece", "", "",
                System.currentTimeMillis(), null, 0);
        if (id == -1) {
            android.widget.Toast.makeText(this,
                    "Couldn't create the piece. Try again.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, NoteActivity.class);
        i.putExtra("note_id", id);
        i.putExtra("fresh_manual", true);
        i.putExtra("focus_body", true);
        startActivity(i);
    }

    /** Match highlighting for search: a soft accent wash behind hits. */
    private CharSequence highlight(String text, String query) {
        if (text == null) return "";
        if (query == null || query.isEmpty()) return text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        String lower = text.toLowerCase(java.util.Locale.US);
        String ql = query.toLowerCase(java.util.Locale.US);
        int from = 0;
        while (true) {
            int at = lower.indexOf(ql, from);
            if (at < 0) break;
            sb.setSpan(new BackgroundColorSpan(Ui.HIGHLIGHT), at,
                    at + ql.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = at + ql.length();
        }
        return sb;
    }

    private String previewFor(Note n) {
        if (n.transcript == null) return "";
        String t = n.transcript.replaceAll("\\s+", " ").trim();
        return t.length() > 140 ? t.substring(0, 140) + "\u2026" : t;
    }

    private String previewFor(Section s) {
        if (s.body == null) return "";
        String t = s.body.replaceAll("\\s+", " ").trim();
        return t.length() > 140 ? t.substring(0, 140) + "\u2026" : t;
    }

    private String metaFor(Note n) {
        String meta = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(new Date(n.created))
                + " \u00B7 " + n.words
                + (n.words == 1 ? " word" : " words");
        if (n.tags != null && !n.tags.isEmpty()) meta += " \u00B7 " + n.tags;
        return meta;
    }

    private void refresh() {
        notes.clear();
        String q = searchBox.getText().toString();
        boolean searching = !q.trim().isEmpty();
        String sortMode = Ui.bucketSortMode(this);
        if (!searching) {
            notes.addAll(db.binNoteHeaders(null, sortMode));
        } else if (searchScope == SCOPE_PROJECTS) {
            notes.addAll(db.searchSections(q));
        } else if (searchScope == SCOPE_ALL) {
            notes.addAll(db.binNoteHeaders(q, sortMode));
            notes.addAll(db.searchSections(q));
        } else {
            notes.addAll(db.binNoteHeaders(q, sortMode));
        }
        adapter.notifyDataSetChanged();
        if (selectionBar != null) selectionBar.update(selected.size());
        updateSortLabel();
        clearSearchBtn.setVisibility(
                searching ? View.VISIBLE : View.GONE);
        // The scope chips only exist to scope a search.
        chipRow.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (!searching && searchScope != SCOPE_BIN) {
            searchScope = SCOPE_BIN;
            paintChips();
        }
        if (searching) {
            countView.setVisibility(View.VISIBLE);
            countView.setText(notes.size()
                    + (notes.size() == 1 ? " piece matches."
                            : " pieces match."));
        } else {
            countView.setVisibility(View.GONE);
        }
        // Empty states: a search with no hits is not an empty Bin, and a
        // first-use Bin is not a cleared one.
        SharedPreferences prefs =
                getSharedPreferences("pour_pro", MODE_PRIVATE);
        if (!notes.isEmpty()) {
            prefs.edit().putBoolean("bin_ever_had", true).apply();
        }
        boolean everHad = prefs.getBoolean("bin_ever_had", false);
        if (emptyView != null) {
            if (searching) {
                emptyView.setText("No pieces match.");
            } else if (everHad) {
                emptyView.setText("Your Bucket is clear.");
            } else {
                emptyView.setText("Nothing here yet.\nTap Capture and talk, or tap + to write by hand.");
            }
        }
        if (emptyActionBtn != null) {
            emptyActionBtn.setVisibility(
                    !searching && !everHad ? View.VISIBLE : View.GONE);
        }
        // Word count: words in the current view (respects search).
        int words = 0;
        for (Object o : notes) {
            if (o instanceof Note) words += ((Note) o).words;
            else if (o instanceof Section) words += ((Section) o).words;
        }
        todayView.setText(words + (words == 1 ? " word" : " words"));
    }

    /** Insert one welcome piece the first time the Bucket opens empty,
     * so new users aren't staring at a blank screen. Runs once; the
     * flag is set after a successful insert (or if pieces already
     * exist), so it never re-runs. */
    private void maybeInsertWelcomePiece() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("pour_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("welcome_shown", false)) return;
        if (!notes.isEmpty()) {
            prefs.edit().putBoolean("welcome_shown", true).apply();
            return;
        }
        String text = "This is a piece of writing."
                + " Words you dictate or type land here as a piece.\n\n"
                + "Tap it to open and edit. Use the menu (three dots) to"
                + " rename, move it to a project, or move it to trash.\n\n"
                + "Happy writing!";
        long id = db.insert("Welcome to Pour", text, null,
                System.currentTimeMillis(), null, NotesDb.countWords(text));
        if (id > 0) {
            prefs.edit().putBoolean("welcome_shown", true).apply();
            refresh();
        }
    }

    /** Sort mode label: Custom is the manual drag order. */
    private void updateSortLabel() {
        if (sortBtn == null) return;
        String mode = Ui.bucketSortMode(this);
        String label = "custom".equals(mode) ? "Custom"
                : "oldest".equals(mode) ? "Oldest" : "Newest";
        sortBtn.setText("Sort: " + label);
    }

    /** Sort picker: Custom (manual drag order), Newest, Oldest. */
    private void onSortMenu() {
        android.widget.PopupMenu menu =
                new android.widget.PopupMenu(this, sortBtn);
        menu.getMenu().add(0, 0, 0, "Custom order");
        menu.getMenu().add(0, 1, 1, "Newest first");
        menu.getMenu().add(0, 2, 2, "Oldest first");
        menu.setOnMenuItemClickListener(
                new android.widget.PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(
                            android.view.MenuItem item) {
                        String mode = item.getItemId() == 1 ? "newest"
                                : item.getItemId() == 2 ? "oldest"
                                : "custom";
                        Ui.setBucketSortMode(LibraryActivity.this, mode);
                        refresh();
                        return true;
                    }
                });
        menu.show();
    }

    /** Export the checked Bucket notes: format picker, then a destination
     * (save to Downloads or share to another app). Nothing checked means
     * nothing to export — the toast says so instead of guessing. */
    private void onExportBucket() {
        final List<Note> toExport = checkedNotes();
        if (toExport.isEmpty()) {
            if (selected.isEmpty()) {
                android.widget.Toast.makeText(this,
                        "Check off the pieces to include.",
                        android.widget.Toast.LENGTH_SHORT).show();
            } else {
                android.widget.Toast.makeText(this, "Nothing to export.",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        final List<Note> notes = toExport;
        // Free-first fixed order: plain text on top, the Pro formats
        // grouped below (TXT, FOUNTAIN, MD, HTML enum order).
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        final boolean pro = Pro.isPro(this);
        for (final ProjectExporter.Format f
                : ProjectExporter.Format.values()) {
            final boolean locked =
                    !pro && Pro.formatIsPro(f);
            content.addView(Sheet.row(this,
                    f.label + " (." + f.ext + ")"
                            + (locked ? " (PRO)" : ""),
                    f.description,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            getSharedPreferences("pour_pro", MODE_PRIVATE)
                                    .edit()
                                    .putString("export_format", f.name())
                                    .apply();
                            if (sheet[0] != null) sheet[0].dismiss();
                            onBucketFormatPicked(f, notes);
                        }
                    }));
        }
        sheet[0] = Sheet.show(this, "Share as", content);
    }

    /** The checked Bucket notes as a single text block: each note's
     * title (when it has one) over its transcript, notes separated by
     * a blank line. */
    /** The checked Bucket notes, in list order. */
    private List<Note> checkedNotes() {
        List<Note> out = new ArrayList<Note>();
        for (Object o : notes) {
            if (o instanceof Note) {
                Note n = (Note) o;
                if (selected.contains(n.id)) out.add(n);
            }
        }
        return out;
    }

    /** The checked Bucket notes as exporter entries, one entry per
     * piece with the piece title as its heading, the same model a
     * project hands the exporter. Empty pieces are skipped, as the
     * exporter's project collection skips empty sections. */
    private static List<ProjectExporter.Entry> bucketEntries(
            List<Note> notes) {
        List<ProjectExporter.Entry> entries =
                new ArrayList<ProjectExporter.Entry>();
        for (Note n : notes) {
            String body = n.transcript == null ? "" : n.transcript;
            if (body.trim().isEmpty()) continue;
            entries.add(new ProjectExporter.Entry(
                    null, n.title == null ? "" : n.title, body));
        }
        return entries;
    }

    /** Copy the checked Bucket pieces: the same "Copy as" format
     * sheet the project page offers (binary formats excluded, as
     * there), rendering the checked pieces under the Bucket name. */
    private void onCopyBucket() {
        final List<Note> toCopy = checkedNotes();
        if (toCopy.isEmpty()) {
            Toast.makeText(this, "Check off the pieces to include.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        for (final ProjectExporter.Format f
                : ProjectExporter.Format.values()) {
            if (f.isBinary()) continue;
            content.addView(Sheet.row(this,
                    f.label + " (." + f.ext + ")",
                    f.description,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            getSharedPreferences("pour_pro", MODE_PRIVATE)
                                    .edit()
                                    .putString("export_format", f.name())
                                    .apply();
                            if (sheet[0] != null) sheet[0].dismiss();
                            doCopyBucket(f, toCopy);
                        }
                    }));
        }
        sheet[0] = Sheet.show(this, "Copy as", content);
    }

    private void doCopyBucket(ProjectExporter.Format f, List<Note> notes) {
        String t = ProjectExporter.render(f, "Bucket",
                bucketEntries(notes));
        if (t.isEmpty()) {
            Toast.makeText(this, "Nothing to copy.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            cm.setPrimaryClip(ClipData.newPlainText("Pour pieces", t));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't copy. Try again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Pieces copied. "
                            + NotesDb.countWords(t) + " words.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** One bucket format picked: render once, then pick the destination. */
    private void onBucketFormatPicked(final ProjectExporter.Format f,
                                      List<Note> notes) {
        List<ProjectExporter.Entry> entries = bucketEntries(notes);
        final boolean isPdf = f == ProjectExporter.Format.PDF;
        final byte[] pdfBytes;
        final String t;
        if (isPdf) {
            pdfBytes = ProjectExporter.renderBytes(f, "Bucket", entries);
            t = null;
            if (pdfBytes == null || pdfBytes.length == 0) {
                android.widget.Toast.makeText(this, "Nothing to export.",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            pdfBytes = null;
            t = ProjectExporter.render(f, "Bucket", entries);
            if (t.isEmpty()) {
                android.widget.Toast.makeText(this, "Nothing to export.",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }
        final String base = ProjectExporter.sanitizeFileName("Bucket");
        final LibraryActivity self = this;
        ExportDelivery.askDestination(this,
                new Runnable() {
                    @Override public void run() {
                        if (isPdf) {
                            ExportDelivery.saveBytesToDownloads(
                                    self, f, pdfBytes, base);
                        } else {
                            ExportDelivery.saveToDownloads(self, f, t, base);
                        }
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        if (isPdf) {
                            ExportDelivery.shareBytes(self, f, pdfBytes, base,
                                    "Share pieces");
                        } else {
                            ExportDelivery.shareFile(self, f, t, base,
                                    "Share pieces");
                        }
                    }
                });
    }

    /** Long-press a Bucket note: rename it, file it into a project,
     * or delete it — without opening it. */
    // ---------------- drag to reorder / drag to trash ----------------

    /** Press-and-hold lifts the card. A drag in Newest/Oldest mode
     * flips to Custom first: manual order is the custom sort. */
    private void startNoteDrag(Note n, View cardView) {
        if (!"custom".equals(Ui.bucketSortMode(this))) {
            Ui.setBucketSortMode(this, "custom");
            refresh();
            Toast.makeText(this, "Custom order.",
                    Toast.LENGTH_SHORT).show();
        }
        dragNoteId = n.id;
        trashHover = false;
        ClipData data = ClipData.newPlainText("pour-pro-bucket-drag",
                String.valueOf(n.id));
        View.DragShadowBuilder shadow =
                new View.DragShadowBuilder(cardView);
        boolean ok;
        if (Build.VERSION.SDK_INT >= 24) {
            ok = cardView.startDragAndDrop(data, shadow, null, 0);
        } else {
            ok = cardView.startDrag(data, shadow, null, 0);
        }
        if (!ok) {
            dragNoteId = -1;
            return;
        }
        try {
            list.performHapticFeedback(
                    android.view.HapticFeedbackConstants.DRAG_START);
        } catch (Exception e) {
            Log.w("LibraryActivity", "Suppressed exception", e);
        }
        trashBar.setVisibility(View.VISIBLE);
        setTrashHover(false);
        // No stale coordinates: the line stays hidden until the
        // finger actually moves.
        dragX = -1;
        dragY = -1;
        if (insertLine != null) insertLine.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private boolean onBucketDrag(android.view.DragEvent e) {
        switch (e.getAction()) {
            case android.view.DragEvent.ACTION_DRAG_STARTED:
                return true;
            case android.view.DragEvent.ACTION_DRAG_LOCATION:
                dragX = e.getX();
                dragY = e.getY();
                updateTrashHover();
                updateBucketInsertLine();
                kickBucketAutoScroll();
                return true;
            case android.view.DragEvent.ACTION_DROP:
                dragX = e.getX();
                dragY = e.getY();
                updateTrashHover();
                if (trashHover) {
                    commitTrashDrop();
                } else {
                    commitBucketDrop();
                }
                return true;
            case android.view.DragEvent.ACTION_DRAG_ENDED:
                endBucketDrag();
                return true;
            default:
                return false;
        }
    }

    /** The trash bar sits at the bottom of the list slot: hovering it
     * arms the delete, everywhere else shows the insert line. */
    private void updateTrashHover() {
        if (trashBar == null || trashBar.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean over = dragX >= trashBar.getLeft()
                && dragX <= trashBar.getRight()
                && dragY >= trashBar.getTop()
                && dragY <= trashBar.getBottom();
        if (over != trashHover) {
            setTrashHover(over);
        }
    }

    private void setTrashHover(boolean over) {
        trashHover = over;
        int red = 0xFFE53935;
        trashLabel.setText(over ? "Release to trash" : "Trash");
        trashLabel.setTextColor(over ? red : Ui.MUTED);
        trashIcon.setColorFilter(over ? red : Ui.MUTED);
        if (over) {
            try {
                list.performHapticFeedback(
                        android.view.HapticFeedbackConstants
                                .CLOCK_TICK);
            } catch (Exception e) {
                Log.w("LibraryActivity", "Suppressed exception", e);
            }
        }
        if (!over) updateBucketInsertLine();
        else if (insertLine != null) insertLine.setVisibility(View.GONE);
    }

    /** Insertion line where the dragged card would land. */
    private void updateBucketInsertLine() {
        if (dragNoteId < 0 || list == null || insertLine == null) return;
        if (trashHover) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        // No coordinates yet (drag just started): keep hidden.
        if (dragX < 0 || dragY < 0) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        int pos = list.pointToPosition((int) dragX, (int) dragY);
        if (pos == AdapterView.INVALID_POSITION || pos >= notes.size()) {
            // Above the first row or below the last: the Y decides.
            if (isBucketDragAboveFirst()) {
                showBucketLineAtTop();
            } else {
                showBucketLineAtEnd();
            }
            return;
        }
        int first = list.getFirstVisiblePosition();
        View child = list.getChildAt(pos - first);
        if (child == null) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        Object item = notes.get(pos);
        if (item instanceof Note
                && ((Note) item).id == dragNoteId) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        // The bar mirrors the drop: top edge when the finger is in
        // the card's top half, bottom edge in the bottom half.
        float lineY = child.getTop() - Ui.dp(this, 1);
        if (dragY > child.getTop() + child.getHeight() / 2f) {
            lineY = child.getBottom() - Ui.dp(this, 1);
        }
        insertLine.setTranslationY(lineY);
        insertLine.setVisibility(View.VISIBLE);
    }

    /** True when the finger is above the first row and the list
     * isn't scrolled: a genuine "top of the list" gesture. */
    private boolean isBucketDragAboveFirst() {
        if (list == null || list.getChildCount() == 0) return true;
        View first = list.getChildAt(0);
        return dragY < first.getTop() + first.getHeight() / 2f;
    }

    private void showBucketLineAtTop() {
        if (insertLine == null || list == null) return;
        if (list.getChildCount() == 0) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        View child = list.getChildAt(0);
        insertLine.setTranslationY(child.getTop() - Ui.dp(this, 1));
        insertLine.setVisibility(View.VISIBLE);
    }

    private void showBucketLineAtEnd() {
        if (insertLine == null || list == null) return;
        int last = list.getChildCount() - 1;
        if (last < 0) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        View child = list.getChildAt(last);
        insertLine.setTranslationY(
                child.getTop() + child.getHeight());
        insertLine.setVisibility(View.VISIBLE);
    }

    private void kickBucketAutoScroll() {
        if (autoScrollTask != null || dragNoteId < 0) return;
        autoScrollTask = new Runnable() {
            @Override public void run() {
                autoScrollTask = null;
                if (dragNoteId < 0 || list == null) return;
                int edge = Ui.dp(LibraryActivity.this, 72);
                int step = Ui.dp(LibraryActivity.this, 28);
                View top = list.getChildAt(0);
                View bottom = list.getChildAt(list.getChildCount() - 1);
                boolean scrolling = false;
                if (top != null && dragY < top.getTop() + edge) {
                    list.smoothScrollBy(-step, 50);
                    scrolling = true;
                } else if (bottom != null
                        && dragY > bottom.getBottom() - edge) {
                    list.smoothScrollBy(step, 50);
                    scrolling = true;
                }
                if (scrolling) {
                    updateBucketInsertLine();
                    kickBucketAutoScroll();
                }
            }
        };
        dragHandler.postDelayed(autoScrollTask, 60);
    }

    private void endBucketDrag() {
        dragNoteId = -1;
        trashHover = false;
        if (autoScrollTask != null) {
            dragHandler.removeCallbacks(autoScrollTask);
            autoScrollTask = null;
        }
        if (insertLine != null) insertLine.setVisibility(View.GONE);
        if (trashBar != null) trashBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    /** Drop on the list: persist the new manual order. */
    private void commitBucketDrop() {
        if (dragNoteId < 0) {
            endBucketDrag();
            return;
        }
        // Dropped without moving: no-op.
        if (dragX < 0 || dragY < 0) {
            endBucketDrag();
            return;
        }
        long id = dragNoteId;
        // Current display order minus the dragged card.
        List<Long> order = new ArrayList<Long>();
        for (Object o : notes) {
            if (o instanceof Note) {
                long nid = ((Note) o).id;
                if (nid != id) order.add(nid);
            }
        }
        // Insertion slot: cards above the finger.
        int pos = list.pointToPosition((int) dragX, (int) dragY);
        int slot;
        if (pos == AdapterView.INVALID_POSITION || pos >= notes.size()) {
            // Above the first card or below the last: the Y decides.
            slot = isBucketDragAboveFirst() ? 0 : order.size();
        } else {
            slot = 0;
            for (int i = 0; i < pos && i < notes.size(); i++) {
                Object o = notes.get(i);
                if (o instanceof Note
                        && ((Note) o).id != id) {
                    slot++;
                }
            }
            // If the finger is in the bottom half of the hovered row,
            // the card goes after it.
            int first = list.getFirstVisiblePosition();
            View child = list.getChildAt(pos - first);
            if (child != null && dragY > child.getTop()
                    + child.getHeight() / 2f) {
                Object o = notes.get(pos);
                if (o instanceof Note && ((Note) o).id != id) slot++;
            }
        }
        if (slot < 0) slot = 0;
        if (slot > order.size()) slot = order.size();
        order.add(slot, id);
        db.reorderBucket(order);
        endBucketDrag();
        refresh();
    }

    /** Drop on the trash bar: delete immediately, undo in the bar.
     * No confirmation — the gesture is deliberate. */
    private void commitTrashDrop() {
        if (dragNoteId < 0) {
            endBucketDrag();
            return;
        }
        final Note gone = db.get(dragNoteId);
        final int gonePos = gone == null ? 0 : gone.pos;
        final long trashedId = dragNoteId;
        db.trashNote(dragNoteId);
        // The dragged card may be checked: drop it from the set.
        selected.remove(dragNoteId);
        endBucketDrag();
        refresh();
        UndoBar.undo(this, "Piece deleted.",
                new UndoBar.Action() {
                    @Override public void run() {
                        if (gone == null) return;
                        // Restore the original row — don't re-insert a
                        // copy (that leaves the trashed original behind).
                        db.restoreNote(trashedId);
                        db.setNotePos(trashedId, gonePos);
                        refresh();
                    }
                });
    }

    /** File this Bucket note into a project (it leaves the Bucket).
     * After picking the project the writer picks a real section —
     * the note goes where they say, never a default holding pile.
     * Static so the note editor can offer the same flow; onFiled
     * runs after the note leaves the Bucket. */
    public static void sendToProject(final Activity a, final NotesDb db,
                                     final Note n, final Runnable onFiled) {
        final List<Project> projects = db.allProjects();
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] sheet = new Dialog[1];
        for (final Project p : projects) {
            List<Section> secs = db.sectionsFor(p.id);
            String sub = secs.size() + (secs.size() == 1 ? " piece"
                    : " pieces");
            content.addView(Sheet.row(a, p.name, sub,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (sheet[0] != null) sheet[0].dismiss();
                            pickSectionFor(a, db, n, p.id, onFiled);
                        }
                    }));
        }
        content.addView(Sheet.row(a, "New project\u2026", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        NoteDialogs.newProject(a, db, false,
                                new NoteDialogs.ProjectCallback() {
                                    @Override public void onProject(long pid) {
                                        fileAsNewSection(a, db, n, pid, -1,
                                                onFiled);
                                    }
                                });
                    }
                }));
        sheet[0] = Sheet.show(a, "Move to project", content);
    }

    private void onSendToProject(final Note n) {
        sendToProject(this, db, n, new Runnable() {
            @Override public void run() {
                refresh();
            }
        });
    }

    /** Second step of filing: the note becomes a section ("New
     * section", titled with the note's title) or its text is appended
     * to an existing section ("Add to"). Sections group under their
     * folder labels; loose sections come under "Ungrouped" (only when
     * folders exist — otherwise the label is noise). */
    private static void pickSectionFor(final Activity a, final NotesDb db,
                                       final Note n, final long projectId,
                                       final Runnable onFiled) {
        Project p = db.getProject(projectId);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] sheet = new Dialog[1];
        List<Folder> folders = db.foldersFor(projectId);
        for (final Folder f : folders) {
            addSheetLabel(a, content, f.name);
            content.addView(newSectionRow(a, db, n, projectId, f.id,
                    sheet, onFiled));
            for (final Section s : db.sectionsInFolder(f.id)) {
                content.addView(appendRow(a, db, n, s, sheet, onFiled));
            }
        }
        if (!folders.isEmpty()) addSheetLabel(a, content, "Ungrouped");
        else addSheetLabel(a, content, "Pieces");
        content.addView(newSectionRow(a, db, n, projectId, -1, sheet,
                onFiled));
        for (final Section s : db.looseSections(projectId)) {
            content.addView(appendRow(a, db, n, s, sheet, onFiled));
        }
        sheet[0] = Sheet.show(a,
                "File into \u201c"
                        + (p == null ? "project" : p.name) + "\u201d",
                content);
    }

    /** "New piece": the Bucket note becomes a section of its own,
     * titled with the note's title. In a folder, or loose at the top
     * level. Undo deletes the section and restores the note. */
    private static View newSectionRow(final Activity a, final NotesDb db,
                                      final Note n, final long projectId,
                                      final long folderId,
                                      final Dialog[] sheet,
                                      final Runnable onFiled) {
        String sub = folderId >= 0 ? "From this piece, in this folder"
                : "From this piece, at the top level";
        return Sheet.row(a, "New piece", sub,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        fileAsNewSection(a, db, n, projectId, folderId,
                                onFiled);
                    }
                });
    }

    private static View appendRow(final Activity a, final NotesDb db,
                                  final Note n, final Section s,
                                  final Dialog[] sheet,
                                  final Runnable onFiled) {
        int words = s.words;
        return Sheet.row(a, "Add to \u201c" + s.name + "\u201d",
                words + (words == 1 ? " word" : " words") + " so far",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        appendToSection(a, db, n, s, onFiled);
                    }
                });
    }

    private static void fileAsNewSection(final Activity a, final NotesDb db,
                                        final Note n, final long projectId,
                                        final long folderId,
                                        final Runnable onFiled) {
        final Note gone = db.get(n.id);
        if (gone == null) return;
        String name = gone.title == null || gone.title.trim().isEmpty()
                ? "Untitled piece" : gone.title.trim();
        final long sid = db.insertSection(projectId, name);
        if (sid == -1) {
            Toast.makeText(a, "Couldn't file the piece.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (folderId >= 0) {
            db.moveSectionToContainer(sid, folderId,
                    db.sectionsInFolder(folderId).size());
        }
        db.updateSectionBody(sid,
                gone.transcript == null ? "" : gone.transcript);
        db.delete(n.id);
        Project p = db.getProject(projectId);
        if (onFiled != null) onFiled.run();
        UndoBar.undo(a,
                "Sent to \u201c" + (p == null ? "project" : p.name)
                        + "\u201d.",
                new UndoBar.Action() {
                    @Override public void run() {
                        db.deleteSectionQuiet(sid);
                        if (gone != null) {
                            db.insert(gone.title, gone.transcript,
                                    gone.tags, gone.created, null,
                                    gone.words);
                        }
                        if (onFiled != null) onFiled.run();
                    }
                });
    }

    /** Append the note's text to the section's body with a paragraph
     * break. The Bucket note is removed; undo restores both. */
    private static void appendToSection(final Activity a, final NotesDb db,
                                        final Note n, final Section s,
                                        final Runnable onFiled) {
        final Note gone = db.get(n.id);
        final Section target = db.getSection(s.id);
        if (gone == null || target == null) return;
        String add = gone.transcript == null ? ""
                : gone.transcript.trim();
        String cur = target.body == null ? "" : target.body.trim();
        final String merged = cur.isEmpty() ? add
                : add.isEmpty() ? cur : cur + "\n\n" + add;
        db.updateSectionBody(target.id, merged);
        db.delete(n.id);
        if (onFiled != null) onFiled.run();
        UndoBar.undo(a,
                "Added to \u201c" + target.name + "\u201d.",
                new UndoBar.Action() {
                    @Override public void run() {
                        db.updateSectionBody(target.id,
                                target.body == null ? "" : target.body);
                        if (gone != null) {
                            db.insert(gone.title, gone.transcript,
                                    gone.tags, gone.created, null,
                                    gone.words);
                        }
                        if (onFiled != null) onFiled.run();
                    }
                });
    }

    private static void addSheetLabel(Activity a, LinearLayout parent,
                                      String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Ui.CARD_MUTED);
        t.setTextSize(14);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 12);
        lp.bottomMargin = Ui.dp(a, 4);
        parent.addView(t, lp);
    }

    /** Create a project whose first section IS this note (titled
     * with the note's title), in one flow. */
    /** Bulk send: the checked notes go to one project. After the
     * project, the writer picks "new piece per piece" or one
     * existing section for all of them. One undo restores everything. */
    private void onSendSelectedToProject() {
        final List<Note> targets = new ArrayList<Note>();
        for (Object o : notes) {
            if (o instanceof Note
                    && selected.contains(((Note) o).id)) {
                targets.add((Note) o);
            }
        }
        if (targets.isEmpty() && !selected.isEmpty()) {
            for (Long id : selected) {
                Note n = db.get(id);
                if (n != null) targets.add(n);
            }
        }
        if (targets.isEmpty()) return;
        final List<Project> projects = db.allProjects();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] sheet = new Dialog[1];
        for (final Project p : projects) {
            List<Section> secs = db.sectionsFor(p.id);
            String sub = secs.size() + (secs.size() == 1 ? " piece"
                    : " pieces");
            content.addView(Sheet.row(this, p.name, sub,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (sheet[0] != null) sheet[0].dismiss();
                            pickSectionForBulk(targets, p.id);
                        }
                    }));
        }
        content.addView(Sheet.row(this, "New project\u2026", null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        NoteDialogs.newProject(LibraryActivity.this, db, false,
                                new NoteDialogs.ProjectCallback() {
                                    @Override public void onProject(long pid) {
                                        pickSectionForBulk(targets, pid);
                                    }
                                });
                    }
                }));
        sheet[0] = Sheet.show(this, "Move to project", content);
    }

    /** Bulk section picker: one new section per note, or all notes
     * appended to one existing section. */
    private void pickSectionForBulk(final List<Note> targets,
                                    final long projectId) {
        Project p = db.getProject(projectId);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] sheet = new Dialog[1];
        List<Folder> folders = db.foldersFor(projectId);
        for (final Folder f : folders) {
            addSheetLabel(this, content, f.name);
            content.addView(Sheet.row(this, "New piece per piece",
                    "One piece for each piece, in this folder",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (sheet[0] != null) sheet[0].dismiss();
                            fileBulkAsNewSections(targets, projectId,
                                    f.id);
                        }
                    }));
            for (final Section s : db.sectionsInFolder(f.id)) {
                content.addView(bulkAppendRow(targets, s, sheet));
            }
        }
        if (!folders.isEmpty()) addSheetLabel(this, content, "Ungrouped");
        else addSheetLabel(this, content, "Pieces");
        content.addView(Sheet.row(this, "New piece per piece",
                "One piece for each piece, at the top level",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        fileBulkAsNewSections(targets, projectId, -1);
                    }
                }));
        for (final Section s : db.looseSections(projectId)) {
            content.addView(bulkAppendRow(targets, s, sheet));
        }
        sheet[0] = Sheet.show(this,
                "File into \u201c"
                        + (p == null ? "project" : p.name) + "\u201d",
                content);
    }

    private View bulkAppendRow(final List<Note> targets, final Section s,
                               final Dialog[] sheet) {
        int words = s.words;
        return Sheet.row(this,
                "Add all to \u201c" + s.name + "\u201d",
                words + (words == 1 ? " word" : " words") + " so far",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        appendBulkToSection(targets, s);
                    }
                });
    }

    /** Each note becomes its own section. Undo deletes the sections
     * and restores the notes. */
    private void fileBulkAsNewSections(List<Note> targets, long projectId,
                                       long folderId) {
        final List<Note> gone = new ArrayList<Note>();
        final List<Long> sids = new ArrayList<Long>();
        for (Note n : targets) {
            Note full = db.get(n.id);
            if (full == null) continue;
            String name = full.title == null
                    || full.title.trim().isEmpty()
                    ? "Untitled piece" : full.title.trim();
            long sid = db.insertSection(projectId, name);
            if (sid == -1) continue;
            if (folderId >= 0) {
                db.moveSectionToContainer(sid, folderId,
                        db.sectionsInFolder(folderId).size());
            }
            db.updateSectionBody(sid, full.transcript == null
                    ? "" : full.transcript);
            db.delete(full.id);
            gone.add(full);
            sids.add(sid);
        }
        selected.clear();
        refresh();
        if (gone.isEmpty()) return;
        UndoBar.undo(this,
                "Sent " + gone.size()
                        + (gone.size() == 1 ? " piece." : " pieces."),
                new UndoBar.Action() {
                    @Override public void run() {
                        for (long sid : sids) db.deleteSectionQuiet(sid);
                        for (Note n : gone) {
                            db.insert(n.title, n.transcript, n.tags,
                                    n.created, null, n.words);
                        }
                        refresh();
                    }
                });
    }

    /** All notes appended to one section with paragraph breaks. Undo
     * restores the section body and the notes. */
    private void appendBulkToSection(List<Note> targets, Section s) {
        final Section target = db.getSection(s.id);
        if (target == null) return;
        final String oldBody =
                target.body == null ? "" : target.body;
        final List<Note> gone = new ArrayList<Note>();
        StringBuilder merged = new StringBuilder(oldBody.trim());
        for (Note n : targets) {
            Note full = db.get(n.id);
            if (full == null) continue;
            String add = full.transcript == null ? ""
                    : full.transcript.trim();
            if (!add.isEmpty()) {
                if (merged.length() > 0) merged.append("\n\n");
                merged.append(add);
            }
            db.delete(full.id);
            gone.add(full);
        }
        db.updateSectionBody(target.id, merged.toString());
        selected.clear();
        refresh();
        if (gone.isEmpty()) return;
        UndoBar.undo(this,
                "Added " + gone.size()
                        + (gone.size() == 1 ? " piece" : " pieces")
                        + " to \u201c" + target.name + "\u201d.",
                new UndoBar.Action() {
                    @Override public void run() {
                        db.updateSectionBody(target.id, oldBody);
                        for (Note n : gone) {
                            db.insert(n.title, n.transcript, n.tags,
                                    n.created, null, n.words);
                        }
                        refresh();
                    }
                });
    }

    /** Bulk delete of the checked cards: confirm, delete, undo restores
     * every note. Nothing written is lost to a mis-tap. */
    private void onDeleteSelected() {
        final List<Note> doomed = new ArrayList<Note>();
        for (Object o : notes) {
            if (o instanceof Note) {
                Note n = (Note) o;
                if (selected.contains(n.id)) doomed.add(n);
            }
        }
        // Selection can outlive the list (search cleared); fall back to
        // the database so checked-off-screen notes are honored too.
        if (doomed.isEmpty() && !selected.isEmpty()) {
            for (Long id : selected) {
                Note n = db.get(id);
                if (n != null) doomed.add(n);
            }
        }
        if (doomed.isEmpty()) return;
        final int count = doomed.size();
        android.app.AlertDialog themedDlg2 = new AlertDialog.Builder(this)
                .setMessage("Move " + count
                        + (count == 1 ? " piece" : " pieces")
                        + " to trash?")
                .setPositiveButton("Move to trash",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d,
                                                          int which) {
                                final List<Note> gone =
                                        new ArrayList<Note>(doomed);
                                for (Note n : doomed) db.trashNote(n.id);
                                selected.clear();
                                refresh();
                                UndoBar.undo(LibraryActivity.this,
                                        count == 1 ? "Moved to trash."
                                                : count + " pieces moved to trash.",
                                        new UndoBar.Action() {
                                            @Override public void run() {
                                                for (Note n : gone) {
                                                    db.restoreNote(n.id);
                                                }
                                                refresh();
                                            }
                                        });
                            }
                        })
                .setNegativeButton("Cancel", null)
                .create();
        themedDlg2.show();
        Ui.styleDialog(this, themedDlg2);
    }

    private void onDeleteConfirm(final Note n) {
        android.app.AlertDialog themedDlg1 = new AlertDialog.Builder(this)
                .setMessage("Move \u201c" + n.title + "\u201d to trash?")
                .setPositiveButton("Move to trash",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d,
                                                          int which) {
                                final long id = n.id;
                                db.trashNote(id);
                                refresh();
                                UndoBar.undo(LibraryActivity.this,
                                        "Moved to trash.",
                                        new UndoBar.Action() {
                                            @Override public void run() {
                                                db.restoreNote(id);
                                                refresh();
                                            }
                                        });
                            }
                        })
                .setNegativeButton("Cancel", null)
                .create();
        themedDlg1.show();
        Ui.styleDialog(this, themedDlg1);
    }

    /** Rename a note without opening it. */
    /** Per-card overflow menu: Rename, Move to project, Move to trash. */
    private void onCardMenu(final Note n, View anchor) {
        final boolean pro = Pro.isPro(this);
        android.widget.PopupMenu menu =
                new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "Rename");
        menu.getMenu().add(0, 1, 1,
                pro ? "Move to project" : "Move to project (PRO)");
        menu.getMenu().add(0, 2, 2, "Move to trash");
        menu.setOnMenuItemClickListener(
                new android.widget.PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(
                            android.view.MenuItem item) {
                        if (item.getItemId() == 0) {
                            onRename(n);
                        } else if (item.getItemId() == 1) {
                            sendToProject(LibraryActivity.this, db, n,
                                    new Runnable() {
                                        @Override public void run() {
                                            refresh();
                                        }
                                    });
                        } else {
                            onDeleteConfirm(n);
                        }
                        return true;
                    }
                });
        menu.show();
    }

    private void onRename(final Note n) {
        NoteDialogs.rename(this, db, n, new Runnable() {
            @Override public void run() {
                refresh();
            }
        });
    }
}
