package com.pour.dictation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings: Appearance, Dictation, Notes,
 * Backup & restore, About. Kept small on purpose.
 */
public class SettingsActivity extends Activity {

    private TextView backupTitle;
    private TextView restoreTitle;
    private TextView themeSubtitle;
    private TextView textSizeSubtitle;
    private TextView sortSubtitle;
    private TextView voiceSubtitle;
    private TextView trashSubtitleView;
    private TextView retentionSubtitle;
    private TextView awakePill;

    /** Callback for the option sheets. */
    private interface PickHandler { void onPick(int idx); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.initTheme(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        Ui.applySafeInsets(this, root, 20);

        // Header: back chevron + title. A leaf page, so no tab row.
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton backBtn = new ImageButton(this);
        backBtn.setImageResource(R.drawable.ic_chevron);
        // The chevron points down; rotate it into a left chevron.
        backBtn.setRotation(90);
        backBtn.setContentDescription("Back");
        backBtn.setBackgroundColor(0x00000000);
        backBtn.setColorFilter(Ui.MUTED);
        backBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int bs = Ui.dp(this, 40);
        backBtn.setLayoutParams(
                new LinearLayout.LayoutParams(bs, bs));
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        headerRow.addView(backBtn);
        TextView header = new TextView(this);
        header.setText("Settings");
        header.setTextSize(24);
        header.setTextColor(Ui.TEXT);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hp.leftMargin = Ui.dp(this, 4);
        headerRow.addView(header, hp);
        root.addView(headerRow);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = Ui.dp(this, 8);
        root.addView(scroll, sp);

        // Appearance: theme presets plus the text-size choice.
        content.addView(sectionHeader("Appearance"));
        LinearLayout themeRow = settingRow("Themes", themeLabel(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showThemeSheet();
                    }
                });
        themeSubtitle = ((TextView[]) themeRow.getTag())[1];
        content.addView(themeRow);
        LinearLayout textSizeRow = settingRow("Text size", textSizeLabel(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showTextSizeSheet();
                    }
                });
        textSizeSubtitle = ((TextView[]) textSizeRow.getTag())[1];
        content.addView(textSizeRow);

