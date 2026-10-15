package com.pour.dictation;

import android.util.Log;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One-tap backup of everything the app owns: Bucket notes, projects,
 * sections (with their bodies), and folders, as versioned JSON the
 * user moves themselves (Storage Access Framework). Restore validates
 * before touching the database and merges — it never wipes: entities
 * whose ids already exist are skipped, the rest are inserted with
 * their original ids so section/project links stay intact.
 *
 * Owner policy: no audio recordings are kept, ever — audio paths are
 * excluded from backups entirely.
 */
public class BackupHelper {

    public static final int REQ_EXPORT = 4101;
    public static final int REQ_IMPORT = 4102;
    private static final int FORMAT_VERSION = 2;

    /** Suggest a dated filename for the SAF create-document dialog. */
    public static String suggestedName() {
        String d = new SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new Date());
        return "pour-pro-backup-" + d + ".json";
    }

    public static void exportRequest(Activity a) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, suggestedName());
        try {
            a.startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            Log.w("BackupHelper", "Suppressed exception", e);
        }
    }

    public static void importRequest(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        try {
            a.startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Log.w("BackupHelper", "Suppressed exception", e);
        }
    }

    /** Write the backup. Returns a human summary, or null on failure. */
    public static String doExport(Activity a, Uri dest) {
        try {
            NotesDb db = new NotesDb(a);
            JSONObject root = new JSONObject();
            root.put("format", FORMAT_VERSION);
            root.put("app", "com.pour.dictation");
            root.put("exportedAt", System.currentTimeMillis());

            JSONArray notes = new JSONArray();
            for (Note n : allNotes(db)) {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("title", s(n.title));
                o.put("transcript", s(n.transcript));
                o.put("tags", s(n.tags));
                o.put("created", n.created);
                o.put("words", n.words);
                o.put("done", n.done);
                o.put("projectId", n.projectId);
                o.put("sectionId", n.sectionId);
                o.put("pos", n.pos);
                notes.put(o);
            }
            root.put("notes", notes);

            JSONArray projects = new JSONArray();
            for (Project p : db.allProjects()) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", s(p.name));
                o.put("created", p.created);
                o.put("pos", p.pos);
                projects.put(o);
            }
            root.put("projects", projects);

            JSONArray sections = new JSONArray();
            for (Project p : db.allProjects()) {
                for (Section s : db.sectionsFor(p.id)) {
                    JSONObject o = new JSONObject();
                    o.put("id", s.id);
                    o.put("projectId", s.projectId);
                    o.put("name", s(s.name));
                    o.put("pos", s.pos);
                    o.put("collapsed", s.collapsed);
                    o.put("folderId", s.folderId);
                    o.put("body", s(s.body));
                    o.put("words", s.words);
                    sections.put(o);
                }
            }
            root.put("sections", sections);

            JSONArray folders = new JSONArray();
            for (Project p : db.allProjects()) {
                for (Folder f : db.foldersFor(p.id)) {
                    JSONObject o = new JSONObject();
                    o.put("id", f.id);
                    o.put("projectId", f.projectId);
                    o.put("name", s(f.name));
                    o.put("pos", f.pos);
                    o.put("collapsed", f.collapsed);
                    folders.put(o);
                }
            }
            root.put("folders", folders);
            db.close();

            OutputStream out = a.getContentResolver()
                    .openOutputStream(dest);
            if (out == null) return null;
            try {
                out.write(root.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return notes.length() + (notes.length() == 1 ? " piece, "
                    : " pieces, ") + projects.length()
                    + (projects.length() == 1 ? " project backed up."
                            : " projects backed up.");
        } catch (Exception e) {
            return null;
        }
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }

    private static List<Note> allNotes(NotesDb db) {
        // Filed notes no longer exist since 1.9.0: the Bin holds every
        // note, and project writing lives in section bodies.
        return new ArrayList<Note>(db.binNotes(null));
    }

    /**
     * Read and validate a backup, then merge it. Returns a human summary
     * like "12 pieces, 2 projects restored (3 already here).", or null
     * when the file isn't a valid Pour backup.
     */
    public static String doImport(Activity a, Uri src) {
        try {
            InputStream in = a.getContentResolver().openInputStream(src);
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            } finally {
                in.close();
            }
            JSONObject root = new JSONObject(
                    new String(bos.toByteArray(), "UTF-8"));
            // Validate before touching the database. Format 1 is
            // the pre-1.9.0 layout (sections without bodies, notes
            // filed into sections); format 2 is current.
            int format = root.optInt("format", -1);
            if (format != 1 && format != FORMAT_VERSION) return null;
            if (!"com.pour.dictation".equals(root.optString("app", ""))) {
                return null;
            }
            JSONArray jProjects = root.optJSONArray("projects");
            JSONArray jSections = root.optJSONArray("sections");
            JSONArray jNotes = root.optJSONArray("notes");
            // Pre-folder backups have no "folders" array — they restore
            // fine, everything landing loose as it was.
            JSONArray jFolders = root.optJSONArray("folders");
            if (jFolders == null) jFolders = new JSONArray();
            if (jProjects == null || jSections == null || jNotes == null) {
                return null;
            }

            NotesDb db = new NotesDb(a);
            int addedProjects = 0, addedSections = 0, addedNotes = 0;
            int skipped = 0;
            // All mutations in one transaction: a mid-import failure
            // rolls everything back instead of leaving a half-merge.
            android.database.sqlite.SQLiteDatabase sqldb =
                    db.getWritableDatabase();
            sqldb.beginTransaction();
            try {
                Set<Long> knownProjects = new HashSet<Long>();
                for (Project p : db.allProjects()) {
                    knownProjects.add(p.id);
                }
                for (int i = 0; i < jProjects.length(); i++) {
                    JSONObject o = jProjects.getJSONObject(i);
                    long id = o.getLong("id");
                    if (knownProjects.contains(id)) {
                        skipped++;
                        continue;
                    }
                    db.insertProjectWithId(id, o.optString("name",
                            "Untitled project"), o.optLong("created",
                            System.currentTimeMillis()));
                    db.setProjectPos(id, o.optInt("pos", 0));
                    knownProjects.add(id);
                    addedProjects++;
                }
                Set<Long> knownSections = new HashSet<Long>();
                for (Project p : db.allProjects()) {
                    for (Section s : db.sectionsFor(p.id)) {
                        knownSections.add(s.id);
                    }
                }
                // Folders first: sections link into them by id.
                Set<Long> knownFolders = new HashSet<Long>();
                for (Project p : db.allProjects()) {
                    for (Folder f : db.foldersFor(p.id)) {
                        knownFolders.add(f.id);
                    }
                }
                for (int i = 0; i < jFolders.length(); i++) {
                    JSONObject o = jFolders.getJSONObject(i);
                    long id = o.getLong("id");
                    long fpid = o.optLong("projectId", -1);
                    if (knownFolders.contains(id)
                            || !knownProjects.contains(fpid)) {
                        skipped++;
                        continue;
                    }
                    db.insertFolderWithId(id, fpid,
                            o.optString("name", "Untitled folder"),
                            o.optInt("pos", 0), o.optInt("collapsed", 0));
                    knownFolders.add(id);
                    addedSections++;
                }
                for (int i = 0; i < jSections.length(); i++) {
                    JSONObject o = jSections.getJSONObject(i);
                    long id = o.getLong("id");
                    if (knownSections.contains(id)
                            || !knownProjects.contains(
                                    o.optLong("projectId", -1))) {
                        skipped++;
                        continue;
                    }
                    long folderId = o.optLong("folderId", -1);
                    // Only restore a folder link the import actually
                    // contains; otherwise the section lands loose.
                    if (folderId >= 0 && !knownFolders.contains(folderId)) {
                        folderId = -1;
                    }
                    db.insertSectionWithId(id, o.getLong("projectId"),
                            o.optString("name", "Untitled piece"),
                            o.optInt("pos", 0), o.optInt("collapsed", 0),
                            folderId, o.optString("body", ""));
                    knownSections.add(id);
                    addedSections++;
                }
                // Format-1 filed notes predate section bodies: fold
                // each one into its section's body (id order, blank-line
                // separated) instead of restoring it as a note, so no
                // text is lost in the migration. Bucket notes restore
                // as before.
                java.util.Map<Long, StringBuilder> folded =
                        new java.util.HashMap<Long, StringBuilder>();
                for (int i = 0; i < jNotes.length(); i++) {
                    JSONObject o = jNotes.getJSONObject(i);
                    long id = o.getLong("id");
                    if (db.get(id) != null) {
                        skipped++;
                        continue;
                    }
                    long projectId = o.optLong("projectId", -1);
                    long sectionId = o.optLong("sectionId", -1);
                    if (sectionId >= 0
                            && knownSections.contains(sectionId)) {
                        String title = o.optString("title", "");
                        String tr = o.optString("transcript", "");
                        StringBuilder part = new StringBuilder();
                        if (!title.trim().isEmpty()
                                && !title.equals("Untitled piece")
                                && (tr == null
                                        || !tr.trim().startsWith(
                                                title.trim()))) {
                            part.append(title.trim()).append("\n");
                        }
                        if (tr != null) part.append(tr.trim());
                        String ps = part.toString().trim();
                        if (!ps.isEmpty()) {
                            StringBuilder acc = folded.get(sectionId);
                            if (acc == null) {
                                acc = new StringBuilder();
                                folded.put(sectionId, acc);
                            }
                            if (acc.length() > 0) acc.append("\n\n");
                            acc.append(ps);
                        }
                        skipped++;
                        continue;
                    }
                    // Only restore links the import actually contains.
                    if (projectId >= 0
                            && !knownProjects.contains(projectId)) {
                        skipped++;
                        continue;
                    }
                    db.insertNoteWithId(id, o.optString("title",
                            "Untitled piece"), o.optString("transcript", ""),
                            o.optString("tags", ""),
                            o.optLong("created", System.currentTimeMillis()),
                            o.optInt("words", 0), o.optInt("done", 0),
                            projectId, -1);
                    db.setNotePos(id, o.optInt("pos", 0));
                    addedNotes++;
                }
                for (java.util.Map.Entry<Long, StringBuilder> fe
                        : folded.entrySet()) {
                    Section s = db.getSection(fe.getKey());
                    if (s == null) continue;
                    String cur = s.body == null ? "" : s.body.trim();
                    String add = fe.getValue().toString().trim();
                    String merged = cur.isEmpty() ? add
                            : cur + "\n\n" + add;
                    db.updateSectionBody(s.id, merged);
                }
                // All inserts succeeded — commit the transaction.
                sqldb.setTransactionSuccessful();
            } finally {
                try { sqldb.endTransaction(); } catch (Exception ignored) { }
                db.close();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(addedNotes).append(addedNotes == 1 ? " piece, "
                    : " pieces, ").append(addedProjects)
                    .append(addedProjects == 1 ? " project"
                            : " projects").append(" restored.");
            if (skipped > 0) {
                sb.append(" (").append(skipped).append(" already here.)");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
