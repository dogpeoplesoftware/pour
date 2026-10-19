package com.pour.dictation;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.DragEvent;
import android.view.Gravity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One project: titled sections of writing, grouped into folders.
 * A section is a block of text — tapping it opens the editor, where
 * the writer dictates or types directly. Folders are pure structure:
 * named containers grouping sections, collapsible, never holding
 * text themselves. Sections and folders move by drag-and-drop (drag
 * handle) or the section menu. The writer names the structure
 * themselves — chapters, scenes, topics, beats, or anything else.
 */
public class ProjectActivity extends Activity {

    private NotesDb db;
    private long projectId = -1;
    private BaseAdapter adapter;
    /** Display rows in order: folders and sections interleaved. */
    private final List<Object> rows = new ArrayList<>();
    private final Map<Long, Section> sectionById =
            new HashMap<Long, Section>();
    /** Folders by id, and each folder's sections in order — rebuilt by
     * buildRows() alongside the section map. */
    private final Map<Long, Folder> folderById =
            new HashMap<Long, Folder>();
    private final Map<Long, List<Section>> sectionsByFolder =
            new HashMap<Long, List<Section>>();
    private TextView titleView;
    private TextView statsView;
    private TextView emptyView;
    private ListView list;
    private FrameLayout listWrap;
    /** In-project search: filters pieces by title/body as you type. */
    private EditText searchBox;
    private Button clearSearchBtn;
    /** The insertion line shown while dragging: a 3dp accent rule. */
    private View insertLine;
    /** Outline shown around a folder header when a dragged section
     * would be filed into it. */
    private View folderOutline;
    /** Trash bar shown while dragging (Bucket-style): drop to delete. */
    private LinearLayout trashBar;
    private TextView trashLabel;
    private ImageView trashIcon;
    private boolean trashHover;
    /** In-memory bulk selection for Copy/Export and folder grouping:
     * section ids, cleared on leaving the view. */
    private final Set<Long> selected = new HashSet<Long>();
    /** Compact floating selection strip: every bulk action as a text
     * button, delete last in red. */
    private SelectionBar selectionBar;
    /** Folders check off as units (for export/copy); their sections
     * are expanded at collection time. */
    private final Set<Long> selectedFolders = new HashSet<Long>();

    private static final int TYPE_SECTION = 0;
    private static final int TYPE_FOLDER = 1;

    /** What is currently being dragged, or null. */
    private DragState drag;
    /** Last drag coordinates, for auto-scroll + the insertion line. */
    private float dragX;
    private float dragY;
    private final Handler dragHandler = new Handler(Looper.getMainLooper());
    private Runnable autoScrollTask = null;

