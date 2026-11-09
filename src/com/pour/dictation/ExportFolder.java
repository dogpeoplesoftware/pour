package com.pour.dictation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;

/**
 * The user-chosen export folder: one folder picked through Android's
 * system picker, remembered with a lasting write permission, and
 * offered as a save destination next to Downloads. Managed from the
 * export destination sheet (choose it while exporting) and from
 * Settings (change or clear it any time). The picker result is
 * delivered through ExportFolder.deliverResult, which every activity
 * that can show an export sheet delegates to from onActivityResult —
 * the same shape as BackupFlow.
 */
public final class ExportFolder {

    public static final int REQ_FOLDER = 4201;

    private static final String PREF_URI = "export_folder_uri";
    private static final String PREF_PROMPT_DONE =
            "export_folder_prompt_done";

    /** The save to complete once a folder pick succeeds. One export
     * flow can be waiting at a time, like the sheets themselves. */
    private static Runnable pendingSave;

    private ExportFolder() { }

    public static void setPendingSave(Runnable r) {
        pendingSave = r;
    }

    // ---------------- stored choice ----------------

    public static Uri folderUri(Context ctx) {
        String s = Ui.prefs(ctx).getString(PREF_URI, "");
        if (s == null || s.isEmpty()) return null;
        try {
            return Uri.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean hasFolder(Context ctx) {
        return folderUri(ctx) != null;
    }

    /** True while Android still grants us write access to the stored
     * folder. A revoked permission or deleted folder reads false, so
     * callers can forget the choice instead of offering a dead
     * destination. */
    public static boolean folderUsable(Context ctx) {
        Uri uri = folderUri(ctx);
        if (uri == null) return false;
        try {
            for (UriPermission p : ctx.getContentResolver()
                    .getPersistedUriPermissions()) {
                if (p.getUri().equals(uri) && p.isWritePermission()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** The folder's own name, for sheet rows and toasts. */
    public static String folderName(Context ctx) {
        Uri uri = folderUri(ctx);
        if (uri == null) return "Downloads";
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(uri,
                    DocumentsContract.getTreeDocumentId(uri));
            Cursor c = ctx.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document
                            .COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String n = c.getString(0);
                        if (n != null && !n.isEmpty()) return n;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            // Fall through to the generic label.
        }
        return "Chosen folder";
    }

    public static void clearFolder(Context ctx) {
        Uri uri = folderUri(ctx);
        if (uri != null) {
            try {
                ctx.getContentResolver()
                        .releasePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent
                                        .FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                // Already gone; forgetting the pref is what matters.
            }
        }
        Ui.prefs(ctx).edit().remove(PREF_URI).apply();
    }

    // ---------------- first-use prompt ----------------

    public static boolean promptDone(Context ctx) {
        return Ui.prefs(ctx).getBoolean(PREF_PROMPT_DONE, false);
    }

    public static void markPromptDone(Context ctx) {
        Ui.prefs(ctx).edit().putBoolean(PREF_PROMPT_DONE, true).apply();
    }

    // ---------------- picking ----------------

    public static void pickFolder(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            a.startActivityForResult(i, REQ_FOLDER);
        } catch (Exception e) {
            Toast.makeText(a, "No folder picker is available.",
                    Toast.LENGTH_LONG).show();
            pendingSave = null;
        }
    }

    /**
     * Route an activity result. Returns 0 when the result is not
     * ours, 1 when a folder was stored (any pending save has run),
     * and 2 when it was our picker but nothing was stored (cancelled
     * or failed). Settings uses the non-zero cases to refresh its
     * Export folder row.
     */
    public static int deliverResult(Activity a, int requestCode,
                                    int resultCode, Intent data) {
        if (requestCode != REQ_FOLDER) return 0;
        Runnable pending = pendingSave;
        pendingSave = null;
        if (resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null) {
            return 2;
        }
        Uri uri = data.getData();
        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            a.getContentResolver()
                    .takePersistableUriPermission(uri, flags);
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't keep access to that folder.",
                    Toast.LENGTH_LONG).show();
            return 2;
        }
        Ui.prefs(a).edit().putString(PREF_URI, uri.toString()).apply();
        markPromptDone(a);
        if (pending != null) {
            pending.run();
        } else {
            Toast.makeText(a, "Exports will save to "
                            + folderName(a) + ".",
                    Toast.LENGTH_LONG).show();
        }
        return 1;
    }
}
