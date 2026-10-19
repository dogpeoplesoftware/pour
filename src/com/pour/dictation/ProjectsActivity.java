package com.pour.dictation;

import android.util.Log;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Projects: typeless containers the writer files Bin notes into and
 * organizes with their own section names. Styled like the Pour web app. */
public class ProjectsActivity extends Activity {

    private NotesDb db;
    private BaseAdapter adapter;
    private final List<Project> projects = new ArrayList<>();
    /** Adapter rows: Project in normal mode; in search mode, String
     * headers (project names) interleaved with matching Sections. */
    private final List<Object> rows = new ArrayList<>();
    private static final int TYPE_PROJECT = 0;
    private static final int TYPE_HEADER = 1;
    private static final int TYPE_SECTION = 2;
    /** Cross-project search: filters pieces by title/body as you type. */
    private EditText searchBox;
    private Button clearSearchBtn;
    private TextView emptyView;
    private ListView list;
    private android.widget.FrameLayout listSlot;

    /** Checked projects for bulk export — same checkbox model as the
     * project view's note selection. In-memory only, cleared when
     * leaving the page (consistent with 1.1.5). */
    private final Set<Long> selected = new HashSet<Long>();
    /** Compact floating selection strip: every bulk action as a text
     * button, delete last in red. */
    private SelectionBar selectionBar;
    // Drag to reorder: press-and-hold lifts the card; a trash bar
    // appears while dragging (same as the Bucket).
    private View insertLine;
    private LinearLayout trashBar;
    private TextView trashLabel;
    private ImageView trashIcon;
    private long dragProjectId = -1;
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