        // Dictation: comfort while recording.
        content.addView(sectionHeader("Dictation"));
        final LinearLayout awakeRow = toggleRow("Keep screen awake",
                "Screen stays on while dictating",
                Ui.keepAwake(this),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean now = !Ui.keepAwake(
                                SettingsActivity.this);
                        Ui.prefs(SettingsActivity.this).edit()
                                .putBoolean("keep_awake", now).apply();
                        paintPill(awakePill, now);
                    }
                });
        content.addView(awakeRow);

        // Voice engine: on-device (offline, private) or the
        // phone's built-in dictation (may use the network).
        LinearLayout voiceRow = settingRow("Voice engine",
                voiceLabel(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showVoiceSheet();
                    }
                });
        voiceSubtitle = ((TextView[]) voiceRow.getTag())[1];
        content.addView(voiceRow);

        // Notes: how the bucket lists them.
        content.addView(sectionHeader("Pieces"));
        LinearLayout sortRow = settingRow("Sort pieces", sortLabel(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showSortSheet();
                    }
                });
        sortSubtitle = ((TextView[]) sortRow.getTag())[1];
        content.addView(sortRow);

        // Trash: soft-deleted pieces live here until the retention
        // period expires or the user empties it.
        content.addView(sectionHeader("Trash"));
        LinearLayout trashRow = settingRow("Trash", trashSubtitle(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this,
                                TrashActivity.class));
                    }
                });
        trashSubtitleView = ((TextView[]) trashRow.getTag())[1];
        content.addView(trashRow);
        LinearLayout retentionRow = settingRow("Empty trash automatically",
                retentionLabel(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showRetentionSheet();
                    }
                });
        retentionSubtitle = ((TextView[]) retentionRow.getTag())[1];
        content.addView(retentionRow);

        // Backup & restore: the same entry points the shared
        // BackupFlow sheet uses, so the logic lives in one place.
        content.addView(sectionHeader("Backup & restore"));
        LinearLayout backupRow = settingRow(
                "Back up everything",
                "Save a dated backup file",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        BackupHelper.exportRequest(SettingsActivity.this);
                    }
                });
        backupTitle = ((TextView[]) backupRow.getTag())[0];
        content.addView(backupRow);
        LinearLayout restoreRow = settingRow(
                "Restore from backup",
                "Merge a backup file in",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        BackupHelper.importRequest(SettingsActivity.this);
                    }
                });
        restoreTitle = ((TextView[]) restoreRow.getTag())[0];
        content.addView(restoreRow);

        // About.
        content.addView(sectionHeader("About"));
        LinearLayout aboutHeader = aboutBlock();
        content.addView(aboutHeader);

        // Debug section — hidden until 7 taps on the version number.
        final LinearLayout debugSection = new LinearLayout(this);
        debugSection.setOrientation(LinearLayout.VERTICAL);
        debugSection.setVisibility(View.GONE);
        content.addView(debugSection);
        // Debug logging.
        debugSection.addView(sectionHeader("Debug"));
        final TextView[] debugTag = new TextView[1];
        LinearLayout debugRow = settingRow("Debug logging",
                DebugLog.isEnabled(this) ? "On" : "Off",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean on = !DebugLog.isEnabled(
                                SettingsActivity.this);
                        DebugLog.setEnabled(SettingsActivity.this, on);
                        debugTag[0].setText(on ? "On" : "Off");
                    }
                });
        debugTag[0] = ((TextView[]) debugRow.getTag())[1];
        debugSection.addView(debugRow);
        LinearLayout shareRow = settingRow("Share debug log",
                "Send the log file",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        shareLog();
                    }
                });
        debugSection.addView(shareRow);
        LinearLayout copyRow = settingRow("Copy log",
                "Copy as plain text",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        copyLog();
                    }
                });
        debugSection.addView(copyRow);

        final TextView[] crashTag = new TextView[1];
        LinearLayout crashRow = settingRow("Crash logs",
                CrashLog.isEnabled(this) ? "On" : "Off",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean on = !CrashLog.isEnabled(SettingsActivity.this);
                        CrashLog.setEnabled(SettingsActivity.this, on);
                        crashTag[0].setText(on ? "On" : "Off");
                    }
                });
        crashTag[0] = ((TextView[]) crashRow.getTag())[1];
        debugSection.addView(crashRow);
        LinearLayout copyCrashRow = settingRow("Copy crash log",
                "Copy as plain text",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        copyCrashLog();
                    }
                });
        debugSection.addView(copyCrashRow);

        // 7 taps on version reveals debug section.
        final int[] tapCount = {0};
        final android.os.Handler tapHandler = new android.os.Handler();
        final Runnable tapReset = new Runnable() {
            @Override public void run() { tapCount[0] = 0; }
        };
        // Find the version TextView (first child of aboutHeader) and make it tappable.
        if (aboutHeader.getChildCount() > 0) {
            View versionView = aboutHeader.getChildAt(0);
            versionView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    tapCount[0]++;
                    tapHandler.removeCallbacks(tapReset);
                    tapHandler.postDelayed(tapReset, 2000);
                    if (tapCount[0] >= 7) {
                        tapCount[0] = 0;
                        debugSection.setVisibility(
                            debugSection.getVisibility() == View.VISIBLE
                                ? View.GONE : View.VISIBLE);
                    }
                }
            });
        }

        setContentView(root);
    }

    private TextView sectionHeader(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(Ui.TEXT);
        h.setTextSize(14);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, 20);
        p.bottomMargin = Ui.dp(this, 6);
        h.setLayoutParams(p);
        return h;
    }

    /**
     * Tappable settings row, same shape as Sheet.row. The title and
     * subtitle views are stashed as the row tag so labels can refresh on
     * resume (the backup pro label, the current text-size and sort
     * choices).
     */
    private LinearLayout settingRow(String title, String subtitle,
                                    View.OnClickListener onTap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int v = Ui.dp(this, 16);
        int h = Ui.dp(this, 4);
        row.setPadding(h, v, h, v);
        row.setMinimumHeight(Ui.dp(this, 56));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(Ui.selectable(this));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(16);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t);
        TextView s = null;
        if (subtitle != null && !subtitle.isEmpty()) {
            s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(Ui.MUTED);
            s.setTextSize(14);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(s);
        }
        row.setOnClickListener(onTap);
        row.setTag(new TextView[]{t, s});
        return row;
    }

    /**
     * A settings row with an ON/OFF pill on the right, for boolean
     * choices. The pill view is stashed as the row tag for repainting.
     */
    private LinearLayout toggleRow(String title, String subtitle,
                                   boolean on,
                                   View.OnClickListener onTap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int v = Ui.dp(this, 16);
        int h = Ui.dp(this, 4);
        row.setPadding(h, v, h, v);
        row.setMinimumHeight(Ui.dp(this, 56));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(Ui.selectable(this));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(16);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(Ui.MUTED);
            s.setTextSize(14);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(s);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, tp);

        TextView pill = new TextView(this);
        pill.setTextSize(14);
        pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        int pw = Ui.dp(this, 56);
        int ph = Ui.dp(this, 32);
        pill.setLayoutParams(new LinearLayout.LayoutParams(pw, ph));
        int pr = Ui.dp(this, 16);
        pill.setPadding(0, 0, 0, 0);
        paintPill(pill, on);
        row.addView(pill);
        awakePill = pill;

        row.setOnClickListener(onTap);
        row.setTag(pill);
        return row;
    }

    /** Paint the ON/OFF pill: accent when on, quiet when off. */
    private void paintPill(TextView pill, boolean on) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 12));
        if (on) {
            pill.setText("ON");
            pill.setTextColor(Ui.ON_ACCENT);
            bg.setColor(Ui.ACCENT);
        } else {
            pill.setText("OFF");
            pill.setTextColor(Ui.MUTED);
            bg.setColor(Ui.CARD);
            bg.setStroke(2, Ui.STROKE);
        }
        pill.setBackground(bg);
        pill.setContentDescription(on ? "On" : "Off");
    }

    private String themeLabel() {
        return Theme.current(this).name;
    }

    /**
     * The theme picker: every preset visible at once, with a color-dot
     * preview. Dark and Light are always selectable; pro presets
     * (including Custom) are greyed with a (PRO) tag while the free
     * tier is active, and tapping one fires the upgrade dialog (same
     * pattern as the pro export formats and menu items). Tapping
     * Custom while pro opens the custom theme editor.
     */
    private void showThemeSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        final String curId = Theme.current(this).id;
        for (final Theme.Preset p : Theme.pickerPresets(this)) {
            final boolean isCur = p.id.equals(curId);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int v = Ui.dp(this, 12);
            int h = Ui.dp(this, 4);
            row.setPadding(h, v, h, v);
            row.setMinimumHeight(Ui.dp(this, 56));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(Ui.selectable(this));
            row.addView(themeDot(p));
            TextView t = new TextView(this);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = Ui.dp(this, 12);
            t.setText(p.name);
            t.setTextColor(isCur ? Ui.ACCENT : Ui.TEXT);
            if (isCur) {
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
            t.setTextSize(16);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t, tp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (sheet[0] != null) sheet[0].dismiss();
                    if (Theme.CUSTOM_ID.equals(p.id)) {
                        CustomThemeEditor.show(SettingsActivity.this,
                                new Runnable() {
                            @Override public void run() {
                                Theme.setCurrent(SettingsActivity.this,
                                        Theme.CUSTOM_ID);
                                if (themeSubtitle != null) {
                                    themeSubtitle.setText(Theme.current(
                                            SettingsActivity.this).name);
                                }
                                // Rebuild this screen in the new theme.
                                recreate();
                            }
                        });
                        return;
                    }
                    if (p.id.equals(
                            Theme.current(SettingsActivity.this).id)) {
                        return;
                    }
                    Theme.setCurrent(SettingsActivity.this, p.id);
                    if (themeSubtitle != null) {
                        themeSubtitle.setText(
                                Theme.current(SettingsActivity.this).name);
                    }
                    // Rebuild this screen in the new theme.
                    recreate();
                }
            });
            content.addView(row);
        }
        sheet[0] = Sheet.show(this, "Themes", content);
    }

    /** Color-dot preview: the candidate theme's background ringed by
     * its stroke, with its accent at the center. */
    private View themeDot(Theme.Preset p) {
        int size = Ui.dp(this, 28);
        android.widget.FrameLayout dot =
                new android.widget.FrameLayout(this);
        dot.setLayoutParams(
                new LinearLayout.LayoutParams(size, size));
        android.graphics.drawable.GradientDrawable ring =
                new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(p.bg);
        ring.setStroke(Ui.dp(this, 2), p.stroke);
        dot.setBackground(ring);
        View inner = new View(this);
        int innerSize = Ui.dp(this, 12);
        android.graphics.drawable.GradientDrawable core =
                new android.graphics.drawable.GradientDrawable();
        core.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        core.setColor(p.accent);
        inner.setBackground(core);
        android.widget.FrameLayout.LayoutParams ip =
                new android.widget.FrameLayout.LayoutParams(
                        innerSize, innerSize);
        ip.gravity = Gravity.CENTER;
        dot.addView(inner, ip);
        return dot;
    }

    private String textSizeLabel() {
        String v = Ui.prefs(this).getString("text_size", "medium");
        if ("small".equals(v)) return "Small";
        if ("large".equals(v)) return "Large";
        return "Medium";
    }

    private String sortLabel() {
        return Ui.newestFirst(this) ? "Newest first" : "Oldest first";
    }

    /** Retention days for trash auto-delete: 7, 30, 90, or -1 for never. */
    public static int trashRetentionDays(android.content.Context ctx) {
        String v = Ui.prefs(ctx).getString("trash_retention", "30");
        if ("never".equals(v)) return -1;
        try { return Integer.parseInt(v); } catch (Exception e) { return 30; }
    }

    private String retentionLabel() {
        int d = trashRetentionDays(this);
        if (d < 0) return "Never";
        return "After " + d + " days";
    }

    private String trashSubtitle() {
        NotesDb db = new NotesDb(this);
        int n;
        try { n = db.trashCount(); } finally { db.close(); }
        if (n == 0) return "Empty";
        return n + (n == 1 ? " piece" : " pieces");
    }

    private void showRetentionSheet() {
        final String[] labels = {"After 7 days", "After 30 days",
                "After 90 days", "Never"};
        final String[] values = {"7", "30", "90", "never"};
        String cur = Ui.prefs(this).getString("trash_retention", "30");
        int sel = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(cur)) { sel = i; break; }
        }
        showOptionSheet("Empty trash automatically", labels, sel,
                new PickHandler() {
            @Override public void onPick(int idx) {
                Ui.prefs(SettingsActivity.this).edit()
                        .putString("trash_retention", values[idx]).apply();
                if (retentionSubtitle != null) {
                    retentionSubtitle.setText(retentionLabel());
                }
            }
        });
    }

    private String voiceLabel() {
        return EngineFactory.usePhoneDictation(this)
                ? "Phone dictation" : "On-device";
    }

    private void showVoiceSheet() {
        final String[] labels = {"On-device", "Phone dictation"};
        int sel = EngineFactory.usePhoneDictation(this) ? 1 : 0;
        showOptionSheet("Voice engine", labels, sel, new PickHandler() {
            @Override public void onPick(int idx) {
                EngineFactory.setUsePhoneDictation(
                        SettingsActivity.this, idx == 1);
                voiceSubtitle.setText(labels[idx]);
            }
        });
    }



    private void showTextSizeSheet() {
        final String[] vals = {"small", "medium", "large"};
        final String[] labels = {"Small", "Medium", "Large"};
        String cur = Ui.prefs(this).getString("text_size", "medium");
        int sel = 1;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i].equals(cur)) sel = i;
        }
        showOptionSheet("Text size", labels, sel, new PickHandler() {
            @Override public void onPick(int idx) {
                Ui.prefs(SettingsActivity.this).edit()
                        .putString("text_size", vals[idx]).apply();
                textSizeSubtitle.setText(labels[idx]);
            }
        });
    }

    private void showSortSheet() {
        final String[] vals = {"newest", "oldest"};
        final String[] labels = {"Newest first", "Oldest first"};
        int sel = Ui.newestFirst(this) ? 0 : 1;
        showOptionSheet("Sort pieces", labels, sel, new PickHandler() {
            @Override public void onPick(int idx) {
                Ui.prefs(SettingsActivity.this).edit()
                        .putString("sort_order", vals[idx]).apply();
                sortSubtitle.setText(labels[idx]);
            }
        });
    }

    /**
     * A small bottom sheet of text options; the current one is tinted
     * accent instead of a checkmark glyph.
     */
    private void showOptionSheet(String title, final String[] labels,
                                 int current, final PickHandler onPick) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int v = Ui.dp(this, 16);
            int h = Ui.dp(this, 4);
            row.setPadding(h, v, h, v);
            row.setMinimumHeight(Ui.dp(this, 52));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(Ui.selectable(this));
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextColor(i == current ? Ui.ACCENT : Ui.TEXT);
            if (i == current) {
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
            t.setTextSize(16);
            row.addView(t);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (sheet[0] != null) sheet[0].dismiss();
                    onPick.onPick(idx);
                }
            });
            content.addView(row);
        }
        sheet[0] = Sheet.show(this, title, content);
    }

    /** Version + tagline. The version line carries the hidden pro test
     * switch: seven taps (Android developer-options pattern) cycle the
     * override auto -> free -> pro -> auto. The TEST indicator beneath
     * it shows while an override is active and is itself tappable as an
     * escape hatch. */
    private LinearLayout aboutBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        int v = Ui.dp(this, 16);
        int h = Ui.dp(this, 4);
        block.setPadding(h, v, h, v);
        TextView name = new TextView(this);
        name.setText("POUR " + appVersion());
        name.setTextColor(Ui.TEXT);
        name.setTextSize(16);
        block.addView(name);
        TextView tag = new TextView(this);
        tag.setText("Speak, and Pour writes it down.\n"
                + "Turn thoughts into words you can keep and send anywhere.");
        tag.setTextColor(Ui.MUTED);
        tag.setTextSize(14);
        block.addView(tag);
        TextView credit = new TextView(this);
        credit.setText("Speech recognition powered by Kroko (CC-BY-SA).");
        credit.setTextColor(Ui.MUTED);
        credit.setTextSize(12);
        block.addView(credit);
        return block;
    }

    private String appVersion() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.syncTheme(this)) return;
        if (backupTitle != null) {
            backupTitle.setText("Back up everything");
        }
        if (restoreTitle != null) {
            final boolean pro = Pro.isPro(this);
            restoreTitle.setText(pro ? "Restore from backup"
                    : "Restore from backup (PRO)");
            restoreTitle.setTextColor(pro ? Ui.TEXT : Ui.MUTED);
        }
        if (themeSubtitle != null) {
            themeSubtitle.setText(themeLabel());
        }
        if (textSizeSubtitle != null) {
            textSizeSubtitle.setText(textSizeLabel());
        }
        if (sortSubtitle != null) {
            sortSubtitle.setText(sortLabel());
        }
        if (trashSubtitleView != null) {
            trashSubtitleView.setText(trashSubtitle());
        }
        if (retentionSubtitle != null) {
            retentionSubtitle.setText(retentionLabel());
        }
        if (awakePill != null) {
            paintPill(awakePill, Ui.keepAwake(this));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        BackupFlow.onActivityResult(this, requestCode, resultCode, data,
                null);
    }

    /** Copy the debug log as plain text to the clipboard. */
    private void copyLog() {
        java.io.File logFile = DebugLog.getLogFile(this);
        if (!logFile.exists()) {
            android.widget.Toast.makeText(this,
                    "No log file yet. Turn on debug logging first.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            java.io.FileInputStream in =
                    new java.io.FileInputStream(logFile);
            byte[] buf = new byte[(int) logFile.length()];
            in.read(buf);
            in.close();
            String text = new String(buf, "UTF-8");
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "debug log", text));
            android.widget.Toast.makeText(this, "Log copied.",
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                    "Couldn't copy the log.",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void copyCrashLog() {
        if (!CrashLog.hasLog(this)) {
            android.widget.Toast.makeText(this,
                    "No crash log yet. Turn on crash logs first.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String text = CrashLog.getLog(this);
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "crash log", text));
            android.widget.Toast.makeText(this, "Crash log copied.",
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                    "Couldn't copy the crash log.",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Share the debug log file via the system share sheet. */
    private void shareLog() {
        java.io.File logFile = DebugLog.getLogFile(this);
        if (!logFile.exists()) {
            android.widget.Toast.makeText(this,
                    "No log file yet. Turn on debug logging first.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Copy to cache/exports for ShareProvider
            java.io.File exports = new java.io.File(getCacheDir(), "exports");
            exports.mkdirs();
            java.io.File dest = new java.io.File(exports, "debug.log");
            java.io.FileInputStream in = new java.io.FileInputStream(logFile);
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            android.net.Uri uri = ShareProvider.uriFor(dest);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share debug log"));
        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                    "Couldn't share the log.",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
