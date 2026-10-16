package com.pour.dictation;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shared backup sheet + result handling. Used by the Projects page and
 * the Bucket page so the logic lives in exactly one place.
 */
public class BackupFlow {

    /** Show the backup sheet: back up everything, or restore. */
    public static void showSheet(final Activity a,
                                 final Runnable onImported) {
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        TextView blurb = new TextView(a);
        blurb.setText("All your pieces, projects, and folders"
                + " as one file you keep. Restoring merges;"
                + " it never deletes what's already here.");
        blurb.setTextColor(Ui.CARD_MUTED);
        blurb.setTextSize(14);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.bottomMargin = Ui.dp(a, 8);
        content.addView(blurb, bp);
        content.addView(Sheet.row(a, "Back up everything",
                "Save a dated backup file",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        BackupHelper.exportRequest(a);
                    }
                }));
        content.addView(Sheet.row(a, "Restore from backup",
                "Merge a backup file in",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        BackupHelper.importRequest(a);
                    }
                }));
        sheet[0] = Sheet.show(a, "Backup", content);
    }

    /**
     * Handle onActivityResult for the backup flow. Returns true when the
     * result belonged to backup. onImported runs after a successful
     * import so the caller can refresh its list. Export and import run
     * on a background thread; toasts post back to the UI thread.
     */
    public static boolean onActivityResult(final Activity a, int requestCode,
                                           int resultCode, final Intent data,
                                           final Runnable onImported) {
        if (data == null || data.getData() == null) return false;
        final android.net.Uri uri = data.getData();
        if (requestCode == BackupHelper.REQ_EXPORT
                && resultCode == Activity.RESULT_OK) {
            Toast.makeText(a, "Backing up…", Toast.LENGTH_SHORT).show();
            new Thread(new Runnable() {
                @Override public void run() {
                    final String summary =
                            BackupHelper.doExport(a, uri);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(a,
                                    summary == null
                                            ? "Backup failed. Try again."
                                            : summary,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }, "PourBackupExport").start();
            return true;
        } else if (requestCode == BackupHelper.REQ_IMPORT
                && resultCode == Activity.RESULT_OK) {
            Toast.makeText(a, "Restoring…", Toast.LENGTH_SHORT).show();
            new Thread(new Runnable() {
                @Override public void run() {
                    final String summary =
                            BackupHelper.doImport(a, uri);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (summary == null) {
                                Toast.makeText(a,
                                        "That file isn't a Pour backup.",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                if (onImported != null) onImported.run();
                                Toast.makeText(a, summary,
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            }, "PourBackupImport").start();
            return true;
        }
        return false;
    }
}