    private static class DragState {
        boolean isSection;
        boolean isFolder;
        long sectionId;      // dragged section
        long folderId = -1;  // dragged folder (when isFolder)
        // Folder drags: the merged top-level order before the drop.
        List<NotesDb.TopItem> fromTopOrder = null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.initTheme(this);
        db = new NotesDb(this);
        projectId = getIntent().getLongExtra("project_id", -1);
        if (db.getProject(projectId) == null) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        Ui.applySafeInsets(this, root, 20);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        // Back arrow: explicit way up to the Projects list.
        headerRow.addView(Ui.headerIconButton(this, R.drawable.ic_back,
                "Back to Projects",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        finish();
                    }
                }));
        titleView = new TextView(this);
        titleView.setTextSize(24);
        titleView.setTextColor(Ui.TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setContentDescription("Rename project");
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onRenameProject();
            }
        });
        headerRow.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(Ui.headerIconButton(this, R.drawable.ic_export,
                "Share project",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onShareProject();
                    }
                }));
        headerRow.addView(Ui.headerIconButton(this, R.drawable.ic_more,
                "More actions",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onOverflow();
                    }
                }));
        root.addView(headerRow);

        // Subtle meta line under the title: "N pieces · M words".
        statsView = new TextView(this);
        statsView.setTextColor(Ui.MUTED);
        statsView.setTextSize(14);
        LinearLayout.LayoutParams statsParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        statsParams.topMargin = Ui.dp(this, 4);
        statsView.setLayoutParams(statsParams);
        root.addView(statsView);

        // In-project search: same pattern as the Bucket. Filters the
        // piece list by title/body as you type; clearing restores all.
        searchBox = new EditText(this);
        searchBox.setHint("Search this project\u2026");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Ui.CARD_TEXT);
        searchBox.setHintTextColor(Ui.CARD_MUTED);
        searchBox.setBackground(Ui.card(this, 16));
        int ssp = Ui.dp(this, 16);
        searchBox.setPadding(ssp, Ui.dp(this, 12), ssp, Ui.dp(this, 12));

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

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a,
                    int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a,
                    int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });

        listWrap = new FrameLayout(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = Ui.dp(this, 12);
        root.addView(listWrap, listParams);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(Ui.BG));
        list.setDividerHeight(Ui.dp(this, 12));
        list.setFastScrollEnabled(true);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int p) { return rows.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int p) {
                return rows.get(p) instanceof Folder
                        ? TYPE_FOLDER : TYPE_SECTION;
            }

            @Override
            public View getView(int position, View convertView,
                                ViewGroup parent) {
                Object row = rows.get(position);
                if (row instanceof Folder) {
                    return folderView((Folder) row, convertView);
                }
                return sectionView((Section) row, convertView);
            }
        };
        list.setAdapter(adapter);
        listWrap.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        insertLine = new View(this);
        insertLine.setBackgroundColor(Ui.ACCENT);
        insertLine.setVisibility(View.GONE);
        insertLine.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        listWrap.addView(insertLine, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 3),
                Gravity.TOP));

        // Folder outline: a stroked rounded rect shown around a
        // folder header when the dragged section would land inside it.
        folderOutline = new View(this);
        android.graphics.drawable.GradientDrawable outlineBg =
                new android.graphics.drawable.GradientDrawable();
        outlineBg.setShape(
                android.graphics.drawable.GradientDrawable.RECTANGLE);
        outlineBg.setStroke(Ui.dp(this, 2), Ui.ACCENT);
        outlineBg.setCornerRadius(Ui.dp(this, 12));
        folderOutline.setBackground(outlineBg);
        folderOutline.setVisibility(View.GONE);
        folderOutline.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        listWrap.addView(folderOutline, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // Trash bar: visible only while dragging. Drop here deletes
        // the section or folder with undo, no confirmation.
        trashBar = new LinearLayout(this);
        trashBar.setOrientation(LinearLayout.HORIZONTAL);
        trashBar.setGravity(Gravity.CENTER);
        trashIcon = new ImageView(this);
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
        FrameLayout.LayoutParams trashParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        trashParams.bottomMargin = Ui.dp(this, 16);
        listWrap.addView(trashBar, trashParams);

        emptyView = new TextView(this);
        emptyView.setTextColor(Ui.MUTED);
        emptyView.setGravity(Gravity.CENTER);
        root.addView(emptyView);
        list.setEmptyView(emptyView);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                Object row = rows.get(position);
                if (row instanceof Folder) {
                    onToggleFolderCollapse((Folder) row);
                } else if (row instanceof Section) {
                    openSectionEditor((Section) row, false);
                }
            }
        });

        list.setOnItemLongClickListener(
                new AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(AdapterView<?> parent,
                                                   View view, int position,
                                                   long id) {
                        // No dragging while searching: the list is a
                        // flat filtered view, not the real order.
                        if (searchBox != null && !searchBox.getText()
                                .toString().trim().isEmpty()) {
                            return false;
                        }
                        // Press-and-hold lifts the row for drag (menus
                        // moved to the per-row overflow button).
                        Object row = rows.get(position);
                        if (row instanceof Folder) {
                            startFolderDrag((Folder) row, view);
                        } else {
                            startSectionDrag((Section) row, view);
                        }
                        return true;
                    }
                });

        list.setOnDragListener(new View.OnDragListener() {
            @Override public boolean onDrag(View v, DragEvent e) {
                return onListDrag(e);
            }
        });

        root.addView(Ui.tabRow(this, Ui.TAB_PROJECTS));

        // Bottom bar: [+ Piece] primary, [+ Folder] secondary.
        // Placed above the tab row for thumb reach. Overflow lives in
        // the top bar only (no duplicate ⋮ down here).
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bbParams.topMargin = Ui.dp(this, 12);
        bottomBar.setLayoutParams(bbParams);
        Button addPieceBtn = new Button(this);
        addPieceBtn.setText("+ Piece");
        Ui.stylePrimaryBtn(this, addPieceBtn);
        LinearLayout.LayoutParams apbParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        apbParams.rightMargin = Ui.dp(this, 8);
        addPieceBtn.setLayoutParams(apbParams);
        addPieceBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onAddSection(); }
        });
        bottomBar.addView(addPieceBtn);
        Button addFolderBtn = new Button(this);
        addFolderBtn.setText("+ Folder");
        Ui.styleBtn(this, addFolderBtn);
        LinearLayout.LayoutParams afbParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        addFolderBtn.setLayoutParams(afbParams);
        addFolderBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onAddFolder(); }
        });
        bottomBar.addView(addFolderBtn);
        // Insert before the tab row (which is the last view so far).
        root.addView(bottomBar, root.getChildCount() - 1);

        // Floating selection toolbar: visible while anything is
        // checked. Wrapped in a FrameLayout so it floats above the
        // tab row without taking layout space.
        java.util.List<SelectionBar.Action> barActions =
                new java.util.ArrayList<SelectionBar.Action>();
        barActions.add(new SelectionBar.Action() {
            @Override public String label() { return "Copy"; }
            @Override public boolean visible() { return true; }
            @Override public boolean destructive() { return false; }
            @Override public void run() { onCopyProject(); }
        });
        barActions.add(new SelectionBar.Action() {
            @Override public String label() { return "Share"; }
            @Override public boolean visible() { return true; }
            @Override public boolean destructive() { return false; }
            @Override public void run() { onShareProject(); }
        });
        barActions.add(new SelectionBar.Action() {
            @Override public String label() { return "Group"; }
            @Override public boolean visible() {
                return groupableSectionIds().size() >= 2;
            }
            @Override public boolean destructive() { return false; }
            @Override public void run() { onGroupIntoFolder(); }
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
                        selectedFolders.clear();
                        refresh();
                    }
                });
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        int navInset = 0;
        try {
            android.view.WindowInsets insets =
                    getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                navInset = insets.getSystemWindowInsetBottom();
            }
        } catch (Exception e) {
            Log.w("ProjectActivity", "Suppressed exception", e);
        }
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pillParams.bottomMargin = navInset + Ui.dp(this, 84);
        frame.addView(selectionBar.view(), pillParams);

        setContentView(frame);
    }

    /** Open the section editor. fresh marks a just-created section:
     * leaving it empty deletes it quietly. */
    private void openSectionEditor(Section s, boolean fresh) {
        Intent i = new Intent(this, SectionActivity.class);
        i.putExtra("section_id", s.id);
        i.putExtra("fresh_section", fresh);
        if (fresh) i.putExtra("focus_body", true);
        startActivity(i);
    }

    // ---------------- section rows: writing ----------------

    private static class SectionHolder {
        TextView titleView;
        TextView snippetView;
        TextView metaView;
        ImageButton selectBtn;
        ImageButton overflowBtn;
    }

    /** Sections render as writing, never as structure: bold title, a
     * two-line snippet of the body, and the word count. No chevron —
     * sections are leaf text blocks and no longer collapse. */
    private LinearLayout createSectionRow() {
        SectionHolder h = new SectionHolder();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.card(this, 16));
        int cp = Ui.dp(this, 16);
        row.setPadding(cp, cp, cp, cp);
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        h.titleView = new TextView(this);
        h.titleView.setTextColor(Ui.CARD_TEXT);
        h.titleView.setTextSize(16);
        h.titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.titleView.setSingleLine(true);
        h.titleView.setEllipsize(
                android.text.TextUtils.TruncateAt.END);
        h.snippetView = new TextView(this);
        h.snippetView.setTextColor(Ui.CARD_MUTED);
        h.snippetView.setTextSize(14);
        h.snippetView.setLineSpacing(0, 1.3f);
        h.snippetView.setMaxLines(2);
        h.snippetView.setEllipsize(
                android.text.TextUtils.TruncateAt.END);
        h.metaView = new TextView(this);
        h.metaView.setTextColor(Ui.CARD_MUTED);
        h.metaView.setTextSize(14);
        textCol.addView(h.titleView);
        textCol.addView(h.snippetView);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = Ui.dp(this, 4);
        textCol.addView(h.metaView, mp);
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        // Checkbox on the left.
        h.selectBtn = iconButton(R.drawable.ic_box,
                "Select piece");
        h.selectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Section s = (Section) v.getTag();
                if (s == null) return;
                if (selected.contains(s.id)) {
                    selected.remove(s.id);
                } else {
                    selected.add(s.id);
                }
                refresh();
                list.announceForAccessibility(selected.contains(s.id)
                        ? "Piece selected." : "Piece deselected.");
            }
        });
        row.addView(h.selectBtn);
        row.addView(textCol, tcp);
        // Overflow menu on the right: the section actions.
        h.overflowBtn = iconButton(R.drawable.ic_more,
                "Piece options");
        h.overflowBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Section s = (Section) v.getTag();
                if (s != null) onSectionMenu(s, v);
            }
        });
        row.addView(h.overflowBtn);
        row.setTag(h);
        row.setLayoutParams(new AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void bindSectionRow(View row, Section s) {
        SectionHolder h = (SectionHolder) row.getTag();
        // Sections nested in a folder are inset so the hierarchy reads
        // at a glance; loose top-level sections stay flush. Applied on
        // every bind because rows are recycled across containers.
        // 16dp is the card's own padding; nested sections add 24dp indent.
        row.setPadding(Ui.dp(this, 16 + (s.folderId != -1 ? 24 : 0)),
                Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        h.overflowBtn.setTag(s);
        h.selectBtn.setTag(s);
        String name = s.name == null || s.name.trim().isEmpty()
                ? "Untitled piece" : s.name.trim();
        h.titleView.setText(name);
        String snippet = snippetFor(s);
        if (snippet.isEmpty()) {
            h.snippetView.setText("Empty piece. Tap to write.");
        } else {
            h.snippetView.setText(snippet);
        }
        // Titles stay fixed so the hierarchy survives the text-size
        // setting; only reading text (the snippet) scales.
        h.titleView.setTextSize(16);
        h.snippetView.setTextSize(14);
        int words = s.words;
        h.metaView.setText(words + (words == 1 ? " word" : " words"));
        boolean checked = selected.contains(s.id);
        h.selectBtn.setImageResource(checked ? R.drawable.ic_box_checked
                : R.drawable.ic_box);
        h.selectBtn.setColorFilter(checked ? Ui.ACCENT : Ui.MUTED);
        h.selectBtn.setContentDescription(checked
                ? "Deselect piece" : "Select piece");
        row.setContentDescription(name + ", " + words
                + (words == 1 ? " word" : " words")
                + ". Tap to write. Long-press for more actions.");
        // The dragged section dims so the finger's shadow reads as lifted.
        row.setAlpha(drag != null && drag.isSection
                && drag.sectionId == s.id ? 0.35f : 1f);
    }

    /** First ~140 characters of the body, whitespace-collapsed. */
    private static String snippetFor(Section s) {
        if (s.body == null) return "";
        String t = s.body.replaceAll("\\s+", " ").trim();
        return t.length() > 140 ? t.substring(0, 140) + "\u2026" : t;
    }

    private View sectionView(final Section s, View convertView) {
        LinearLayout row;
        if (convertView == null
                || !(convertView.getTag() instanceof SectionHolder)) {
            row = createSectionRow();
        } else {
            row = (LinearLayout) convertView;
        }
        bindSectionRow(row, s);
        return row;
    }

    // ---------------- folder rows: structure ----------------

    private static class FolderHolder {
        ImageView chevron;
        ImageView icon;
        TextView titleView;
        TextView countView;
        ImageButton selectBtn;
        ImageButton overflowBtn;
    }

    /** Folder headers are flat like section rows but read as the
     * level above: larger type, an accent folder icon, and a
     * sections/words count. Folders never hold text — only sections. */
    private LinearLayout createFolderRow() {
        FolderHolder h = new FolderHolder();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int cp = Ui.dp(this, 16);
        row.setPadding(cp, cp, cp, cp);
        row.setBackground(Ui.card(this, 16));
        // Checkbox on the left.
        h.selectBtn = iconButton(R.drawable.ic_box,
                "Select folder");
        h.selectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Folder f = (Folder) v.getTag();
                if (f == null) return;
                // The checkbox selects the folder itself — not its
                // sections. Export/copy expand a checked folder into
                // its sections at collection time.
                if (selectedFolders.contains(f.id)) {
                    selectedFolders.remove(f.id);
                } else {
                    selectedFolders.add(f.id);
                }
                refresh();
                list.announceForAccessibility(selectedFolders.contains(f.id)
                        ? "Folder selected." : "Folder deselected.");
            }
        });
        row.addView(h.selectBtn);
        h.chevron = new ImageView(this);
        h.chevron.setImageResource(R.drawable.ic_chevron);
        h.chevron.setColorFilter(Ui.MUTED);
        h.chevron.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int cs = Ui.dp(this, 24);
        row.addView(h.chevron, new LinearLayout.LayoutParams(cs, cs));
        h.icon = new ImageView(this);
        h.icon.setImageResource(R.drawable.ic_folder);
        h.icon.setColorFilter(Ui.ACCENT);
        h.icon.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // The folder icon reads a step larger than the chevron: the
        // folder is the top of the hierarchy at a glance.
        int is = Ui.dp(this, 28);
        LinearLayout.LayoutParams icp =
                new LinearLayout.LayoutParams(is, is);
        icp.leftMargin = Ui.dp(this, 4);
        row.addView(h.icon, icp);
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        h.titleView = new TextView(this);
        h.titleView.setTextColor(Ui.CARD_TEXT);
        h.titleView.setTextSize(20);
        h.titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.titleView.setSingleLine(true);
        h.titleView.setEllipsize(
                android.text.TextUtils.TruncateAt.END);
        h.countView = new TextView(this);
        h.countView.setTextColor(Ui.CARD_MUTED);
        h.countView.setTextSize(14);
        textCol.addView(h.titleView);
        textCol.addView(h.countView);
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tcp.leftMargin = Ui.dp(this, 8);
        row.addView(textCol, tcp);
        // Overflow menu on the right: the folder actions.
        h.overflowBtn = iconButton(R.drawable.ic_more,
                "Folder options");
        h.overflowBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Folder f = (Folder) v.getTag();
                if (f != null) onFolderMenu(f, v);
            }
        });
        row.addView(h.overflowBtn);
        row.setTag(h);
        row.setLayoutParams(new AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void bindFolderRow(View row, Folder f) {
        FolderHolder h = (FolderHolder) row.getTag();
        h.overflowBtn.setTag(f);
        h.selectBtn.setTag(f);
        boolean collapsed = f.collapsed != 0;
        h.titleView.setText(f.name);
        List<Section> inner = sectionsByFolder.get(f.id);
        int sc = inner == null ? 0 : inner.size();
        int words = 0;
        if (inner != null) {
            for (Section s : inner) {
                words += s.words;
            }
        }
        boolean checked = selectedFolders.contains(f.id);
        h.selectBtn.setImageResource(checked ? R.drawable.ic_box_checked
                : R.drawable.ic_box);
        h.selectBtn.setColorFilter(checked ? Ui.ACCENT : Ui.MUTED);
        h.selectBtn.setContentDescription(checked
                ? "Deselect folder" : "Select folder");
        h.countView.setText(sc == 0
                ? "Empty folder. Add pieces with + Piece, or move pieces here."
                : sc + (sc == 1 ? " piece" : " pieces") + " \u00B7 "
                        + words + (words == 1 ? " word" : " words")
                        + (collapsed ? " \u00B7 collapsed" : ""));
        h.chevron.setRotation(collapsed ? -90f : 0f);
        row.setContentDescription("Folder " + f.name + ", "
                + (collapsed ? "collapsed" : "expanded") + ", " + sc
                + (sc == 1 ? " piece" : " pieces") + ". Tap to "
                + (collapsed ? "expand" : "collapse") + ".");
        row.setAlpha(drag != null && drag.isFolder
                && drag.folderId == f.id ? 0.35f : 1f);
    }

    private View folderView(final Folder f, View convertView) {
        LinearLayout row;
        if (convertView == null
                || !(convertView.getTag() instanceof FolderHolder)) {
            row = createFolderRow();
        } else {
            row = (LinearLayout) convertView;
        }
        bindFolderRow(row, f);
        return row;
    }

    private void onToggleFolderCollapse(Folder f) {
        boolean nowCollapsed = f.collapsed == 0;
        db.setFolderCollapsed(f.id, nowCollapsed ? 1 : 0);
        refresh();
        list.announceForAccessibility(f.name
                + (nowCollapsed ? " collapsed." : " expanded."));
    }

    // ---------------- group into folder ----------------

    /** Checked section ids in display order — folder checkboxes
     * select the folder itself (for export), so grouping only ever
     * sees individually checked sections. */
    private List<Long> groupableSectionIds() {
        List<Long> ids = new ArrayList<Long>();
        for (NotesDb.TopItem t : db.topLevelOrder(projectId)) {
            if (t.isFolder) {
                List<Section> inner = sectionsByFolder.get(t.id);
                if (inner == null) continue;
                for (Section s : inner) {
                    if (selected.contains(s.id)) ids.add(s.id);
                }
            } else {
                if (selected.contains(t.id)) ids.add(t.id);
            }
        }
        return ids;
    }

    /** The group bar: name the folder, group the checked sections, offer
     * undo. Undo dissolves the new folder, puts every grouped section
     * back in its original container and slot, and restores the exact
     * prior top-level order. */
    private void onGroupIntoFolder() {
        final List<Long> ids = groupableSectionIds();
        if (ids.size() < 2) return;
        NoteDialogs.textInput(this, "New folder",
                "Name the folder", "",
                new NoteDialogs.TextCallback() {
                    @Override public void onText(String t) {
                        String name = t.isEmpty() ? "Untitled folder" : t;
                        final List<NotesDb.TopItem> before =
                                db.topLevelOrder(projectId);
                        // Per-section home containers, for an exact undo.
                        // ids are in display order, so restoring in this
                        // order with the original indices rebuilds every
                        // container exactly.
                        final long[] oldFolders = new long[ids.size()];
                        final int[] oldIndices = new int[ids.size()];
                        for (int i = 0; i < ids.size(); i++) {
                            Section s = db.getSection(ids.get(i));
                            long of = s == null ? -1 : s.folderId;
                            oldFolders[i] = of;
                            List<Section> cont = of >= 0
                                    ? db.sectionsInFolder(of)
                                    : db.looseSections(projectId);
                            int idx = 0;
                            for (int j = 0; j < cont.size(); j++) {
                                if (cont.get(j).id == ids.get(i)) {
                                    idx = j;
                                    break;
                                }
                            }
                            oldIndices[i] = idx;
                        }
                        final long fid = db.groupSectionsIntoFolder(
                                projectId, ids, name);
                        refresh();
                        if (fid == -1) {
                            Toast.makeText(ProjectActivity.this,
                                    "Couldn't create the folder.",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        UndoBar.undo(ProjectActivity.this,
                                "Folder created.",
                                new UndoBar.Action() {
                                    @Override public void run() {
                                        db.ungroupFolder(projectId, fid);
                                        for (int i = 0; i < ids.size();
                                             i++) {
                                            db.moveSectionToContainer(
                                                    ids.get(i),
                                                    oldFolders[i],
                                                    oldIndices[i]);
                                        }
                                        db.reorderTopLevel(projectId,
                                                before);
                                        refresh();
                                    }
                                });
                        list.announceForAccessibility(
                                "Folder " + name + " created with "
                                        + ids.size() + " pieces.");
                        scrollToFolder(fid);
                    }
                });
    }

    /** Bring a folder header into view. */
    private void scrollToFolder(long id) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row instanceof Folder && ((Folder) row).id == id) {
                list.smoothScrollToPosition(i);
                return;
            }
        }
    }

    /**
     * Row icon button. Not focusable (a focusable child steals taps and
     * long-presses from the ListView — the 1.0.1 lesson), 48dp for the
     * accessibility target size.
     */
    private ImageButton iconButton(int resId, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(resId);
        b.setContentDescription(desc);
        b.setBackground(Ui.selectable(this));
        b.setColorFilter(Ui.MUTED);
        b.setFocusable(false);
        b.setFocusableInTouchMode(false);
        int s = Ui.dp(this, 48);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Ui.dp(this, 12);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    // ---------------- list state ----------------

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.syncTheme(this)) return;
        if (db.getProject(projectId) == null) {
            finish();
            return;
        }
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Selection is a working set, not state: leaving the view clears
        // it so the next visit starts unselected.
        selected.clear();
        selectedFolders.clear();
    }

    private void refresh() {
        Project p = db.getProject(projectId);
        if (p == null) {
            finish();
            return;
        }
        titleView.setText(p.name);
        buildRows();
        adapter.notifyDataSetChanged();
        String q = searchBox != null
                ? searchBox.getText().toString().trim() : "";
        boolean searching = !q.isEmpty();
        clearSearchBtn.setVisibility(
                searching ? View.VISIBLE : View.GONE);
        int words = db.projectWordCount(projectId);
        // Reuse the maps already populated by buildRows() — no extra queries.
        int sectionCount = sectionById.size();
        int folderCount = folderById.size();
        statsView.setText(words + (words == 1 ? " word" : " words")
                + " \u00B7 " + sectionCount
                + (sectionCount == 1 ? " piece" : " pieces")
                + (folderCount == 0 ? ""
                        : " \u00B7 " + folderCount
                                + (folderCount == 1 ? " folder"
                                        : " folders")));
        if (selectionBar != null) {
            selectionBar.update(selected.size() + selectedFolders.size());
        }
        emptyView.setText(sectionCount == 0
                ? (searching ? "No pieces match."
                        : "This project is empty.\nAdd a piece to start writing.")
                : "");
    }

    private void buildRows() {
        rows.clear();
        sectionById.clear();
        folderById.clear();
        sectionsByFolder.clear();
        String q = searchBox != null
                ? searchBox.getText().toString().trim() : "";
        if (!q.isEmpty()) {
            // Search mode: flat list of matching pieces. The folder
            // hierarchy is flattened — search cuts across organization.
            // A piece matches on its title/body, or when its folder's
            // name matches.
            String ql = q.toLowerCase();
            java.util.Set<Long> matchingFolderIds =
                    new java.util.HashSet<Long>();
            for (Folder f : db.foldersFor(projectId)) {
                if (f.name != null
                        && f.name.toLowerCase().contains(ql)) {
                    matchingFolderIds.add(f.id);
                }
            }
            java.util.Set<Long> seen = new java.util.HashSet<Long>();
            for (Section s : db.searchSectionsInProject(projectId, q)) {
                rows.add(s);
                sectionById.put(s.id, s);
                seen.add(s.id);
            }
            if (!matchingFolderIds.isEmpty()) {
                for (Section s : db.sectionHeadersFor(projectId)) {
                    if (!seen.contains(s.id)
                            && matchingFolderIds.contains(s.folderId)) {
                        rows.add(s);
                        sectionById.put(s.id, s);
                    }
                }
            }
            return;
        }
        // Two queries total: all folders + all section headers. Build the
        // hierarchy in memory instead of N+1 individual lookups.
        List<Folder> folders = db.foldersFor(projectId);
        List<Section> allSections = db.sectionHeadersFor(projectId);
        Map<Long, Folder> folderMap = new HashMap<Long, Folder>();
        for (Folder f : folders) {
            folderById.put(f.id, f);
            folderMap.put(f.id, f);
            sectionsByFolder.put(f.id, new ArrayList<Section>());
        }
        List<Section> loose = new ArrayList<Section>();
        for (Section s : allSections) {
            sectionById.put(s.id, s);
            if (s.folderId != -1 && folderMap.containsKey(s.folderId)) {
                sectionsByFolder.get(s.folderId).add(s);
            } else {
                loose.add(s);
            }
        }
        // Merge folders and loose sections by pos (same as
        // NotesDb.topLevelOrder, but from in-memory lists).
        int i = 0, j = 0;
        while (i < folders.size() || j < loose.size()) {
            boolean takeFolder;
            if (j >= loose.size()) {
                takeFolder = true;
            } else if (i >= folders.size()) {
                takeFolder = false;
            } else {
                takeFolder = folders.get(i).pos <= loose.get(j).pos;
            }
            if (takeFolder) {
                Folder f = folders.get(i++);
                rows.add(f);
                if (f.collapsed != 0) continue;
                for (Section s : sectionsByFolder.get(f.id)) {
                    rows.add(s);
                }
            } else {
                rows.add(loose.get(j++));
            }
        }
    }

    // ---------------- drag and drop ----------------

    private void haptic(boolean grab) {
        try {
            if (grab) {
                if (Build.VERSION.SDK_INT >= 34) {
                    list.performHapticFeedback(
                            android.view.HapticFeedbackConstants.DRAG_START);
                } else {
                    list.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS);
                }
            } else {
                if (Build.VERSION.SDK_INT >= 30) {
                    list.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CONFIRM);
                } else {
                    list.performHapticFeedback(
                            android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                }
            }
        } catch (Exception e) {
            Log.w("ProjectActivity", "Suppressed exception", e);
        }
    }

    private void startSectionDrag(Section s, View handle) {
        DragState st = new DragState();
        st.isSection = true;
        st.sectionId = s.id;
        beginDrag(handle, st);
    }

    private void startFolderDrag(Folder f, View handle) {
        DragState st = new DragState();
        st.isFolder = true;
        st.folderId = f.id;
        st.fromTopOrder = db.topLevelOrder(projectId);
        beginDrag(handle, st);
    }

    private void beginDrag(View handle, DragState st) {
        drag = st;
        ClipData data = ClipData.newPlainText("pour-pro-drag", "drag");
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(handle);
        boolean ok;
        if (Build.VERSION.SDK_INT >= 24) {
            ok = handle.startDragAndDrop(data, shadow, st, 0);
        } else {
            ok = handle.startDrag(data, shadow, st, 0);
        }
        if (!ok) {
            drag = null;
            return;
        }
        haptic(true);
        trashHover = false;
        if (trashBar != null) {
            trashBar.setVisibility(View.VISIBLE);
            setTrashHover(false);
        }
        // No stale coordinates: the line stays hidden until the
        // finger actually moves.
        dragX = -1;
        dragY = -1;
        if (insertLine != null) insertLine.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private boolean onListDrag(DragEvent e) {
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                dragX = e.getX();
                dragY = e.getY();
                updateProjectTrashHover();
                if (!trashHover) updateInsertLine();
                else if (insertLine != null) {
                    insertLine.setVisibility(View.GONE);
                }
                kickAutoScroll();
                return true;
            case DragEvent.ACTION_DROP:
                dragX = e.getX();
                dragY = e.getY();
                updateProjectTrashHover();
                if (trashHover) {
                    commitTrashDrop();
                } else {
                    commitDrop();
                }
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                endDrag();
                return true;
            default:
                return false;
        }
    }

    /** Trash bar hit-test: the bar lives in listWrap, the drag
     * coordinates are list-relative. */
    private void updateProjectTrashHover() {
        if (trashBar == null
                || trashBar.getVisibility() != View.VISIBLE
                || list == null) {
            return;
        }
        float bx = dragX + list.getLeft();
        float by = dragY + list.getTop();
        boolean over = bx >= trashBar.getLeft()
                && bx <= trashBar.getRight()
                && by >= trashBar.getTop()
                && by <= trashBar.getBottom();
        if (over != trashHover) setTrashHover(over);
    }

    private void setTrashHover(boolean over) {
        trashHover = over;
        if (trashBar == null) return;
        int red = 0xFFE53935;
        trashLabel.setText(over ? "Release to trash" : "Trash");
        trashLabel.setTextColor(over ? red : Ui.MUTED);
        trashIcon.setColorFilter(over ? red : Ui.MUTED);
        if (over) {
            try {
                list.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CLOCK_TICK);
            } catch (Exception e) {
                Log.w("ProjectActivity", "Suppressed exception", e);
            }
        }
    }

    /** Show the insertion line where the dragged item would land. */
    private void updateInsertLine() {
        if (drag == null || list == null) return;
        if (trashHover) {
            insertLine.setVisibility(View.GONE);
            hideFolderOutline();
            return;
        }
        // No coordinates yet (drag just started): keep hidden.
        if (dragX < 0 || dragY < 0) {
            insertLine.setVisibility(View.GONE);
            hideFolderOutline();
            return;
        }
        int pos = list.pointToPosition((int) dragX, (int) dragY);
        if (pos == AdapterView.INVALID_POSITION || pos >= rows.size()) {
            // Above the first row or below the last: the Y decides.
            hideFolderOutline();
            if (isProjectDragAboveFirst()) {
                showLineAtListTop();
            } else {
                showLineAtListEnd();
            }
            return;
        }
        int first = list.getFirstVisiblePosition();
        View child = list.getChildAt(pos - first);
        if (child == null) {
            insertLine.setVisibility(View.GONE);
            hideFolderOutline();
            return;
        }
        if (drag.isFolder) {
            hideFolderOutline();
            showFolderDropLine(pos, first);
            return;
        }
        Object row = rows.get(pos);
        if (row instanceof Folder) {
            // Dropping onto a folder files the section into it at
            // its end — unless the finger is on the header's top
            // edge, which targets the end of the container above.
            // The outline marks the "into the folder" target.
            if (isOnFolderTopEdge(child)) {
                hideFolderOutline();
                insertLine.setTranslationY(
                        child.getTop() - Ui.dp(this, 1));
                insertLine.setVisibility(View.VISIBLE);
            } else {
                insertLine.setVisibility(View.GONE);
                showFolderOutline(child);
            }
            return;
        }
        hideFolderOutline();
        // Section rows: the line goes at the top edge, or the bottom
        // edge when the finger is in the row's bottom half.
        float lineY = child.getTop() - Ui.dp(this, 1);
        if (dragY > child.getTop() + child.getHeight() / 2f) {
            lineY = child.getBottom() - Ui.dp(this, 1);
        }
        insertLine.setTranslationY(lineY);
        insertLine.setVisibility(View.VISIBLE);
    }

    /** True when the finger is on the top edge of a folder header:
     * the drop targets the end of the container above the folder. */
    private boolean isOnFolderTopEdge(View header) {
        if (header == null) return false;
        float edge = Math.max(Ui.dp(this, 12),
                header.getHeight() * 0.25f);
        return dragY < header.getTop() + edge;
    }

    /** Show the outline around a folder header: the dragged section
     * would be filed into this folder. */
    private void showFolderOutline(View header) {
        if (folderOutline == null || header == null || list == null) return;
        // Header is list-relative; the outline lives in listWrap.
        // Clamp to the visible area so the top never clips.
        float rawY = header.getTop() + list.getTop() - Ui.dp(this, 4);
        float y = Math.max(0, rawY);
        float h = header.getHeight() + Ui.dp(this, 8) - (y - rawY);
        folderOutline.setTranslationY(y);
        android.view.ViewGroup.LayoutParams lp =
                folderOutline.getLayoutParams();
        lp.height = (int) h;
        folderOutline.setLayoutParams(lp);
        folderOutline.setVisibility(View.VISIBLE);
    }

    private void hideFolderOutline() {
        if (folderOutline != null) folderOutline.setVisibility(View.GONE);
    }

    /** True when the finger is above the first row and the list
     * isn't scrolled: a genuine "top of the list" gesture. */
    private boolean isProjectDragAboveFirst() {
        if (list == null || list.getChildCount() == 0) return true;
        View first = list.getChildAt(0);
        return dragY < first.getTop() + first.getHeight() / 2f;
    }

    /** The insertion line at the very top of the list. */
    private void showLineAtListTop() {
        if (list == null || list.getChildCount() == 0) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        View first = list.getChildAt(0);
        insertLine.setTranslationY(first.getTop() - Ui.dp(this, 1));
        insertLine.setVisibility(View.VISIBLE);
    }

    /** The insertion line at the very end of the list. */
    private void showLineAtListEnd() {
        View last = list.getChildAt(list.getChildCount() - 1);
        if (last == null) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        insertLine.setTranslationY(last.getBottom() - Ui.dp(this, 1));
        insertLine.setVisibility(View.VISIBLE);
    }

    /** Row indices that start a top-level block: every folder header
     * and every loose section header. Everything between two starts
     * belongs to the first one's block. */
    private List<Integer> topLevelBlockStarts() {
        List<Integer> starts = new ArrayList<Integer>();
        for (int i = 0; i < rows.size(); i++) {
            Object r = rows.get(i);
            if (r instanceof Folder) {
                starts.add(i);
            } else if (r instanceof Section
                    && ((Section) r).folderId < 0) {
                starts.add(i);
            }
        }
        return starts;
    }

    /**
     * The folder-drag drop slot as a top-level insert index (0..N), or
     * -1 when the block's rows aren't laid out. Each top-level item is
     * one block; the finger above a block's midpoint targets its top
     * boundary, below targets its bottom boundary.
     */
    private int folderDropSlot(int pos, int first) {
        List<Integer> starts = topLevelBlockStarts();
        if (starts.isEmpty()) return -1;
        int block = 0;
        for (int b = 0; b < starts.size(); b++) {
            if (starts.get(b) <= pos) block = b;
            else break;
        }
        int blockFirst = starts.get(block);
        int blockLast = block + 1 < starts.size()
                ? starts.get(block + 1) - 1 : rows.size() - 1;
        View firstV = list.getChildAt(blockFirst - first);
        View lastV = list.getChildAt(blockLast - first);
        if (firstV == null || lastV == null) return -1;
        boolean above = dragY
                < (firstV.getTop() + lastV.getBottom()) / 2f;
        return above ? block : block + 1;
    }

    /** Snap the insertion line to the top-level block boundary the
     * dragged folder would land on. */
    private void showFolderDropLine(int pos, int first) {
        int slot = folderDropSlot(pos, first);
        if (slot < 0) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        List<Integer> starts = topLevelBlockStarts();
        float y;
        if (slot < starts.size()) {
            View v = list.getChildAt(starts.get(slot) - first);
            if (v == null) {
                insertLine.setVisibility(View.GONE);
                return;
            }
            y = v.getTop();
        } else {
            View last = list.getChildAt(list.getChildCount() - 1);
            if (last == null) {
                insertLine.setVisibility(View.GONE);
                return;
            }
            y = last.getBottom();
        }
        insertLine.setTranslationY(y - Ui.dp(this, 1));
        insertLine.setVisibility(View.VISIBLE);
    }

    private void kickAutoScroll() {
        if (autoScrollTask != null || drag == null) return;
        autoScrollTask = new Runnable() {
            @Override public void run() {
                autoScrollTask = null;
                if (drag == null || list == null) return;
                int edge = Ui.dp(ProjectActivity.this, 72);
                int step = Ui.dp(ProjectActivity.this, 28);
                int first = list.getFirstVisiblePosition();
                View top = list.getChildAt(0);
                View bottom = list.getChildAt(list.getChildCount() - 1);
                boolean scrolling = false;
                // dragY is relative to the ListView (the drag listener's
                // host); compare against visible children.
                if (top != null && dragY < top.getTop() + edge) {
                    list.smoothScrollBy(-step, 50);
                    scrolling = true;
                } else if (bottom != null
                        && dragY > bottom.getBottom() - edge) {
                    list.smoothScrollBy(step, 50);
                    scrolling = true;
                }
                if (scrolling) {
                    updateInsertLine();
                    kickAutoScroll();
                }
            }
        };
        dragHandler.postDelayed(autoScrollTask, 60);
    }

    private void endDrag() {
        drag = null;
        trashHover = false;
        if (autoScrollTask != null) {
            dragHandler.removeCallbacks(autoScrollTask);
            autoScrollTask = null;
        }
        insertLine.setVisibility(View.GONE);
        hideFolderOutline();
        if (trashBar != null) trashBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    /** Drop on the trash bar: delete the section or folder with undo,
     * no confirmation. The gesture is deliberate. Trash means delete:
     * a trashed folder's sections go with it (the menu's Ungroup is
     * the non-destructive path). */
    private void commitTrashDrop() {
        if (drag == null) {
            endDrag();
            return;
        }
        final DragState st = drag;
        if (st.isFolder) {
            final Folder gone = db.getFolder(st.folderId);
            if (gone == null) {
                endDrag();
                return;
            }
            // Snapshot the folder and its sections for undo.
            final List<Section> secs =
                    new ArrayList<Section>(db.sectionsInFolder(gone.id));
            final int folderPos = db.topLevelIndex(projectId, gone.id);
            for (Section s : secs) {
                db.deleteSectionQuiet(s.id);
            }
            db.deleteFolderRow(gone.id);
            selectedFolders.remove(gone.id);
            endDrag();
            refresh();
            UndoBar.undo(this, "Folder deleted.",
                    new UndoBar.Action() {
                        @Override public void run() {
                            long nid = db.insertFolder(projectId,
                                    gone.name);
                            db.setFolderPos(nid, folderPos);
                            for (int i = 0; i < secs.size(); i++) {
                                Section s = secs.get(i);
                                long nsid = db.insertSection(projectId,
                                        s.name);
                                db.updateSectionBody(nsid,
                                        s.body == null ? "" : s.body);
                                db.moveSectionToContainer(nsid, nid, i);
                            }
                            refresh();
                        }
                    });
        } else {
            final Section gone = db.getSection(st.sectionId);
            if (gone == null) {
                endDrag();
                return;
            }
            final long folderId = gone.folderId;
            final String name = gone.name;
            final String body = gone.body;
            db.deleteSectionQuiet(gone.id);
            selected.remove(gone.id);
            endDrag();
            refresh();
            UndoBar.undo(this, "Piece deleted.",
                    new UndoBar.Action() {
                        @Override public void run() {
                            long nsid = db.insertSection(projectId, name);
                            db.updateSectionBody(nsid,
                                    body == null ? "" : body);
                            if (folderId >= 0) {
                                db.moveSectionToContainer(nsid, folderId,
                                        db.sectionsInFolder(folderId)
                                                .size());
                            }
                            refresh();
                        }
                    });
        }
    }

    /** The finger let go: file the dragged thing where the line says. */
    private void commitDrop() {
        if (drag == null) return;
        final DragState st = drag;
        // Dropped without moving: no-op.
        if (dragX < 0 || dragY < 0) {
            endDrag();
            return;
        }
        int pos = list.pointToPosition((int) dragX, (int) dragY);
        if (st.isFolder) {
            commitFolderDrop(st, pos);
            return;
        }
        if (pos == AdapterView.INVALID_POSITION || pos >= rows.size()) {
            if (isProjectDragAboveFirst()) {
                // Top of the list: loose at the very top.
                dropSectionToContainer(st, -1, 0);
            } else {
                // Past the last row: loose at the end of the top level.
                dropSectionToContainer(st, -1, Integer.MAX_VALUE);
            }
            return;
        }
        commitSectionDrop(st, pos);
    }

    /**
     * A section drop lands inside one container: the hovered row's
     * container (its folder, or the loose top level). Onto a folder
     * header the section joins that folder at its end.
     */
    private void commitSectionDrop(final DragState st, int pos) {
        Object row = rows.get(pos);
        int first = list.getFirstVisiblePosition();
        View child = list.getChildAt(pos - first);
        if (row instanceof Folder) {
            Folder f = (Folder) row;
            if (child != null && isOnFolderTopEdge(child)) {
                // Top edge: end of the container above the folder.
                dropAtEndOfPreviousContainer(st, pos);
            } else {
                dropSectionToContainer(st, f.id, Integer.MAX_VALUE);
            }
            return;
        }
        Section s = (Section) row;
        long targetFolder = s.folderId;
        // The slot: other sections of the target container whose
        // headers sit above the finger, plus one when the finger is
        // in the hovered row's bottom half.
        List<Section> container = targetFolder >= 0
                ? db.sectionsInFolder(targetFolder)
                : db.looseSections(projectId);
        Set<Long> inContainer = new HashSet<Long>();
        for (Section x : container) inContainer.add(x.id);
        int slot = 0;
        for (int i = 0; i < pos && i < rows.size(); i++) {
            Object r = rows.get(i);
            if (r instanceof Section) {
                long id = ((Section) r).id;
                if (id != st.sectionId && inContainer.contains(id)) {
                    slot++;
                }
            }
        }
        if (child != null && s.id != st.sectionId
                && dragY > child.getTop() + child.getHeight() / 2f) {
            slot++;
        }
        dropSectionToContainer(st, targetFolder, slot);
    }

    /** Drop at the end of the container holding the row above pos:
     * the section above's folder, or the loose top level. */
    private void dropAtEndOfPreviousContainer(final DragState st, int pos) {
        long targetFolder = -1;
        for (int i = pos - 1; i >= 0; i--) {
            Object r = rows.get(i);
            if (r instanceof Section) {
                targetFolder = ((Section) r).folderId;
                break;
            } else if (r instanceof Folder) {
                break;
            }
        }
        dropSectionToContainer(st, targetFolder, Integer.MAX_VALUE);
    }

    /**
     * Commit a section move into a container slot, with undo back to
     * the original container and index. A drop that changes nothing
     * ends the drag silently.
     */
    private void dropSectionToContainer(final DragState st,
                                        final long targetFolderId,
                                        int slot) {
        Section dragged = db.getSection(st.sectionId);
        if (dragged == null) {
            endDrag();
            return;
        }
        final long oldFolder = dragged.folderId;
        List<Section> oldContainer = oldFolder >= 0
                ? db.sectionsInFolder(oldFolder)
                : db.looseSections(dragged.projectId);
        int oldIndex = -1;
        for (int i = 0; i < oldContainer.size(); i++) {
            if (oldContainer.get(i).id == st.sectionId) oldIndex = i;
        }
        List<Section> target = targetFolderId >= 0
                ? db.sectionsInFolder(targetFolderId)
                : db.looseSections(dragged.projectId);
        int room = target.size();
        if (oldFolder == targetFolderId) room--;
        if (slot < 0) slot = 0;
        if (slot > room) slot = room;
        final int fSlot = slot;
        // No-op: dropped where it already was (including a section
        // dropped onto its own folder when it's already last there).
        if (oldFolder == targetFolderId && oldIndex == slot) {
            endDrag();
            return;
        }
        db.moveSectionToContainer(st.sectionId, targetFolderId, fSlot);
        endDrag();
        refresh();
        haptic(false);
        final long fOldFolder = oldFolder;
        final int fOldIndex = oldIndex;
        UndoBar.undo(this, "Piece moved.", new UndoBar.Action() {
            @Override public void run() {
                db.moveSectionToContainer(st.sectionId, fOldFolder,
                        fOldIndex);
                refresh();
            }
        });
        String where;
        if (targetFolderId >= 0) {
            Folder f = folderById.get(targetFolderId);
            where = "folder \u201c"
                    + (f == null ? "folder" : f.name) + "\u201d";
        } else {
            where = "the top level";
        }
        list.announceForAccessibility("Piece moved to " + where + ".");
        scrollToSection(st.sectionId);
    }

    /** A folder drop reorders the merged top level, with undo. */
    private void commitFolderDrop(final DragState st, int pos) {
        List<NotesDb.TopItem> order = db.topLevelOrder(projectId);
        int draggedIdx = -1;
        for (int i = 0; i < order.size(); i++) {
            NotesDb.TopItem t = order.get(i);
            if (t.isFolder && t.id == st.folderId) {
                draggedIdx = i;
                break;
            }
        }
        if (draggedIdx < 0) {
            endDrag();
            return;
        }
        int k;
        if (pos == AdapterView.INVALID_POSITION || pos >= rows.size()) {
            k = isProjectDragAboveFirst() ? 0 : order.size();
        } else {
            int first = list.getFirstVisiblePosition();
            k = folderDropSlot(pos, first);
            if (k < 0) {
                endDrag();
                return;
            }
        }
        List<NotesDb.TopItem> next = new ArrayList<NotesDb.TopItem>(order);
        NotesDb.TopItem dragged = next.remove(draggedIdx);
        if (draggedIdx < k) k--;
        if (k < 0) k = 0;
        if (k > next.size()) k = next.size();
        next.add(k, dragged);
        // No-op: dropped back where it started.
        if (topOrdersEqual(next, st.fromTopOrder)) {
            endDrag();
            return;
        }
        db.reorderTopLevel(projectId, next);
        endDrag();
        refresh();
        haptic(false);
        final List<NotesDb.TopItem> before = st.fromTopOrder;
        UndoBar.undo(this, "Folder moved.", new UndoBar.Action() {
            @Override public void run() {
                db.reorderTopLevel(projectId, before);
                refresh();
            }
        });
        list.announceForAccessibility("Folder moved.");
        scrollToFolder(st.folderId);
    }

    private boolean topOrdersEqual(List<NotesDb.TopItem> a,
                                   List<NotesDb.TopItem> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }

    /** Bring a moved section header into view. */
    private void scrollToSection(long id) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row instanceof Section && ((Section) row).id == id) {
                list.smoothScrollToPosition(i);
                return;
            }
        }
    }

    // ---------------- jump to section ----------------

    /** At 5+ sections, jump straight to one instead of scrolling. */
    /** Overflow menu: Jump to piece · Copy project · Share project · Move to trash. */
    private void onOverflow() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        content.addView(Sheet.row(this, "Jump to piece",
                "Jump to a piece in this project",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        onJumpToSection();
                    }
                }));
        content.addView(Sheet.row(this, "Copy project",
                "Copy all pieces as text",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        onCopyProject();
                    }
                }));
        content.addView(Sheet.row(this, "Share project",
                "Share all pieces as a file",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        onShareProject();
                    }
                }));
        content.addView(Sheet.row(this, "Move to trash",
                "Move this project and its pieces to trash",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        onDeleteProject();
                    }
                }));
        sheet[0] = Sheet.show(this, "Project actions", content);
    }

    /** Move this project to trash with confirmation. */
    private void onDeleteProject() {
        android.app.AlertDialog themedDlg3 = new AlertDialog.Builder(this)
                .setTitle("Move project to trash?")
                .setMessage("The project and all its pieces will move to trash."
                        + " You can restore the whole project later.")
                .setPositiveButton("Move to trash",
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        // Soft-delete the whole project. Its sections and
                        // folders stay linked and come back on restore.
                        db.trashProject(projectId);
                        Toast.makeText(ProjectActivity.this,
                                "Moved to trash.", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        themedDlg3.show();
        Ui.styleDialog(this, themedDlg3);
    }

    private void onJumpToSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] sheet = new Dialog[1];
        List<Folder> folders = db.foldersFor(projectId);
        if (folders.isEmpty()) {
            for (Section s : db.looseSections(projectId)) {
                content.addView(jumpSheetRow(s, sheet));
            }
        } else {
            for (final Folder f : folders) {
                List<Section> inner = db.sectionsInFolder(f.id);
                if (inner.isEmpty()) continue;
                addSheetLabel(content, f.name);
                for (Section s : inner) {
                    content.addView(jumpSheetRow(s, sheet));
                }
            }
            addSheetLabel(content, "Ungrouped");
            for (Section s : db.looseSections(projectId)) {
                content.addView(jumpSheetRow(s, sheet));
            }
        }
        sheet[0] = Sheet.show(this, "Jump to piece", content);
    }

    private View jumpSheetRow(final Section s, final Dialog[] sheet) {
        int words = s.words;
        return Sheet.row(this, s.name,
                words + (words == 1 ? " word" : " words"),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        scrollToSection(s.id);
                    }
                });
    }

    private void addSheetLabel(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Ui.CARD_MUTED);
        t.setTextSize(14);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, 12);
        p.bottomMargin = Ui.dp(this, 4);
        parent.addView(t, p);
    }

    // ---------------- sections ----------------

    /** Move a section to the top or end of its own container (its
     * folder, or the loose top level), with undo. */
    private void onMoveSectionTo(final Section s, boolean toTop) {
        // Use the same container-move path as drag-and-drop: the
        // section stays in its own container, slot 0 or end.
        final long container = s.folderId;
        final List<Long> before = new ArrayList<Long>();
        List<Section> inContainer = container >= 0
                ? db.sectionsInFolder(container)
                : db.looseSections(s.projectId);
        for (Section x : inContainer) before.add(x.id);
        int slot = toTop ? 0 : Integer.MAX_VALUE;
        // No-op: already at the target end.
        int idx = before.indexOf(s.id);
        if ((toTop && idx == 0)
                || (!toTop && idx == before.size() - 1)) {
            return;
        }
        db.moveSectionToContainer(s.id, container, slot);
        refresh();
        scrollToSection(s.id);
        UndoBar.undo(this,
                "Moved \u201c" + s.name + "\u201d to the "
                        + (toTop ? "top" : "end") + ".",
                new UndoBar.Action() {
                    @Override public void run() {
                        if (container >= 0) {
                            db.reorderSectionsInFolder(container, before);
                        } else {
                            db.reorderSections(s.projectId, before);
                        }
                        refresh();
                    }
                });
    }

    /** Move a section into a folder (or back out to the top level) via
     * a bottom sheet, with undo. */
    private void onMoveSectionToFolderSheet(final Section s) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] sheet = new Dialog[1];
        for (final Folder f : db.foldersFor(projectId)) {
            List<Section> inner = db.sectionsInFolder(f.id);
            content.addView(Sheet.row(this, f.name,
                    inner.size()
                            + (inner.size() == 1 ? " piece"
                                    : " pieces"),
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (sheet[0] != null) sheet[0].dismiss();
                            doMoveSectionToContainer(s, f.id);
                        }
                    }));
        }
        content.addView(Sheet.row(this, "Top level",
                "Loose pieces, outside any folder",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        doMoveSectionToContainer(s, -1);
                    }
                }));
        sheet[0] = Sheet.show(this, "Move to folder", content);
    }

    private void doMoveSectionToContainer(final Section s,
                                          final long targetFolderId) {
        final long oldFolder = s.folderId;
        List<Section> oldContainer = oldFolder >= 0
                ? db.sectionsInFolder(oldFolder)
                : db.looseSections(s.projectId);
        int oldIndex = -1;
        for (int i = 0; i < oldContainer.size(); i++) {
            if (oldContainer.get(i).id == s.id) oldIndex = i;
        }
        List<Section> target = targetFolderId >= 0
                ? db.sectionsInFolder(targetFolderId)
                : db.looseSections(s.projectId);
        int slot = target.size();
        if (oldFolder == targetFolderId) slot--;
        if (slot < 0) slot = 0;
        // No-op: already there.
        if (oldFolder == targetFolderId && oldIndex == slot) return;
        db.moveSectionToContainer(s.id, targetFolderId, slot);
        refresh();
        haptic(false);
        final int fOldIndex = oldIndex;
        UndoBar.undo(this, "Piece moved.", new UndoBar.Action() {
            @Override public void run() {
                db.moveSectionToContainer(s.id, oldFolder, fOldIndex);
                refresh();
            }
        });
        String where;
        if (targetFolderId >= 0) {
            Folder f = db.getFolder(targetFolderId);
            where = "folder \u201c"
                    + (f == null ? "folder" : f.name) + "\u201d";
        } else {
            where = "the top level";
        }
        list.announceForAccessibility("Piece moved to " + where + ".");
        scrollToSection(s.id);
    }

    private void onRenameProject() {
        final Project p = db.getProject(projectId);
        if (p == null) return;
        NoteDialogs.textInput(this, "Rename project", null, p.name,
                new NoteDialogs.TextCallback() {
                    @Override public void onText(String t) {
                        db.renameProject(projectId,
                                t.isEmpty() ? p.name : t);
                        refresh();
                    }
                });
    }

    /** + Section: create it and open the editor immediately — a new
     * section is always a writing surface, never a dead row. The
     * title is deferred: it auto-names from the opening words on
     * save, and an untouched section is removed quietly. */
    private void onAddSection() {
        long sid = db.insertSection(projectId, "");
        if (sid == -1) {
            Toast.makeText(this, "Couldn't create the piece.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        refresh();
        Section s = db.getSection(sid);
        if (s == null) return;
        openSectionEditor(s, true);
    }

    /** Create an empty folder at the top level, expanded. Folders are
     * first-class: they can exist before their sections do. Undo
     * dissolves the new folder. */
    private void onAddFolder() {
        NoteDialogs.textInput(this, "New folder",
                "Part, act, collection\u2026", "",
                new NoteDialogs.TextCallback() {
                    @Override public void onText(String t) {
                        String name = t.isEmpty() ? "Untitled folder" : t;
                        final long fid = db.insertFolder(projectId, name);
                        refresh();
                        if (fid == -1) {
                            Toast.makeText(ProjectActivity.this,
                                    "Couldn't create the folder.",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        UndoBar.undo(ProjectActivity.this,
                                "Folder created.",
                                new UndoBar.Action() {
                                    @Override public void run() {
                                        db.ungroupFolder(projectId, fid);
                                        refresh();
                                    }
                                });
                        list.announceForAccessibility(
                                "Folder " + name + " created.");
                        scrollToFolder(fid);
                    }
                });
    }

    private static boolean isBodyEmpty(Section s) {
        return s.body == null || s.body.trim().isEmpty();
    }

    private void onSectionMenu(final Section s, View anchor) {
        // "Make into folder" is offered only for empty top-level
        // sections, and never for the project's last section: a project
        // always keeps at least one section, mirroring the delete guard.
        final boolean convertible = s.folderId < 0
                && isBodyEmpty(s)
                && db.sectionsFor(projectId).size() > 1;
        android.widget.PopupMenu menu =
                new android.widget.PopupMenu(this, anchor);
        android.view.Menu m = menu.getMenu();
        m.add(0, 1, 0, "Write in piece");
        m.add(0, 2, 0, "Rename piece");
        m.add(0, 3, 0, "Move to top");
        m.add(0, 4, 0, "Move to end");
        m.add(0, 5, 0, "Move to folder\u2026");
        if (convertible) m.add(0, 6, 0, "Make into folder");
        m.add(0, 7, 0, "Move to trash");
        menu.setOnMenuItemClickListener(
                new android.widget.PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(
                            android.view.MenuItem item) {
                        switch (item.getItemId()) {
                            case 1:
                                openSectionEditor(s, false);
                                return true;
                            case 2:
                                NoteDialogs.textInput(
                                        ProjectActivity.this,
                                        "Rename piece", null, s.name,
                                        new NoteDialogs.TextCallback() {
                                            @Override public void onText(
                                                    String t) {
                                                db.renameSection(s.id,
                                                        t.isEmpty()
                                                                ? s.name
                                                                : t);
                                                refresh();
                                            }
                                        });
                                return true;
                            case 3:
                                onMoveSectionTo(s, true);
                                return true;
                            case 4:
                                onMoveSectionTo(s, false);
                                return true;
                            case 5:
                                onMoveSectionToFolderSheet(s);
                                return true;
                            case 6:
                                onSectionToFolder(s);
                                return true;
                            default:
                                onDeleteSectionConfirm(s);
                                return true;
                        }
                    }
                });
        menu.show();
    }

    /** Turn an empty top-level section into a folder of the same name
     * and position. Undo restores the section exactly and dissolves
     * the folder. */
    private void onSectionToFolder(final Section s) {
        final Section gone = db.getSection(s.id);
        if (gone == null || gone.folderId >= 0
                || !isBodyEmpty(gone)) return;
        if (db.sectionsFor(projectId).size() <= 1) return;
        final List<NotesDb.TopItem> before = db.topLevelOrder(projectId);
        final long fid = db.insertFolder(projectId, gone.name);
        if (fid == -1) {
            Toast.makeText(this, "Couldn't create the folder.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // Move the new folder from the end into the section's old slot,
        // then remove the (empty) section.
        List<NotesDb.TopItem> order = db.topLevelOrder(projectId);
        List<NotesDb.TopItem> next = new ArrayList<NotesDb.TopItem>();
        NotesDb.TopItem folderItem = null;
        int sectionIdx = -1;
        for (NotesDb.TopItem t : order) {
            if (t.isFolder && t.id == fid) {
                folderItem = t;
            } else {
                if (!t.isFolder && t.id == gone.id) {
                    sectionIdx = next.size();
                }
                next.add(t);
            }
        }
        if (folderItem != null && sectionIdx >= 0) {
            next.add(sectionIdx, folderItem);
            db.reorderTopLevel(projectId, next);
        }
        db.deleteSection(gone.id);
        refresh();
        UndoBar.undo(this, "Piece is now a folder.",
                new UndoBar.Action() {
                    @Override public void run() {
                        db.ungroupFolder(projectId, fid);
                        db.insertSectionWithId(gone.id, gone.projectId,
                                gone.name, gone.pos, gone.collapsed, -1,
                                gone.body);
                        db.reorderTopLevel(projectId, before);
                        refresh();
                    }
                });
        list.announceForAccessibility(
                gone.name + " is now a folder.");
        scrollToFolder(fid);
    }

    private void onDeleteSectionConfirm(final Section s) {
        if (db.sectionsFor(projectId).size() <= 1) {
            Toast.makeText(this,
                    "A project needs at least one piece.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        android.app.AlertDialog themedDlg2 = new AlertDialog.Builder(this)
                .setMessage("Move piece \u201c" + s.name + "\u201d to trash?")
                .setPositiveButton("Move to trash",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d,
                                                          int which) {
                                db.trashSection(s.id);
                                refresh();
                                UndoBar.undo(ProjectActivity.this,
                                        "Moved to trash.",
                                        new UndoBar.Action() {
                                            @Override public void run() {
                                                db.restoreSection(s.id);
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

    /** Bulk delete of the checked sections and folders. Sections go to
     * trash (restorable); deleting a folder trashes its sections too.
     * Fully undoable via restoreSection. */
    private void onDeleteSelected() {
        // Resolve targets, skipping anything already gone.
        final List<Section> secTargets = new ArrayList<Section>();
        for (Long id : selected) {
            Section s = db.getSection(id);
            if (s != null && s.projectId == projectId) secTargets.add(s);
        }
        final List<Folder> folderTargets = new ArrayList<Folder>();
        // Sections inside a checked folder are covered by the folder;
        // drop them from the section list to avoid double handling.
        final Set<Long> inFolders = new HashSet<Long>();
        for (Long fid : selectedFolders) {
            Folder f = db.getFolder(fid);
            if (f == null || f.projectId != projectId) continue;
            folderTargets.add(f);
            for (Section s : db.sectionsInFolder(fid)) inFolders.add(s.id);
        }
        final List<Section> doomedSections = new ArrayList<Section>();
        for (Section s : secTargets) {
            if (!inFolders.contains(s.id)) doomedSections.add(s);
        }
        // A project needs at least one section: count what would remain.
        int totalSections = db.sectionsFor(projectId).size();
        int doomedCount = doomedSections.size() + inFolders.size();
        if (totalSections - doomedCount < 1) {
            Toast.makeText(this,
                    "A project needs at least one piece.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (doomedSections.isEmpty() && folderTargets.isEmpty()) return;
        int n = doomedSections.size() + folderTargets.size();
        String what = n == 1 ? "1 item" : n + " items";
        android.app.AlertDialog themedDlg1 = new AlertDialog.Builder(this)
                .setMessage("Move " + what + " to trash?")
                .setPositiveButton("Move to trash",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d,
                                                          int which) {
                                // Collect every trashed section id for undo.
                                final List<Long> trashedIds =
                                        new ArrayList<Long>();
                                // Folders first: trash each inner section,
                                // then drop the (now empty) folder row.
                                for (Folder f : folderTargets) {
                                    for (Section s
                                            : db.sectionsInFolder(f.id)) {
                                        db.trashSection(s.id);
                                        trashedIds.add(s.id);
                                    }
                                    db.deleteFolder(projectId, f.id);
                                }
                                for (Section s : doomedSections) {
                                    db.trashSection(s.id);
                                    trashedIds.add(s.id);
                                }
                                selected.clear();
                                selectedFolders.clear();
                                refresh();
                                final int count = n;
                                UndoBar.undo(ProjectActivity.this,
                                        count == 1 ? "Moved to trash."
                                                : count + " items moved to trash.",
                                        new UndoBar.Action() {
                                            @Override public void run() {
                                                for (Long sid : trashedIds) {
                                                    db.restoreSection(sid);
                                                }
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

    // ---------------- folders ----------------

    /** Long-press a folder: rename it or dissolve it. */
    private void onFolderMenu(final Folder f, View anchor) {
        final List<Section> inner = db.sectionsInFolder(f.id);
        String dissolveLabel = inner.isEmpty() ? "Delete folder"
                : "Ungroup folder";
        android.widget.PopupMenu menu =
                new android.widget.PopupMenu(this, anchor);
        android.view.Menu m = menu.getMenu();
        m.add(0, 1, 0, "Rename folder");
        m.add(0, 2, 0, dissolveLabel);
        menu.setOnMenuItemClickListener(
                new android.widget.PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(
                            android.view.MenuItem item) {
                        if (item.getItemId() == 1) {
                            NoteDialogs.textInput(
                                    ProjectActivity.this,
                                    "Rename folder", null, f.name,
                                    new NoteDialogs.TextCallback() {
                                        @Override public void onText(
                                                String t) {
                                            db.renameFolder(f.id,
                                                    t.isEmpty()
                                                            ? f.name
                                                            : t);
                                            refresh();
                                        }
                                    });
                        } else {
                            onUngroupFolder(f);
                        }
                        return true;
                    }
                });
        menu.show();
    }

    /** Dissolve a folder: its sections go loose at the folder's old
     * slot, in order. Undo recreates the folder with the sections back
     * inside, restoring positions and collapse state. */
    private void onUngroupFolder(final Folder f) {
        final Folder gone = db.getFolder(f.id);
        if (gone == null) return;
        final List<Long> innerIds = new ArrayList<Long>();
        for (Section s : db.sectionsInFolder(f.id)) {
            innerIds.add(s.id);
        }
        final List<NotesDb.TopItem> before = db.topLevelOrder(projectId);
        db.ungroupFolder(projectId, f.id);
        refresh();
        UndoBar.undo(this, "Folder ungrouped.", new UndoBar.Action() {
            @Override public void run() {
                db.insertFolderWithId(gone.id, projectId, gone.name,
                        gone.pos, gone.collapsed);
                for (int i = 0; i < innerIds.size(); i++) {
                    db.moveSectionToContainer(innerIds.get(i), gone.id,
                            i);
                }
                db.reorderTopLevel(projectId, before);
                refresh();
            }
        });
        list.announceForAccessibility("Folder ungrouped.");
    }

    // ---------------- export ----------------
    // COPY = inline text for pasting. EXPORT = format picker, then a
    // destination: save the file to Downloads or share it to another app.

    private static final int MODE_COPY = 0;
    private static final int MODE_EXPORT = 1;

    private void onCopyProject() {
        openFormatSheet(MODE_COPY);
    }

    private void onShareProject() {
        openFormatSheet(MODE_EXPORT);
    }

    /** Format picker: tap a format to continue in it. */
    private void openFormatSheet(final int mode) {
        Project p = db.getProject(projectId);
        if (p == null) return;
        if (selected.isEmpty() && selectedFolders.isEmpty()) {
            Toast.makeText(this, "Check off pieces or folders to include.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // Free-first fixed order: plain text on top, the Pro formats
        // grouped below (TXT, FOUNTAIN, MD, HTML enum order).
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        for (final ProjectExporter.Format f
                : ProjectExporter.Format.values()) {
            // Binary formats (PDF) can't go to the clipboard.
            if (mode == MODE_COPY && f.isBinary()) continue;
            content.addView(Sheet.row(this,
                    f.label + " (." + f.ext + ")", f.description,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            getSharedPreferences(PREFS, MODE_PRIVATE)
                                    .edit()
                                    .putString("export_format", f.name())
                                    .apply();
                            if (sheet[0] != null) sheet[0].dismiss();
                            if (mode == MODE_COPY) doCopyProject(f);
                            else onExportFormatPicked(f);
                        }
                    }));
        }
        String title = mode == MODE_COPY ? "Copy as" : "Share as";
        sheet[0] = Sheet.show(this, title, content);
    }

    /** EXPORT: one format picked — now pick the destination. The text is
     * rendered once and reused by whichever destination is chosen. */
    private void onExportFormatPicked(final ProjectExporter.Format f) {
        Project p = db.getProject(projectId);
        if (p == null) return;
        final String name = p.name == null ? "" : p.name;
        final List<ProjectExporter.Entry> entries = exportEntries();
        final boolean isPdf = f == ProjectExporter.Format.PDF;
        final byte[] pdfBytes;
        final String text;
        if (isPdf) {
            pdfBytes = ProjectExporter.renderBytes(f, name, entries);
            text = null;
            if (pdfBytes == null || pdfBytes.length == 0) {
                Toast.makeText(this, "Nothing to export.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            pdfBytes = null;
            text = ProjectExporter.render(f, name, entries);
            if (text.isEmpty()) {
                Toast.makeText(this, "Nothing to export.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        final String base = ProjectExporter.sanitizeFileName(p.name);
        final ProjectActivity self = this;
        ExportDelivery.askDestination(this,
                new Runnable() {
                    @Override public void run() {
                        if (isPdf) {
                            ExportDelivery.saveBytesToDownloads(
                                    self, f, pdfBytes, base);
                        } else {
                            ExportDelivery.saveToDownloads(self, f, text, base);
                        }
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        if (isPdf) {
                            ExportDelivery.shareBytes(self, f, pdfBytes, base,
                                    "Share project");
                        } else {
                            ExportDelivery.shareFile(self, f, text, base,
                                    "Share project");
                        }
                    }
                });
    }

    private List<ProjectExporter.Entry> exportEntries() {
        // Checked folders expand into their sections; checked sections
        // pass through. One set for the exporter, deduplicated.
        Set<Long> ids = new HashSet<Long>(selected);
        for (Long fid : selectedFolders) {
            for (Section s : db.sectionsInFolder(fid)) {
                ids.add(s.id);
            }
        }
        return ProjectExporter.collect(db, projectId, ids);
    }

    private void doCopyProject(ProjectExporter.Format f) {
        Project p = db.getProject(projectId);
        if (p == null) return;
        String t = ProjectExporter.render(f,
                p.name == null ? "" : p.name, exportEntries());
        if (t.isEmpty()) {
            Toast.makeText(this, "Nothing to copy.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm =
                (ClipboardManager) getSystemService(
                        Context.CLIPBOARD_SERVICE);
        try {
            cm.setPrimaryClip(
                    ClipData.newPlainText("Pour project", t));
        } catch (Exception e) {
            // The clipboard can refuse (locked-down device, huge text).
            Toast.makeText(this, "Couldn't copy. Try again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Android 13+ shows its own "Copied" overlay with a preview;
        // doubling it with our own toast is a documented annoyance.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this,
                    "Project copied. "
                            + NotesDb.countWords(t) + " words.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static final String PREFS = "pour_pro";

}
