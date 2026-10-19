package com.pour.dictation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Trash: soft-deleted notes and sections. Restore or delete forever.
 * Auto-emptied on app start per the retention setting in Settings. */
public class TrashActivity extends Activity {

    private NotesDb db;
    private LinearLayout listContainer;
    private TextView emptyView;

    /** Unified trash item: a note, a section, or a whole project. */
    private static class TrashItem {
        long id;
        int kind; // 0 = note, 1 = section, 2 = project
        String title;
        String snippet;
        long deletedAt;
        int words;
        int pieceCount; // for projects
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.refresh(this);
        db = new NotesDb(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.syncTheme(this)) return;
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { db.close(); } catch (Exception ignored) { }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        Ui.applySafeInsets(this, root, 20);

        // Header: back, title, empty-trash button.
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton backBtn = new ImageButton(this);
        backBtn.setImageResource(R.drawable.ic_back);
        backBtn.setColorFilter(Ui.TEXT);
        backBtn.setBackground(Ui.selectable(this));
        backBtn.setContentDescription("Back");
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        headerRow.addView(backBtn);
        TextView titleView = new TextView(this);
        titleView.setText("Trash");
        titleView.setTextSize(24);
        titleView.setTextColor(Ui.TEXT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(this, 12);
        headerRow.addView(titleView, tp);
        Button emptyBtn = new Button(this);
        emptyBtn.setText("Empty trash");
        Ui.styleBtn(this, emptyBtn);
        emptyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onEmptyTrash(); }
        });
        headerRow.addView(emptyBtn);
        root.addView(headerRow);

        // Retention info.
        TextView infoView = new TextView(this);
        int days = SettingsActivity.trashRetentionDays(this);
        infoView.setText(days < 0
                ? "Pieces stay here until you delete them."
                : "Pieces auto-delete after " + days + " days.");
        infoView.setTextColor(Ui.MUTED);
        infoView.setTextSize(14);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ip.topMargin = Ui.dp(this, 8);
        infoView.setLayoutParams(ip);
        root.addView(infoView);

        // List.
        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f);
        sp.topMargin = Ui.dp(this, 12);
        scroll.setLayoutParams(sp);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        emptyView = new TextView(this);
        emptyView.setText("Trash is empty.");
        emptyView.setTextColor(Ui.MUTED);
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        setContentView(root);
    }

    private void refresh() {
        listContainer.removeAllViews();
        List<TrashItem> items = loadTrash();
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (final TrashItem item : items) {
            listContainer.addView(buildRow(item));
        }
    }

    private List<TrashItem> loadTrash() {
        List<TrashItem> out = new ArrayList<>();
        for (Note n : db.trashedNotes()) {
            TrashItem t = new TrashItem();
            t.id = n.id;
            t.kind = 0;
            t.title = (n.title == null || n.title.trim().isEmpty())
                    ? "Untitled piece" : n.title.trim();
            String tr = n.transcript == null ? "" : n.transcript.trim();
            t.snippet = tr.length() > 120 ? tr.substring(0, 120) + "…" : tr;
            t.deletedAt = getDeletedAt("notes", n.id);
            t.words = n.words;
            out.add(t);
        }
        for (Section s : db.trashedSections()) {
            TrashItem t = new TrashItem();
            t.id = s.id;
            t.kind = 1;
            t.title = (s.name == null || s.name.trim().isEmpty())
                    ? "Untitled piece" : s.name.trim();
            String body = s.body == null ? "" : s.body.trim();
            t.snippet = body.length() > 120
                    ? body.substring(0, 120) + "…" : body;
            t.deletedAt = getDeletedAt("sections", s.id);
            t.words = s.words;
            out.add(t);
        }
        for (Project p : db.trashedProjects()) {
            TrashItem t = new TrashItem();
            t.id = p.id;
            t.kind = 2;
            t.title = "Project: " + ((p.name == null
                    || p.name.trim().isEmpty())
                    ? "Untitled project" : p.name.trim());
            int pieces = db.sectionsFor(p.id).size();
            t.pieceCount = pieces;
            t.snippet = pieces + (pieces == 1 ? " piece" : " pieces");
            t.deletedAt = (p.deletedAt != null) ? p.deletedAt
                    : getDeletedAt("projects", p.id);
            t.words = db.projectWordCount(p.id);
            out.add(t);
        }
        // Newest trash first.
        java.util.Collections.sort(out,
                new java.util.Comparator<TrashItem>() {
            @Override public int compare(TrashItem a, TrashItem b) {
                return Long.compare(b.deletedAt, a.deletedAt);
            }
        });
        return out;
    }

    private long getDeletedAt(String table, long id) {
        android.database.Cursor c = null;
        try {
            c = db.getReadableDatabase().rawQuery(
                    "SELECT deleted_at FROM " + table + " WHERE _id=?",
                    new String[]{String.valueOf(id)});
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    private String daysAgo(long deletedAt) {
        long days = (System.currentTimeMillis() - deletedAt)
                / (24L * 60 * 60 * 1000);
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        return days + " days ago";
    }

    private View buildRow(final TrashItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.card(this, 16));
        int p = Ui.dp(this, 16);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = Ui.dp(this, 12);
        row.setLayoutParams(rp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(tcp);

        TextView titleView = new TextView(this);
        titleView.setText(item.title);
        titleView.setTextColor(Ui.CARD_TEXT);
        titleView.setTextSize(16);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(titleView);

        if (!item.snippet.isEmpty()) {
            TextView snippetView = new TextView(this);
            snippetView.setText(item.snippet);
            snippetView.setTextColor(Ui.CARD_MUTED);
            snippetView.setTextSize(14);
            snippetView.setSingleLine(true);
            snippetView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(snippetView);
        }

        TextView metaView = new TextView(this);
        metaView.setText("Deleted " + daysAgo(item.deletedAt)
                + " · " + item.words
                + (item.words == 1 ? " word" : " words"));
        metaView.setTextColor(Ui.CARD_MUTED);
        metaView.setTextSize(12);
        textCol.addView(metaView);
        row.addView(textCol);

        Button restoreBtn = new Button(this);
        restoreBtn.setText("Restore");
        Ui.styleBtn(this, restoreBtn);
        restoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (item.kind == 0) db.restoreNote(item.id);
                else if (item.kind == 1) db.restoreSection(item.id);
                else db.restoreProject(item.id);
                Toast.makeText(TrashActivity.this, "Restored.",
                        Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        row.addView(restoreBtn);

        ImageButton moreBtn = new ImageButton(this);
        moreBtn.setImageResource(R.drawable.ic_more);
        moreBtn.setColorFilter(Ui.MUTED);
        moreBtn.setBackground(Ui.selectable(this));
        moreBtn.setContentDescription("More actions");
        int ms = Ui.dp(this, 48);
        moreBtn.setLayoutParams(
                new LinearLayout.LayoutParams(ms, ms));
        moreBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onItemMenu(item, v); }
        });
        row.addView(moreBtn);

        return row;
    }

    private void onItemMenu(final TrashItem item, View anchor) {
        android.widget.PopupMenu menu =
                new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "Delete forever");
        menu.setOnMenuItemClickListener(
                new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(
                    android.view.MenuItem mi) {
                onDeleteForever(item);
                return true;
            }
        });
        menu.show();
    }

    private void onDeleteForever(final TrashItem item) {
        android.app.AlertDialog themedDlg2 = new AlertDialog.Builder(this)
                .setMessage("Permanently delete \u201c" + item.title
                        + "\u201d? This cannot be undone.")
                .setPositiveButton("Delete forever",
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (item.kind == 0) db.hardDeleteNote(item.id);
                        else if (item.kind == 1)
                            db.hardDeleteSection(item.id);
                        else db.hardDeleteProject(item.id);
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        themedDlg2.show();
        Ui.styleDialog(this, themedDlg2);
    }

    private void onEmptyTrash() {
        int n = db.trashCount();
        if (n == 0) {
            Toast.makeText(this, "Trash is already empty.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        android.app.AlertDialog themedDlg1 = new AlertDialog.Builder(this)
                .setMessage("Permanently delete all " + n
                        + (n == 1 ? " piece" : " pieces")
                        + " in trash? This cannot be undone.")
                .setPositiveButton("Empty trash",
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        db.emptyTrash();
                        Toast.makeText(TrashActivity.this,
                                "Trash emptied.", Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        themedDlg1.show();
        Ui.styleDialog(this, themedDlg1);
    }
}