        root.addView(Ui.header(this, "Projects",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onExportProjects(); }
                },
                "New project",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onNewProject(); }
                },
                true, true, false));

        TextView purposeView = new TextView(this);
        purposeView.setText(
                "Your text, organized.");
        purposeView.setTextColor(Ui.MUTED);
        purposeView.setTextSize(14);
        LinearLayout.LayoutParams ppp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ppp.topMargin = Ui.dp(this, 4);
        root.addView(purposeView, ppp);

        // Cross-project search: same pattern as the Bucket. Searches
        // piece titles and body text across all projects; results are
        // grouped under project name headers.
        searchBox = new EditText(this);
        searchBox.setHint("Search all projects\u2026");
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

        list = new ListView(this);
        list.setDivider(new ColorDrawable(Ui.BG));
        list.setDividerHeight(Ui.dp(this, 12));
        list.setFastScrollEnabled(true);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int p) { return rows.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public int getViewTypeCount() { return 3; }
            @Override public int getItemViewType(int p) {
                Object row = rows.get(p);
                if (row instanceof String) return TYPE_HEADER;
                if (row instanceof Section) return TYPE_SECTION;
                return TYPE_PROJECT;
            }

            @Override
            public View getView(int position, View convertView,
                                ViewGroup parent) {
                Object row = rows.get(position);
                int type = getItemViewType(position);
                if (type == TYPE_HEADER) {
                    TextView header;
                    if (convertView instanceof TextView) {
                        header = (TextView) convertView;
                    } else {
                        header = new TextView(ProjectsActivity.this);
                        header.setTextColor(Ui.MUTED);
                        header.setTextSize(12);
                        header.setTypeface(
                                android.graphics.Typeface.DEFAULT_BOLD);
                        int hp = Ui.dp(ProjectsActivity.this, 16);
                        header.setPadding(hp, Ui.dp(ProjectsActivity.this, 8),
                                hp, Ui.dp(ProjectsActivity.this, 4));
                        header.setLayoutParams(new AbsListView.LayoutParams(
                                AbsListView.LayoutParams.MATCH_PARENT,
                                AbsListView.LayoutParams.WRAP_CONTENT));
                    }
                    header.setText(((String) row).toUpperCase());
                    return header;
                }
                if (type == TYPE_SECTION) {
                    return sectionResultView((Section) row, convertView,
                            searchBox.getText().toString().trim());
                }
                // TYPE_PROJECT: the existing card UI below.
                final Project pr = (Project) row;
                LinearLayout card;
                TextView titleView;
                TextView metaView;
                ImageButton selectBtn;
                ImageButton overflowBtn;
                if (convertView == null) {
                    card = new LinearLayout(ProjectsActivity.this);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(Gravity.CENTER_VERTICAL);
                    int p = Ui.dp(ProjectsActivity.this, 16);
                    card.setPadding(p, p, p, p);
                    card.setBackground(Ui.card(ProjectsActivity.this, 16));
                    // Checkbox on the left.
                    selectBtn = iconButton(R.drawable.ic_box,
                            "Select project for export");
                    card.addView(selectBtn);
                    LinearLayout textCol =
                            new LinearLayout(ProjectsActivity.this);
                    textCol.setOrientation(LinearLayout.VERTICAL);
                    titleView = new TextView(ProjectsActivity.this);
                    titleView.setTextColor(Ui.CARD_TEXT);
                    titleView.setTextSize(16);
                    metaView = new TextView(ProjectsActivity.this);
                    metaView.setTextColor(Ui.CARD_MUTED);
                    metaView.setTextSize(14);
                    textCol.addView(titleView);
                    textCol.addView(metaView);
                    card.addView(textCol,
                            new LinearLayout.LayoutParams(0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f));
                    // Overflow menu on the right.
                    overflowBtn = iconButton(R.drawable.ic_more,
                            "Project options");
                    overflowBtn.setOnClickListener(
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    Project op = (Project) v.getTag();
                                    if (op != null) onProjectMenu(op, v);
                                }
                            });
                    card.addView(overflowBtn);
                    card.setTag(new Object[]{titleView, metaView,
                            selectBtn, overflowBtn});
                    card.setLayoutParams(new AbsListView.LayoutParams(
                            AbsListView.LayoutParams.MATCH_PARENT,
                            AbsListView.LayoutParams.WRAP_CONTENT));
                } else {
                    card = (LinearLayout) convertView;
                    Object[] t = (Object[]) card.getTag();
                    titleView = (TextView) t[0];
                    metaView = (TextView) t[1];
                    selectBtn = (ImageButton) t[2];
                    overflowBtn = (ImageButton) t[3];
                }
                titleView.setText(pr.name);
                int secs = db.sectionsFor(pr.id).size();
                int words = db.projectWordCount(pr.id);
                metaView.setText(secs + (secs == 1 ? " piece" : " pieces")
                        + " \u00B7 " + words
                        + (words == 1 ? " word" : " words"));
                overflowBtn.setTag(pr);
                final boolean isSel = selected.contains(pr.id);
                selectBtn.setImageResource(isSel
                        ? R.drawable.ic_box_checked : R.drawable.ic_box);
                selectBtn.setColorFilter(isSel ? Ui.ACCENT : Ui.MUTED);
                selectBtn.setContentDescription(isSel
                        ? "Deselect project for export"
                        : "Select project for export");
                selectBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (selected.contains(pr.id)) {
                            selected.remove(pr.id);
                        } else {
                            selected.add(pr.id);
                        }
                        refresh();
                        list.announceForAccessibility(
                                selected.contains(pr.id)
                                        ? "Project selected."
                                        : "Project deselected.");
                    }
                });
                card.setContentDescription(pr.name + ", project."
                        + " Tap to open."
                        + (isSel ? " Selected for export." : ""));
                return card;
            }
        };
        list.setAdapter(adapter);
        emptyView = new TextView(this);
        emptyView.setText("No projects yet. Tap + to start one.");
        emptyView.setTextColor(Ui.MUTED);
        emptyView.setGravity(Gravity.CENTER);
        // Weighted slot: when the list empties, the ListView goes GONE
        // for its empty view — without the slot the tab row would ride
        // up the screen.
        listSlot = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = Ui.dp(this, 12);
        root.addView(listSlot, listParams);
        listSlot.addView(list,
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        listSlot.addView(emptyView,
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER));
        list.setEmptyView(emptyView);
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
        // Trash bar: visible only while dragging.
        trashBar = new LinearLayout(this);
        trashBar.setOrientation(LinearLayout.HORIZONTAL);
        trashBar.setGravity(android.view.Gravity.CENTER);
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
        android.widget.FrameLayout.LayoutParams trashParams =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM
                                | android.view.Gravity.CENTER_HORIZONTAL);
        trashParams.bottomMargin = Ui.dp(this, 16);
        listSlot.addView(trashBar, trashParams);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                Object row = rows.get(position);
                if (row instanceof Section) {
                    // Search result: open the piece in its editor.
                    Intent i = new Intent(ProjectsActivity.this,
                            SectionActivity.class);
                    i.putExtra("section_id", ((Section) row).id);
                    startActivity(i);
                    return;
                }
                if (!(row instanceof Project)) return;
                Project p = (Project) row;
                Intent i = new Intent(ProjectsActivity.this,
                        ProjectActivity.class);
                i.putExtra("project_id", p.id);
                startActivity(i);
            }
        });

        list.setOnItemLongClickListener(
                new AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(AdapterView<?> parent,
                                                   View view, int position,
                                                   long id) {
                        // No dragging while searching.
                        if (isSearching()) return false;
                        Object row = rows.get(position);
                        if (!(row instanceof Project)) return false;
                        // Press-and-hold lifts the card for drag (the
                        // menu moved to the per-card overflow button).
                        startProjectDrag((Project) row, view);
                        return true;
                    }
                });

        list.setOnDragListener(new View.OnDragListener() {
            @Override public boolean onDrag(View v,
                                             android.view.DragEvent e) {
                return onProjectsDrag(e);
            }
        });

        root.addView(Ui.tabRow(this, Ui.TAB_PROJECTS));

        // Floating selection toolbar: visible while projects are
        // checked. Same pattern as the Bucket and the project page —
        // every bulk action is shown, so delete never looks like the
        // only option.
        java.util.List<SelectionBar.Action> barActions =
                new java.util.ArrayList<SelectionBar.Action>();
        barActions.add(new SelectionBar.Action() {
            @Override public String label() { return "Share"; }
            @Override public boolean visible() { return true; }
            @Override public boolean destructive() { return false; }
            @Override public void run() { onExportProjects(); }
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
            Log.w("ProjectsActivity", "Suppressed exception", e);
        }
        android.widget.FrameLayout.LayoutParams pillParams =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM
                                | android.view.Gravity.CENTER_HORIZONTAL);
        pillParams.bottomMargin = navInset + Ui.dp(this, 84);
        frame.addView(selectionBar.view(), pillParams);

        setContentView(frame);
    }

    /** A search result row: piece title + snippet showing the match in
     * context. Tapping opens the piece in its editor. */
    private View sectionResultView(Section s, View convertView, String query) {
        LinearLayout card;
        TextView titleView;
        TextView snippetView;
        if (convertView instanceof LinearLayout
                && convertView.getTag() instanceof Object[]) {
            card = (LinearLayout) convertView;
            Object[] t = (Object[]) card.getTag();
            titleView = (TextView) t[0];
            snippetView = (TextView) t[1];
        } else {
            card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int p = Ui.dp(this, 16);
            card.setPadding(p, p, p, p);
            card.setBackground(Ui.card(this, 16));
            titleView = new TextView(this);
            titleView.setTextColor(Ui.CARD_TEXT);
            titleView.setTextSize(16);
            titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            snippetView = new TextView(this);
            snippetView.setTextColor(Ui.CARD_MUTED);
            snippetView.setTextSize(14);
            snippetView.setMaxLines(2);
            snippetView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(titleView);
            card.addView(snippetView);
            card.setTag(new Object[]{titleView, snippetView});
            card.setLayoutParams(new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT));
        }
        titleView.setText(s.name);
        snippetView.setText(snippetAround(s.body, query));
        card.setContentDescription(s.name + ", piece. Tap to open.");
        return card;
    }

    /** Extract ~120 chars of body text centered on the query match,
     * so the hit is visible in context. Falls back to the start of
     * the body when the match is in the title. */
    private String snippetAround(String body, String query) {
        if (body == null) return "";
        String t = body.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "";
        String ql = query == null ? "" : query.trim().toLowerCase();
        if (ql.isEmpty()) {
            return t.length() > 120 ? t.substring(0, 120) + "\u2026" : t;
        }
        int at = t.toLowerCase().indexOf(ql);
        if (at < 0) {
            return t.length() > 120 ? t.substring(0, 120) + "\u2026" : t;
        }
        int start = Math.max(0, at - 40);
        int end = Math.min(t.length(), at + ql.length() + 60);
        String snip = t.substring(start, end);
        if (start > 0) snip = "\u2026" + snip;
        if (end < t.length()) snip = snip + "\u2026";
        return snip;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.syncTheme(this)) return;
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        selected.clear();
    }

    private void refresh() {
        projects.clear();
        projects.addAll(db.allProjects());
        rows.clear();
        String q = searchBox != null
                ? searchBox.getText().toString().trim() : "";
        boolean searching = !q.isEmpty();
        if (!searching) {
            rows.addAll(projects);
        } else {
            // Search mode: matching pieces grouped under project
            // name headers. Projects stay in their existing order.
            java.util.Map<Long, String> nameById =
                    new java.util.HashMap<Long, String>();
            java.util.Map<Long, List<Section>> hitsByProject =
                    new java.util.HashMap<Long, List<Section>>();
            for (Project p : projects) {
                nameById.put(p.id, p.name);
            }
            for (Section s : db.searchSections(q)) {
                List<Section> bucket = hitsByProject.get(s.projectId);
                if (bucket == null) {
                    bucket = new ArrayList<Section>();
                    hitsByProject.put(s.projectId, bucket);
                }
                bucket.add(s);
            }
            for (Project p : projects) {
                List<Section> hits = hitsByProject.get(p.id);
                if (hits == null || hits.isEmpty()) continue;
                rows.add(p.name);
                rows.addAll(hits);
            }
        }
        adapter.notifyDataSetChanged();
        clearSearchBtn.setVisibility(
                searching ? View.VISIBLE : View.GONE);
        emptyView.setText(searching ? "No pieces match."
                : "No projects yet. Tap + to start one.");
        if (selectionBar != null) selectionBar.update(selected.size());
    }

    /** True while the search box has a query. Drag, selection, and
     * project cards are all suspended in search mode. */
    private boolean isSearching() {
        return searchBox != null
                && !searchBox.getText().toString().trim().isEmpty();
    }

    /** Bulk delete of the checked projects. Delete means delete;
     * undo restores every project with its folders,
     * sections, and text. */
    private void onDeleteSelected() {
        final List<Project> targets = new ArrayList<Project>();
        for (Project p : projects) {
            if (selected.contains(p.id)) targets.add(p);
        }
        if (targets.isEmpty()) return;
        final int n = targets.size();
        android.app.AlertDialog themedDlg2 = new AlertDialog.Builder(this)
                .setMessage("Move " + n
                        + (n == 1 ? " project" : " projects")
                        + " to trash? You can restore them later.")
                .setPositiveButton("Move to trash",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d,
                                                          int which) {
                                final List<Long> trashedIds =
                                        new ArrayList<Long>();
                                for (Project p : targets) {
                                    db.trashProject(p.id);
                                    trashedIds.add(p.id);
                                }
                                selected.clear();
                                refresh();
                                UndoBar.undo(ProjectsActivity.this,
                                        n == 1 ? "Project moved to trash."
                                                : n + " projects moved to trash.",
                                        new UndoBar.Action() {
                                            @Override public void run() {
                                                for (long pid : trashedIds) {
                                                    db.restoreProject(pid);
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

    /**
     * Row icon button. Not focusable (a focusable child steals taps and
     * long-presses from the ListView — the 1.0.1 lesson), 48dp for the
     * accessibility target size. Same visuals as the project view's
     * selection checkboxes.
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
        b.setScaleType(
                android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int pad = Ui.dp(this, 12);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    /** Export the checked projects: format picker, then a destination
     * (one file per project saved to Downloads, or all files shared
     * to another app). Each project exports in full — fine-grained
     * note selection stays in the project view. Nothing checked →
     * toast, never an export-everything fallback. */
    private void onExportProjects() {
        final List<Project> checked = new ArrayList<Project>();
        for (Project p : projects) {
            if (selected.contains(p.id)) checked.add(p);
        }
        if (checked.isEmpty()) {
            Toast.makeText(this, "Check off the projects to include.",
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
            content.addView(Sheet.row(this,
                    f.label + " (." + f.ext + ")", f.description,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            getSharedPreferences("pour_pro", MODE_PRIVATE)
                                    .edit()
                                    .putString("export_format", f.name())
                                    .apply();
                            if (sheet[0] != null) sheet[0].dismiss();
                            onProjectsFormatPicked(f, checked);
                        }
                    }));
        }
        sheet[0] = Sheet.show(this, "Share as", content);
    }

    /** One format picked: render each checked project in full, then
     * pick the destination. Projects with no notes are skipped. */
    private void onProjectsFormatPicked(final ProjectExporter.Format f,
                                        List<Project> checked) {
        final boolean isPdf = f == ProjectExporter.Format.PDF;
        final List<String> texts = new ArrayList<String>();
        final List<byte[]> pdfs = new ArrayList<byte[]>();
        final List<String> bases = new ArrayList<String>();
        for (Project p : checked) {
            Project full = db.getProject(p.id);
            if (full == null) continue;
            List<ProjectExporter.Entry> entries =
                    ProjectExporter.collect(db, p.id,
                            allSectionIds(db, p.id));
            if (entries.isEmpty()) continue;
            String name = full.name == null ? "" : full.name;
            if (isPdf) {
                byte[] pdf = ProjectExporter.renderBytes(f, name, entries);
                if (pdf == null || pdf.length == 0) continue;
                pdfs.add(pdf);
            } else {
                texts.add(ProjectExporter.render(f, name, entries));
            }
            bases.add(ProjectExporter.sanitizeFileName(full.name));
        }
        if ((isPdf ? pdfs : texts).isEmpty()) {
            Toast.makeText(this, "Nothing to export.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final ProjectsActivity self = this;
        ExportDelivery.askDestination(this,
                new Runnable() {
                    @Override public void run() {
                        if (isPdf) {
                            ExportDelivery.saveAllBytesToDownloads(self, f,
                                    pdfs, bases);
                        } else {
                            ExportDelivery.saveAllToDownloads(self, f,
                                    texts, bases);
                        }
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        if (isPdf) {
                            ExportDelivery.shareBytesList(self, f, pdfs, bases,
                                    "Share projects");
                        } else {
                            ExportDelivery.shareFiles(self, f, texts, bases,
                                    "Share projects");
                        }
                    }
                });
    }

    /** Every section id in the project — the projects list exports
     * whole projects; fine-grained selection stays in the project
     * view. */
    private static Set<Long> allSectionIds(NotesDb db, long projectId) {
        Set<Long> ids = new HashSet<Long>();
        for (Section s : db.sectionsFor(projectId)) ids.add(s.id);
        return ids;
    }

    /** A project starts with one section; the writer names it (or adds
     * more) whenever the shape of the work becomes clear. */
    private void onNewProject() {
        NoteDialogs.newProject(this, db,
                new NoteDialogs.ProjectCallback() {
                    @Override public void onProject(long pid) {
                        Project p = db.getProject(pid);
                        refresh();
                        Toast.makeText(ProjectsActivity.this,
                                "\u201c" + (p == null ? "Project" : p.name)
                                        + "\u201d created.",
                                Toast.LENGTH_SHORT).show();
                        // The writer just set this project up — open it
                        // so they can add sections, instead of making them
                        // tap the card they just created.
                        Intent i = new Intent(ProjectsActivity.this,
                                ProjectActivity.class);
                        i.putExtra("project_id", pid);
                        startActivity(i);
                    }
                });
    }

    /** Per-card overflow menu: Rename, Share, Move to trash. No "Move to":
     * a project is a top-level container with nowhere to move it. */
    private void onProjectMenu(final Project p, View anchor) {
        android.widget.PopupMenu menu =
                new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "Rename");
        menu.getMenu().add(0, 1, 1, "Share");
        menu.getMenu().add(0, 2, 2, "Move to trash");
        menu.setOnMenuItemClickListener(
                new android.widget.PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(
                            android.view.MenuItem item) {
                        if (item.getItemId() == 0) {
                            NoteDialogs.textInput(
                                    ProjectsActivity.this,
                                    "Rename project", null, p.name,
                                    new NoteDialogs.TextCallback() {
                                        @Override public void onText(
                                                String t) {
                                            db.renameProject(p.id,
                                                    t.isEmpty()
                                                            ? p.name
                                                            : t);
                                            refresh();
                                        }
                                    });
                        } else if (item.getItemId() == 1) {
                            // Export just this project.
                            selected.add(p.id);
                            onExportProjects();
                            selected.remove(p.id);
                            refresh();
                        } else {
                            onDeleteConfirm(p);
                        }
                        return true;
                    }
                });
        menu.show();
    }

    // ---------------- drag to reorder / drag to trash ----------------

    /** Press-and-hold lifts the project card. */
    private void startProjectDrag(Project p, View cardView) {
        dragProjectId = p.id;
        trashHover = false;
        android.content.ClipData data =
                android.content.ClipData.newPlainText(
                        "pour-pro-projects-drag",
                        String.valueOf(p.id));
        View.DragShadowBuilder shadow =
                new View.DragShadowBuilder(cardView);
        boolean ok;
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            ok = cardView.startDragAndDrop(data, shadow, null, 0);
        } else {
            ok = cardView.startDrag(data, shadow, null, 0);
        }
        if (!ok) {
            dragProjectId = -1;
            return;
        }
        try {
            list.performHapticFeedback(
                    android.view.HapticFeedbackConstants.DRAG_START);
        } catch (Exception e) {
            Log.w("ProjectsActivity", "Suppressed exception", e);
        }
        trashBar.setVisibility(View.VISIBLE);
        setProjectsTrashHover(false);
        // No stale coordinates: the line stays hidden until the
        // finger actually moves.
        dragX = -1;
        dragY = -1;
        if (insertLine != null) insertLine.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private boolean onProjectsDrag(android.view.DragEvent e) {
        switch (e.getAction()) {
            case android.view.DragEvent.ACTION_DRAG_STARTED:
                return true;
            case android.view.DragEvent.ACTION_DRAG_LOCATION:
                dragX = e.getX();
                dragY = e.getY();
                updateProjectsTrashHover();
                if (!trashHover) updateProjectsInsertLine();
                else if (insertLine != null) {
                    insertLine.setVisibility(View.GONE);
                }
                kickProjectsAutoScroll();
                return true;
            case android.view.DragEvent.ACTION_DROP:
                dragX = e.getX();
                dragY = e.getY();
                updateProjectsTrashHover();
                if (trashHover) {
                    commitProjectsTrashDrop();
                } else {
                    commitProjectsDrop();
                }
                return true;
            case android.view.DragEvent.ACTION_DRAG_ENDED:
                endProjectsDrag();
                return true;
            default:
                return false;
        }
    }

    private void updateProjectsTrashHover() {
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
        if (over != trashHover) setProjectsTrashHover(over);
    }

    private void setProjectsTrashHover(boolean over) {
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
                Log.w("ProjectsActivity", "Suppressed exception", e);
            }
        }
    }

    private void updateProjectsInsertLine() {
        if (dragProjectId < 0 || list == null || insertLine == null) return;
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
        if (pos == AdapterView.INVALID_POSITION || pos >= projects.size()) {
            if (isProjectsDragAboveFirst()) {
                showProjectsLineAtTop();
            } else {
                showProjectsLineAtEnd();
            }
            return;
        }
        int first = list.getFirstVisiblePosition();
        View child = list.getChildAt(pos - first);
        if (child == null) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        if (projects.get(pos).id == dragProjectId) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        // The bar mirrors the drop: top edge in the top half,
        // bottom edge in the bottom half.
        float lineY = child.getTop() - Ui.dp(this, 1);
        if (dragY > child.getTop() + child.getHeight() / 2f) {
            lineY = child.getBottom();
        }
        insertLine.setTranslationY(lineY);
        insertLine.setVisibility(View.VISIBLE);
    }

    private boolean isProjectsDragAboveFirst() {
        if (list == null || list.getChildCount() == 0) return true;
        View first = list.getChildAt(0);
        return dragY < first.getTop() + first.getHeight() / 2f;
    }

    private void showProjectsLineAtTop() {
        if (insertLine == null || list == null) return;
        if (list.getChildCount() == 0) {
            insertLine.setVisibility(View.GONE);
            return;
        }
        View child = list.getChildAt(0);
        insertLine.setTranslationY(child.getTop() - Ui.dp(this, 1));
        insertLine.setVisibility(View.VISIBLE);
    }

    private void showProjectsLineAtEnd() {
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

    private void kickProjectsAutoScroll() {
        if (autoScrollTask != null || dragProjectId < 0) return;
        autoScrollTask = new Runnable() {
            @Override public void run() {
                autoScrollTask = null;
                if (dragProjectId < 0 || list == null) return;
                int edge = Ui.dp(ProjectsActivity.this, 72);
                int step = Ui.dp(ProjectsActivity.this, 28);
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
                    updateProjectsInsertLine();
                    kickProjectsAutoScroll();
                }
            }
        };
        dragHandler.postDelayed(autoScrollTask, 60);
    }

    private void endProjectsDrag() {
        dragProjectId = -1;
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
    private void commitProjectsDrop() {
        if (dragProjectId < 0) {
            endProjectsDrag();
            return;
        }
        // Dropped without moving: no-op.
        if (dragX < 0 || dragY < 0) {
            endProjectsDrag();
            return;
        }
        long id = dragProjectId;
        List<Long> order = new ArrayList<Long>();
        for (Project p : projects) {
            if (p.id != id) order.add(p.id);
        }
        int pos = list.pointToPosition((int) dragX, (int) dragY);
        int slot;
        if (pos == AdapterView.INVALID_POSITION || pos >= projects.size()) {
            // Above the first card or below the last: the Y decides.
            slot = isProjectsDragAboveFirst() ? 0 : order.size();
        } else {
            slot = 0;
            for (int i = 0; i < pos && i < projects.size(); i++) {
                if (projects.get(i).id != id) slot++;
            }
            int first = list.getFirstVisiblePosition();
            View child = list.getChildAt(pos - first);
            if (child != null && dragY > child.getTop()
                    + child.getHeight() / 2f) {
                if (projects.get(pos).id != id) slot++;
            }
        }
        if (slot < 0) slot = 0;
        if (slot > order.size()) slot = order.size();
        order.add(slot, id);
        db.reorderProjects(order);
        endProjectsDrag();
        refresh();
    }

    /** Drop on the trash bar: delete with undo, no confirmation. */
    private void commitProjectsTrashDrop() {
        if (dragProjectId < 0) {
            endProjectsDrag();
            return;
        }
        Project p = db.getProject(dragProjectId);
        if (p == null) {
            endProjectsDrag();
            return;
        }
        selected.remove(p.id);
        endProjectsDrag();
        // Reuse the confirmed delete flow's undo by calling the
        // delete directly and showing its undo bar.
        deleteProjectWithUndo(p);
        refresh();
    }

    private void onDeleteConfirm(final Project p) {
        android.app.AlertDialog themedDlg1 = new AlertDialog.Builder(this)
                .setMessage("Move \u201c" + p.name
                        + "\u201d to trash? The project and all"
                        + " its pieces will move to trash.")
                .setPositiveButton("Move to trash",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d,
                                                          int which) {
                                deleteProjectWithUndo(p);
                            }
                        })
                .setNegativeButton("Cancel", null)
                .create();
        themedDlg1.show();
        Ui.styleDialog(this, themedDlg1);
    }

    /** Delete the project and show undo. Undo restores the
     * project, folders, and sections. */
    private void deleteProjectWithUndo(final Project p) {
        db.trashProject(p.id);
        refresh();
        UndoBar.undo(ProjectsActivity.this,
                "Project moved to trash.",
                new UndoBar.Action() {
                    @Override public void run() {
                        db.restoreProject(p.id);
                        refresh();
                    }
                });
    }

}
