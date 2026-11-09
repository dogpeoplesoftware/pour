package com.pour.dictation;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The delivery half of export: once text has been rendered in a format,
 * it gets saved to Downloads, saved to the user's chosen export folder
 * (see ExportFolder), or shared to another app. Shared by every export
 * flow so they all offer the same Export → format → destination flow.
 */
public final class ExportDelivery {

    private ExportDelivery() { }

    /** Destination sheet after the format picker: save to Downloads,
     * save to the chosen export folder, or share the file. The first
     * time anyone reaches this sheet with no folder chosen, it asks
     * about the folder once (see ExportFolder) and says where the
     * choice lives; after that it is just a destination list. */
    public static void askDestination(final Activity a,
                                     final Runnable doSave,
                                     final Runnable doSaveToFolder,
                                     final Runnable doShare,
                                     final Runnable chooseFolder) {
        // A stored folder whose permission is gone is a dead
        // destination: say so once and forget it.
        if (ExportFolder.hasFolder(a) && !ExportFolder.folderUsable(a)) {
            ExportFolder.clearFolder(a);
            Toast.makeText(a,
                    "The export folder is no longer available,"
                            + " so it was forgotten.",
                    Toast.LENGTH_LONG).show();
        }
        final boolean firstUse = !ExportFolder.promptDone(a);
        final boolean haveFolder = ExportFolder.hasFolder(a);
        final String fname = ExportFolder.folderName(a);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        if (firstUse) {
            android.widget.TextView blurb =
                    new android.widget.TextView(a);
            blurb.setText("Exports save to Downloads unless you pick"
                    + " a folder. You can change your choice any time"
                    + " in Settings.");
            blurb.setTextColor(Ui.CARD_MUTED);
            blurb.setTextSize(14);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.bottomMargin = Ui.dp(a, 8);
            content.addView(blurb, bp);
        }
        if (haveFolder) {
            content.addView(Sheet.row(a,
                    "Save to " + fname, "Your chosen export folder",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (sheet[0] != null) sheet[0].dismiss();
                            ExportFolder.markPromptDone(a);
                            doSaveToFolder.run();
                        }
                    }));
        }
        content.addView(Sheet.row(a,
                "Save to Downloads", "Keep the file on this device",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        ExportFolder.markPromptDone(a);
                        doSave.run();
                    }
                }));
        content.addView(Sheet.row(a,
                haveFolder ? "Change export folder"
                        : "Choose export folder",
                haveFolder ? "Pick a different folder for exports"
                        : "Pick where saved exports go",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        ExportFolder.markPromptDone(a);
                        chooseFolder.run();
                    }
                }));
        content.addView(Sheet.row(a,
                "Share to another app", "Send the file via email or another app",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (sheet[0] != null) sheet[0].dismiss();
                        ExportFolder.markPromptDone(a);
                        doShare.run();
                    }
                }));
        sheet[0] = Sheet.show(a, "Share", content);
    }

    /** Save text as <baseName>.<ext> in the Downloads folder via
     * MediaStore. No storage permission needed on Android 10+ (API 29+);
     * on older devices a toast explains instead of failing. */
    public static void saveToDownloads(Activity a,
                                       ProjectExporter.Format f,
                                       String text, String baseName) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(a,
                    "Saving to Downloads needs Android 10 or newer.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = writeDownload(a, f, text, baseName);
        if (fileName == null) {
            Toast.makeText(a, "Couldn't save the file.",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(a, "Saved to Downloads/" + fileName,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Save binary data (e.g. PDF) to Downloads. */
    public static void saveBytesToDownloads(Activity a,
                                            ProjectExporter.Format f,
                                            byte[] data, String baseName) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(a,
                    "Saving to Downloads needs Android 10 or newer.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = writeDownloadBytes(a, f, data, baseName);
        if (fileName == null) {
            Toast.makeText(a, "Couldn't save the file.",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(a, "Saved to Downloads/" + fileName,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Save several texts, one file per base name, with a single
     * summary toast instead of one toast per file. */
    public static void saveAllToDownloads(Activity a,
                                          ProjectExporter.Format f,
                                          List<String> texts,
                                          List<String> baseNames) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(a,
                    "Saving to Downloads needs Android 10 or newer.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int ok = 0;
        for (int i = 0; i < texts.size(); i++) {
            if (writeDownload(a, f, texts.get(i), baseNames.get(i))
                    != null) {
                ok++;
            }
        }
        if (ok == 0) {
            Toast.makeText(a, "Couldn't save the files.",
                    Toast.LENGTH_LONG).show();
        } else if (ok == texts.size()) {
            Toast.makeText(a, ok == 1 ? "Saved 1 file to Downloads."
                            : "Saved " + ok + " files to Downloads.",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(a, "Saved " + ok + " of " + texts.size()
                            + " files to Downloads.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Save multiple binary files (e.g. PDFs) to Downloads. */
    public static void saveAllBytesToDownloads(Activity a,
                                               ProjectExporter.Format f,
                                               List<byte[]> datas,
                                               List<String> baseNames) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(a,
                    "Saving to Downloads needs Android 10 or newer.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int ok = 0;
        for (int i = 0; i < datas.size(); i++) {
            if (writeDownloadBytes(a, f, datas.get(i), baseNames.get(i))
                    != null) {
                ok++;
            }
        }
        if (ok == 0) {
            Toast.makeText(a, "Couldn't save the files.",
                    Toast.LENGTH_LONG).show();
        } else if (ok == datas.size()) {
            Toast.makeText(a, ok == 1 ? "Saved 1 file to Downloads."
                            : "Saved " + ok + " files to Downloads.",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(a, "Saved " + ok + " of " + datas.size()
                            + " files to Downloads.",
                    Toast.LENGTH_LONG).show();
        }
    }


    // ---------------- save to the chosen export folder ----------------

    /** Save text as <baseName>.<ext> in the chosen export folder. */
    public static void saveToFolder(Activity a,
                                    ProjectExporter.Format f,
                                    String text, String baseName) {
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Exception e) {
            data = null;
        }
        if (data == null || !saveOneToFolder(a, f, data, baseName)) {
            folderSaveFailed(a);
        }
    }

    /** Save binary data (e.g. PDF) to the chosen export folder. */
    public static void saveBytesToFolder(Activity a,
                                         ProjectExporter.Format f,
                                         byte[] data, String baseName) {
        if (!saveOneToFolder(a, f, data, baseName)) {
            folderSaveFailed(a);
        }
    }

    private static boolean saveOneToFolder(Activity a,
                                           ProjectExporter.Format f,
                                           byte[] data, String base) {
        String fileName = writeFolderBytes(a, f, data, base);
        if (fileName == null) return false;
        Toast.makeText(a, "Saved to " + ExportFolder.folderName(a)
                        + "/" + fileName,
                Toast.LENGTH_LONG).show();
        return true;
    }

    /** Save several texts to the chosen folder, one summary toast. */
    public static void saveAllToFolder(Activity a,
                                       ProjectExporter.Format f,
                                       List<String> texts,
                                       List<String> baseNames) {
        int ok = 0;
        for (int i = 0; i < texts.size(); i++) {
            byte[] data;
            try {
                data = texts.get(i).getBytes("UTF-8");
            } catch (Exception e) {
                data = null;
            }
            if (data != null && writeFolderBytes(a, f, data,
                    baseNames.get(i)) != null) {
                ok++;
            }
        }
        folderSummary(a, ok, texts.size());
    }

    /** Save several binary files to the chosen folder. */
    public static void saveAllBytesToFolder(Activity a,
                                            ProjectExporter.Format f,
                                            List<byte[]> datas,
                                            List<String> baseNames) {
        int ok = 0;
        for (int i = 0; i < datas.size(); i++) {
            if (writeFolderBytes(a, f, datas.get(i),
                    baseNames.get(i)) != null) {
                ok++;
            }
        }
        folderSummary(a, ok, datas.size());
    }

    private static void folderSummary(Activity a, int ok, int total) {
        if (ok == 0) {
            folderSaveFailed(a);
            return;
        }
        String fname = ExportFolder.folderName(a);
        if (ok == total) {
            Toast.makeText(a, ok == 1 ? "Saved 1 file to " + fname + "."
                            : "Saved " + ok + " files to " + fname + ".",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(a, "Saved " + ok + " of " + total
                            + " files to " + fname + ".",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** A failed folder save almost always means the folder moved or
     * its permission was revoked: say so and forget the folder, so
     * the sheet stops offering a dead destination. */
    private static void folderSaveFailed(Activity a) {
        String fname = ExportFolder.folderName(a);
        ExportFolder.clearFolder(a);
        Toast.makeText(a, "Couldn't save to " + fname
                        + ". It may have moved, so it was forgotten."
                        + " Downloads still works.",
                Toast.LENGTH_LONG).show();
    }

    /** Create and write one file inside the chosen folder's tree;
     * returns the display name used, or null on any failure. */
    private static String writeFolderBytes(Activity a,
                                           ProjectExporter.Format f,
                                           byte[] data, String base) {
        android.net.Uri tree = ExportFolder.folderUri(a);
        if (tree == null || !ExportFolder.folderUsable(a)) return null;
        try {
            String parentId = DocumentsContract.getTreeDocumentId(tree);
            android.net.Uri parent = DocumentsContract
                    .buildDocumentUriUsingTree(tree, parentId);
            String fileName = uniqueFolderName(a, tree, parentId,
                    base, f.ext);
            android.net.Uri doc = DocumentsContract.createDocument(
                    a.getContentResolver(), parent, f.mime, fileName);
            if (doc == null) return null;
            OutputStream out = null;
            try {
                out = a.getContentResolver().openOutputStream(doc);
                if (out == null) throw new IOException("no stream");
                out.write(data);
                out.flush();
            } catch (Exception e) {
                try {
                    DocumentsContract.deleteDocument(
                            a.getContentResolver(), doc);
                } catch (Exception ignored) { }
                return null;
            } finally {
                try { if (out != null) out.close(); }
                catch (Exception ignored) { }
            }
            return fileName;
        } catch (Exception e) {
            return null;
        }
    }

    /** Collision-safe name inside the folder: "name.ext",
     * "name (2).ext", ... against the folder's current children. */
    private static String uniqueFolderName(Activity a,
                                           android.net.Uri tree,
                                           String parentId,
                                           String base, String ext) {
        java.util.Set<String> existing = new java.util.HashSet<String>();
        Cursor c = null;
        try {
            android.net.Uri children = DocumentsContract
                    .buildChildDocumentsUriUsingTree(tree, parentId);
            c = a.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document
                            .COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String n = c.getString(0);
                    if (n != null) existing.add(n);
                }
            }
        } catch (Exception e) {
            // An unreadable listing just falls back to the plain name;
            // createDocument itself renames on collision as a backup.
        } finally {
            if (c != null) c.close();
        }
        String name = base + "." + ext;
        int n = 2;
        while (existing.contains(name)) {
            name = base + " (" + n + ")." + ext;
            n++;
        }
        return name;
    }

    /** Insert and write one download; returns the display name, or
     * null on failure (any partial row is cleaned up). No toasts —
     * the caller reports. */
    private static String writeDownload(Activity a,
                                        ProjectExporter.Format f,
                                        String text, String base) {
        try {
            return writeDownloadBytes(a, f, text.getBytes("UTF-8"), base);
        } catch (Exception e) {
            return null;
        }
    }

    private static String writeDownloadBytes(Activity a,
                                             ProjectExporter.Format f,
                                             byte[] data, String base) {
        String fileName = uniqueDownloadName(a, base, f.ext);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        v.put(MediaStore.Downloads.MIME_TYPE, f.mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
        Uri uri;
        try {
            uri = a.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) {
            uri = null;
        }
        if (uri == null) return null;
        OutputStream out = null;
        try {
            out = a.getContentResolver().openOutputStream(uri);
            if (out == null) throw new IOException("no stream");
            out.write(data);
            out.flush();
        } catch (Exception e) {
            try { a.getContentResolver().delete(uri, null, null); }
            catch (Exception ignored) { }
            return null;
        } finally {
            try { if (out != null) out.close(); }
            catch (Exception ignored) { }
        }
        return fileName;
    }

    /** Write text as <baseName>.<ext> to the cache dir and send it to
     * another app as a real file attachment. Send-only: use
     * saveToDownloads to keep a file on the device. */
    public static void shareFile(Activity a,
                                 ProjectExporter.Format f,
                                 String text, String baseName,
                                 String chooserTitle) {
        // A real file with a real extension, so the receiving app knows
        // what it got. Served read-only from the cache dir.
        File dir = new File(a.getCacheDir(), "exports");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toast.makeText(a, "Couldn't prepare the file.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = baseName + "." + f.ext;
        File out = new File(dir, fileName);
        java.io.OutputStreamWriter w = null;
        try {
            w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(out), "UTF-8");
            w.write(text);
            w.flush();
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't write the file.",
                    Toast.LENGTH_LONG).show();
            return;
        } finally {
            try { if (w != null) w.close(); }
            catch (Exception ignored) { }
        }
        shareCachedFile(a, f, out, baseName, chooserTitle);
    }

    /** Share binary data (e.g. PDF) as a real file attachment. */
    public static void shareBytes(Activity a,
                                  ProjectExporter.Format f,
                                  byte[] data, String baseName,
                                  String chooserTitle) {
        File dir = new File(a.getCacheDir(), "exports");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toast.makeText(a, "Couldn't prepare the file.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = baseName + "." + f.ext;
        File out = new File(dir, fileName);
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(out);
            fos.write(data);
            fos.flush();
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't write the file.",
                    Toast.LENGTH_LONG).show();
            return;
        } finally {
            try { if (fos != null) fos.close(); }
            catch (Exception ignored) { }
        }
        shareCachedFile(a, f, out, baseName, chooserTitle);
    }

    private static void shareCachedFile(Activity a,
                                        ProjectExporter.Format f,
                                        File out, String baseName,
                                        String chooserTitle) {
        Uri uri = ShareProvider.uriFor(out);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(f.mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, baseName);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(send, chooserTitle));
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't share. Try again.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Write each text as <baseName>.<ext> to the cache dir and send
     * them to another app as real file attachments
     * (ACTION_SEND_MULTIPLE). Send-only: use saveAllToDownloads to
     * keep files on the device. */
    public static void shareFiles(Activity a,
                                  ProjectExporter.Format f,
                                  List<String> texts,
                                  List<String> baseNames,
                                  String chooserTitle) {
        // Real files with real extensions, so the receiving app knows
        // what it got. Served read-only from the cache dir.
        File dir = new File(a.getCacheDir(), "exports");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toast.makeText(a, "Couldn't prepare the files.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<Uri>();
        for (int i = 0; i < texts.size(); i++) {
            String fileName = uniqueCacheName(dir,
                    baseNames.get(i), f.ext);
            File out = new File(dir, fileName);
            java.io.OutputStreamWriter w = null;
            try {
                w = new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(out), "UTF-8");
                w.write(texts.get(i));
                w.flush();
            } catch (Exception e) {
                Toast.makeText(a, "Couldn't write the files.",
                        Toast.LENGTH_LONG).show();
                return;
            } finally {
                try { if (w != null) w.close(); }
                catch (Exception ignored) { }
            }
            uris.add(ShareProvider.uriFor(out));
        }
        if (uris.isEmpty()) {
            Toast.makeText(a, "Nothing to export.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
        send.setType(f.mime);
        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(send, chooserTitle));
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't share. Try again.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Share multiple binary files (e.g. PDFs) as attachments. */
    public static void shareBytesList(Activity a,
                                      ProjectExporter.Format f,
                                      List<byte[]> datas,
                                      List<String> baseNames,
                                      String chooserTitle) {
        File dir = new File(a.getCacheDir(), "exports");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toast.makeText(a, "Couldn't prepare the files.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<Uri>();
        for (int i = 0; i < datas.size(); i++) {
            String fileName = uniqueCacheName(dir,
                    baseNames.get(i), f.ext);
            File out = new File(dir, fileName);
            java.io.FileOutputStream fos = null;
            try {
                fos = new java.io.FileOutputStream(out);
                fos.write(datas.get(i));
                fos.flush();
            } catch (Exception e) {
                Toast.makeText(a, "Couldn't write the files.",
                        Toast.LENGTH_LONG).show();
                return;
            } finally {
                try { if (fos != null) fos.close(); }
                catch (Exception ignored) { }
            }
            uris.add(ShareProvider.uriFor(out));
        }
        if (uris.isEmpty()) {
            Toast.makeText(a, "Nothing to export.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
        send.setType(f.mime);
        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(send, chooserTitle));
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't share. Try again.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Collision-safe cache file name: "name.ext", "name (2).ext", ...
     * Two projects can sanitize to the same base name, so the cache
     * dir needs the same protection as Downloads. */
    private static String uniqueCacheName(File dir, String base,
                                          String ext) {
        String name = base + "." + ext;
        int n = 2;
        while (new File(dir, name).exists()) {
            name = base + " (" + n + ")." + ext;
            n++;
        }
        return name;
    }

    /** Collision-safe display name: "name.ext", "name (2).ext", ... */
    private static String uniqueDownloadName(Activity a, String base,
                                             String ext) {
        String name = base + "." + ext;
        int n = 2;
        while (downloadNameExists(a, name)) {
            name = base + " (" + n + ")." + ext;
            n++;
        }
        return name;
    }

    private static boolean downloadNameExists(Activity a, String name) {
        Cursor c = null;
        try {
            c = a.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[]{name}, null);
            return c != null && c.getCount() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }
}
