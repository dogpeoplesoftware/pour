package com.pour.dictation;

import android.content.ContentValues;
import android.content.Context;
import android.util.Log;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** SQLite store: the Bucket (unfiled notes), projects, and their sections. */
public class NotesDb extends SQLiteOpenHelper {

    private static final String DB = "pour_pro.db";
    private static final int VERSION = 12;

    public NotesDb(Context ctx) {
        super(ctx, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " title TEXT, transcript TEXT, tags TEXT,"
                + " created INTEGER, audio_path TEXT, words INTEGER,"
                + " pos INTEGER DEFAULT 0, done INTEGER DEFAULT 0,"
                + " project_id INTEGER DEFAULT NULL,"
                + " section_id INTEGER DEFAULT NULL,"
                + " deleted_at INTEGER DEFAULT NULL)");
        db.execSQL("CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " name TEXT, created INTEGER, pos INTEGER DEFAULT 0,"
                + " deleted_at INTEGER DEFAULT NULL)");
        db.execSQL("CREATE TABLE sections (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " project_id INTEGER, name TEXT, pos INTEGER DEFAULT 0,"
                + " collapsed INTEGER DEFAULT 0,"
                + " folder_id INTEGER DEFAULT NULL,"
                + " body TEXT DEFAULT '',"
                + " words INTEGER DEFAULT 0,"
                + " deleted_at INTEGER DEFAULT NULL)");
        db.execSQL("CREATE TABLE folders (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " project_id INTEGER, name TEXT, pos INTEGER DEFAULT 0,"
                + " collapsed INTEGER DEFAULT 0)");
        // Section-scoped ordering is the hot path for drag-and-drop.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_section"
                + " ON notes(section_id, pos)");
        // Trash filtering hits deleted_at in every list query.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_deleted"
                + " ON notes(deleted_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sections_deleted"
                + " ON sections(deleted_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_deleted"
                + " ON projects(deleted_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN pos INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE notes ADD COLUMN done INTEGER DEFAULT 0");
            db.execSQL("UPDATE notes SET pos = (SELECT COUNT(*) FROM notes n2"
                    + " WHERE n2.created < notes.created"
                    + " OR (n2.created = notes.created AND n2._id < notes._id))");
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " name TEXT, created INTEGER)");
            db.execSQL("CREATE TABLE sections (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " project_id INTEGER, name TEXT, pos INTEGER DEFAULT 0)");
            db.execSQL("ALTER TABLE notes ADD COLUMN project_id INTEGER DEFAULT NULL");
            db.execSQL("ALTER TABLE notes ADD COLUMN section_id INTEGER DEFAULT NULL");
            // Preserve the round-2 manuscript: every existing note keeps its
            // order and done state inside one migrated project.
            long now = System.currentTimeMillis();
            db.execSQL("INSERT INTO projects (name, created) VALUES"
                    + " ('My project', " + now + ")");
            long pid = lastId(db);
            db.execSQL("INSERT INTO sections (project_id, name, pos) VALUES"
                    + " (" + pid + ", 'Pieces', 0)");
            long sid = lastId(db);
            db.execSQL("UPDATE notes SET project_id=" + pid
                    + ", section_id=" + sid);
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE sections ADD COLUMN"
                    + " collapsed INTEGER DEFAULT 0");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_section"
                    + " ON notes(section_id, pos)");
            // Owner policy (Oct 2026): no audio recordings are kept, ever.
            // Nothing in the app writes audio files — transcription streams
            // from the mic straight to the recognizer — but clear any
            // reference an older build may have left, deleting the files
            // it points at. Note text and metadata are untouched.
            Cursor ap = null;
            try {
                ap = db.rawQuery("SELECT DISTINCT audio_path FROM notes"
                        + " WHERE audio_path IS NOT NULL"
                        + " AND audio_path != ''", null);
                while (ap.moveToNext()) {
                    String p = ap.getString(0);
                    try {
                        java.io.File f = new java.io.File(p);
                        if (f.exists()) f.delete();
                    } catch (Exception e) {
                        Log.w("NotesDb", "Suppressed exception", e);
                    }
                }
            } catch (Exception e) {
                Log.w("NotesDb", "Suppressed exception", e);
            }
            finally {
                if (ap != null) ap.close();
            }
            db.execSQL("UPDATE notes SET audio_path = NULL");
        }
        if (oldVersion < 5) {
            // The migration-created "Pieces" holding section is an unsorted
            // pile, not workspace — it starts tucked away collapsed. One
            // tap expands it; nothing is hidden, just out of the way.
            db.execSQL("UPDATE sections SET collapsed = 1"
                    + " WHERE name = 'Notes'");
        }
        if (oldVersion < 6) {
            // Owner directive (Oct 2026): the "Pieces" holding section is
            // dead — the Bucket is the unsorted pile. Its notes go back to
            // the Bucket (project/section unassigned; text, order metadata
            // and everything else untouched) and the section is deleted.
            // Heuristic: exact name match on 'Notes', the auto-created
            // holding section. The v5 collapse above is moot once the
            // sections are gone, but stays as migration history.
            Cursor c = null;
            try {
                c = db.rawQuery("SELECT _id FROM sections"
                        + " WHERE name = 'Notes'", null);
                while (c.moveToNext()) {
                    long sid = c.getLong(0);
                    db.execSQL("UPDATE notes SET project_id = NULL,"
                            + " section_id = NULL"
                            + " WHERE section_id = " + sid);
                    db.execSQL("DELETE FROM sections WHERE _id = " + sid);
                }
            } catch (Exception e) {
                Log.w("NotesDb", "Suppressed exception", e);
            }
            finally {
                if (c != null) c.close();
            }
        }
        if (oldVersion < 7) {
            // Opt-in folders (round 6): a folders table plus a nullable
            // folder_id on sections. Existing sections get folder_id
            // NULL — every section stays loose at the top level, in its
            // current pos order. No data loss, no reordering.
            db.execSQL("CREATE TABLE folders"
                    + " (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " project_id INTEGER, name TEXT,"
                    + " pos INTEGER DEFAULT 0,"
                    + " collapsed INTEGER DEFAULT 0)");
            db.execSQL("ALTER TABLE sections ADD COLUMN"
                    + " folder_id INTEGER DEFAULT NULL");
        }
        if (oldVersion < 8) {
            // Sections become the writable leaf (1.9.0): each section
            // gets its own body text plus a cached word count. Existing
            // sections absorb their notes' transcripts in position
            // order, blank-line separated; a note whose title isn't the
            // auto-generated kind keeps its title as a heading line so
            // no authored text is lost. The notes themselves are then
            // deleted — their text now lives in the section body.
            db.execSQL("ALTER TABLE sections ADD COLUMN"
                    + " body TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE sections ADD COLUMN"
                    + " words INTEGER DEFAULT 0");
            Cursor sc = null;
            try {
                sc = db.rawQuery("SELECT _id FROM sections", null);
                while (sc.moveToNext()) {
                    long sid = sc.getLong(0);
                    StringBuilder body = new StringBuilder();
                    Cursor nc = null;
                    try {
                        nc = db.rawQuery(
                                "SELECT title, transcript FROM notes"
                                        + " WHERE section_id=? "
                                        + " ORDER BY pos ASC, _id ASC",
                                new String[]{String.valueOf(sid)});
                        while (nc.moveToNext()) {
                            String title = nc.getString(0);
                            String tr = nc.getString(1);
                            if (tr == null) tr = "";
                            tr = tr.trim();
                            StringBuilder part = new StringBuilder();
                            if (title != null
                                    && !title.trim().isEmpty()
                                    && !isAutoTitle(title, tr)) {
                                part.append(title.trim()).append("\n");
                            }
                            part.append(tr);
                            String ps = part.toString().trim();
                            if (ps.isEmpty()) continue;
                            if (body.length() > 0) body.append("\n\n");
                            body.append(ps);
                        }
                    } finally {
                        if (nc != null) nc.close();
                    }
                    String b = body.toString();
                    db.execSQL("UPDATE sections SET body=?, words=?"
                            + " WHERE _id=?",
                            new Object[]{b, countWords(b), sid});
                    db.execSQL("DELETE FROM notes WHERE section_id=?",
                            new Object[]{sid});
                }
            } finally {
                if (sc != null) sc.close();
            }
        }
        if (oldVersion < 9) {
            // Projects get a manual order (1.9.15): drag to reorder on
            // the Projects list. Existing projects keep creation order.
            db.execSQL("ALTER TABLE projects ADD COLUMN"
                    + " pos INTEGER DEFAULT 0");
            db.execSQL("UPDATE projects SET pos = (SELECT COUNT(*)"
                    + " FROM projects p2 WHERE p2.created < projects.created"
                    + " OR (p2.created = projects.created"
                    + " AND p2._id < projects._id))");
        }
        if (oldVersion < 10) {
            // Trash (1.0.16): soft delete via deleted_at timestamp.
            // NULL = live, non-NULL = in trash (millis since epoch).
            db.execSQL("ALTER TABLE notes ADD COLUMN"
                    + " deleted_at INTEGER DEFAULT NULL");
            db.execSQL("ALTER TABLE sections ADD COLUMN"
                    + " deleted_at INTEGER DEFAULT NULL");
        }
        if (oldVersion < 11) {
            // Trash projects as a whole (1.0.17): soft delete the
            // project row; its sections/folders stay linked.
            db.execSQL("ALTER TABLE projects ADD COLUMN"
                    + " deleted_at INTEGER DEFAULT NULL");
        }
        if (oldVersion < 12) {
            // Indexes for trash filtering (every list query filters
            // on deleted_at IS NULL).
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_deleted"
                    + " ON notes(deleted_at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sections_deleted"
                    + " ON sections(deleted_at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_deleted"
                    + " ON projects(deleted_at)");
        }
    }

    /** True when the title looks auto-generated from the transcript
     * (empty, "Untitled piece", or the transcript's own opening words):
     * not worth keeping as a separate heading line on migration. */
    private static boolean isAutoTitle(String title, String transcript) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty() || t.equals("Untitled piece")) return true;
        if (t.endsWith("\u2026")) t = t.substring(0, t.length() - 1);
        String body = transcript == null ? "" : transcript.trim();
        return !t.isEmpty() && body.startsWith(t);
    }

    private long lastId(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT last_insert_rowid()", null);
        long id = 1;
        try {
            if (c.moveToFirst()) id = c.getLong(0);
        } finally {
            c.close();
        }
        return id;
    }

    // ---------------- notes: the Bin ----------------

    /** A fresh dictation always lands in the Bin: unfiled, unordered. */
    public long insert(String title, String transcript, String tags, long created,
                       String audioPath, int words) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("transcript", transcript);
        v.put("tags", tags);
        v.put("created", created);
        v.put("audio_path", audioPath);
        v.put("words", words);
        v.put("pos", nextBucketPos());
        v.put("done", 0);
        v.putNull("project_id");
        v.putNull("section_id");
        return getWritableDatabase().insert("notes", null, v);
    }

    /** Insert a note with an explicit id (backup restore / undo). The
     * note keeps its original position inside its section. */
    public void insertNoteWithId(long id, String title, String transcript,
                                 String tags, long created, int words,
                                 int done, long projectId, long sectionId) {
        ContentValues v = new ContentValues();
        v.put("_id", id);
        v.put("title", title);
        v.put("transcript", transcript);
        v.put("tags", tags);
        v.put("created", created);
        v.put("words", words);
        v.put("done", done);
        if (projectId < 0) v.putNull("project_id");
        else v.put("project_id", projectId);
        if (sectionId < 0) v.putNull("section_id");
        else v.put("section_id", sectionId);
        v.put("pos", 0);
        getWritableDatabase().insert("notes", null, v);
    }

    /** Bin notes: unfiled, newest first unless told otherwise; optional
     * case-insensitive search. */
    public List<Note> binNotes(String query, boolean newestFirst) {
        return binNotes(query, newestFirst ? "newest" : "oldest");
    }

    /** One less than the lowest Bucket pos: new notes take this so
     * they land on top of a manual order. */
    public int nextBucketPos() {
        return nextBucketPos(getReadableDatabase());
    }

    private int nextBucketPos(SQLiteDatabase db) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT MIN(pos) FROM notes WHERE project_id IS NULL"
                            + " AND deleted_at IS NULL",
                    null);
            if (c.moveToFirst() && !c.isNull(0)) return c.getInt(0) - 1;
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    /** Persist a manual project order: the ids in display order. */
    public void reorderProjects(List<Long> orderedIds) {
        SQLiteDatabase w = getWritableDatabase();
        w.beginTransaction();
        try {
            for (int i = 0; i < orderedIds.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("pos", i);
                w.update("projects", v, "_id=?",
                        new String[]{String.valueOf(orderedIds.get(i))});
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    /** Persist a manual Bucket order: the ids in display order. */
    public void reorderBucket(List<Long> orderedIds) {
        SQLiteDatabase w = getWritableDatabase();
        w.beginTransaction();
        try {
            for (int i = 0; i < orderedIds.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("pos", i);
                w.update("notes", v, "_id=?",
                        new String[]{String.valueOf(orderedIds.get(i))});
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    /** Set one note's manual Bucket position. */
    public void setNotePos(long id, int pos) {
        ContentValues v = new ContentValues();
        v.put("pos", pos);
        getWritableDatabase().update("notes", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Bin notes with an explicit sort mode: "custom" (manual order,
     * new notes on top), "newest", or "oldest". */
    public List<Note> binNotes(String query, String sortMode) {
        List<Note> out = new ArrayList<>();
        String sel = "project_id IS NULL AND deleted_at IS NULL";
        String[] args = null;
        if (query != null && !query.trim().isEmpty()) {
            sel += " AND (title LIKE ? OR transcript LIKE ? OR tags LIKE ?)";
            String q = "%" + query.trim() + "%";
            args = new String[]{q, q, q};
        }
        String orderBy;
        if ("custom".equals(sortMode)) {
            orderBy = "pos ASC, created DESC";
        } else if ("oldest".equals(sortMode)) {
            orderBy = "created ASC";
        } else {
            orderBy = "created DESC";
        }
        Cursor c = getReadableDatabase().query("notes", null, sel, args,
                null, null, orderBy);
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    public List<Note> binNotes(String query) {
        return binNotes(query, true);
    }

    /** Lightweight headers for list views: title, snippet (first 200
     * chars), metadata — but NOT the full transcript. Use get(id)
     * when opening the editor. */
    public List<Note> binNoteHeaders(String query, String sortMode) {
        List<Note> out = new ArrayList<>();
        String sel = "project_id IS NULL AND deleted_at IS NULL";
        String[] args = null;
        if (query != null && !query.trim().isEmpty()) {
            sel += " AND (title LIKE ? OR transcript LIKE ? OR tags LIKE ?)";
            String q = "%" + query.trim() + "%";
            args = new String[]{q, q, q};
        }
        String orderBy;
        if ("custom".equals(sortMode)) {
            orderBy = "pos ASC, created DESC";
        } else if ("oldest".equals(sortMode)) {
            orderBy = "created ASC";
        } else {
            orderBy = "created DESC";
        }
        String[] cols = new String[]{"_id", "title",
                "substr(transcript,1,200) AS transcript",
                "tags", "created", "words", "pos", "done",
                "project_id", "section_id"};
        Cursor c = getReadableDatabase().query("notes", cols, sel, args,
                null, null, orderBy);
        try {
            while (c.moveToNext()) {
                Note n = new Note();
                n.id = c.getLong(0);
                n.title = c.getString(1);
                n.transcript = c.getString(2);
                n.tags = c.getString(3);
                n.created = c.getLong(4);
                n.words = c.getInt(5);
                n.pos = c.getInt(6);
                n.done = c.getInt(7);
                int pi = 8;
                n.projectId = c.isNull(pi) ? -1 : c.getLong(pi);
                int si = 9;
                n.sectionId = c.isNull(si) ? -1 : c.getLong(si);
                out.add(n);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Note get(long id) {
        Cursor c = getReadableDatabase().query("notes", null, "_id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        Note n = c.moveToFirst() ? fromCursor(c) : null;
        c.close();
        return n;
    }

    public void update(Note n) {
        ContentValues v = new ContentValues();
        v.put("title", n.title);
        v.put("transcript", n.transcript);
        v.put("tags", n.tags);
        v.put("words", n.words);
        v.put("pos", n.pos);
        v.put("done", n.done);
        if (n.projectId < 0) v.putNull("project_id");
        else v.put("project_id", n.projectId);
        if (n.sectionId < 0) v.putNull("section_id");
        else v.put("section_id", n.sectionId);
        getWritableDatabase().update("notes", v, "_id=?",
                new String[]{String.valueOf(n.id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete("notes", "_id=?",
                new String[]{String.valueOf(id)});
    }

    // ---------------- trash (soft delete) ----------------

    /** Move a note to trash (soft delete). */
    public void trashNote(long id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", System.currentTimeMillis());
        getWritableDatabase().update("notes", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Move a section to trash (soft delete). */
    public void trashSection(long id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", System.currentTimeMillis());
        getWritableDatabase().update("sections", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Restore a trashed note. */
    public void restoreNote(long id) {
        ContentValues v = new ContentValues();
        v.putNull("deleted_at");
        getWritableDatabase().update("notes", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Restore a trashed section. */
    public void restoreSection(long id) {
        ContentValues v = new ContentValues();
        v.putNull("deleted_at");
        getWritableDatabase().update("sections", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Permanently delete a trashed note. */
    public void hardDeleteNote(long id) {
        getWritableDatabase().delete("notes",
                "_id=? AND deleted_at IS NOT NULL",
                new String[]{String.valueOf(id)});
    }

    /** Permanently delete a trashed section. */
    public void hardDeleteSection(long id) {
        getWritableDatabase().delete("sections",
                "_id=? AND deleted_at IS NOT NULL",
                new String[]{String.valueOf(id)});
    }

    /** All trashed notes, newest trash first. */
    public List<Note> trashedNotes() {
        List<Note> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("notes", null,
                "deleted_at IS NOT NULL", null, null, null,
                "deleted_at DESC");
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** All trashed sections, newest trash first. */
    public List<Section> trashedSections() {
        List<Section> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sections", null,
                "deleted_at IS NOT NULL", null, null, null,
                "deleted_at DESC");
        try {
            while (c.moveToNext()) out.add(sectionFromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** Permanently delete everything in trash. */
    public void emptyTrash() {
        SQLiteDatabase w = getWritableDatabase();
        w.delete("notes", "deleted_at IS NOT NULL", null);
        w.delete("sections", "deleted_at IS NOT NULL", null);
        // Trashed projects and their contents.
        Cursor c = null;
        try {
            c = w.query("projects", new String[]{"_id"},
                    "deleted_at IS NOT NULL", null, null, null, null);
            while (c.moveToNext()) {
                long pid = c.getLong(0);
                w.delete("sections", "project_id=?",
                        new String[]{String.valueOf(pid)});
                w.delete("folders", "project_id=?",
                        new String[]{String.valueOf(pid)});
            }
            w.delete("projects", "deleted_at IS NOT NULL", null);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    /** Permanently delete trash older than the cutoff (millis epoch).
     * Used by the timed auto-delete setting. */
    public void autoDeleteOldTrash(long cutoffMs) {
        SQLiteDatabase w = getWritableDatabase();
        String sel = "deleted_at IS NOT NULL AND deleted_at < ?";
        String[] args = new String[]{String.valueOf(cutoffMs)};
        w.delete("notes", sel, args);
        w.delete("sections", sel, args);
        // Trashed projects: hard-delete the project and everything in it.
        Cursor c = null;
        try {
            c = w.query("projects", new String[]{"_id"}, sel, args,
                    null, null, null);
            while (c.moveToNext()) {
                long pid = c.getLong(0);
                w.delete("sections", "project_id=?",
                        new String[]{String.valueOf(pid)});
                w.delete("folders", "project_id=?",
                        new String[]{String.valueOf(pid)});
                w.delete("projects", "_id=?",
                        new String[]{String.valueOf(pid)});
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    /** Total number of notes in the database, including trashed ones. */
    public int noteCount() {
        return intQuery("SELECT COUNT(*) FROM notes");
    }

    /** Count of items currently in trash. */
    public int trashCount() {
        int n = intQuery("SELECT COUNT(*) FROM notes"
                + " WHERE deleted_at IS NOT NULL");
        n += intQuery("SELECT COUNT(*) FROM sections"
                + " WHERE deleted_at IS NOT NULL");
        n += intQuery("SELECT COUNT(*) FROM projects"
                + " WHERE deleted_at IS NOT NULL");
        return n;
    }

    // ---------------- project trash (whole-project soft delete) ----------------

    /** Move a whole project to trash. Its sections and folders stay
     * linked — they're hidden because the project is hidden. */
    public void trashProject(long id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", System.currentTimeMillis());
        getWritableDatabase().update("projects", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Restore a trashed project with all its pieces and folders. */
    public void restoreProject(long id) {
        ContentValues v = new ContentValues();
        v.putNull("deleted_at");
        getWritableDatabase().update("projects", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** All trashed projects, newest trash first. */
    public List<Project> trashedProjects() {
        List<Project> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("projects", null,
                "deleted_at IS NOT NULL", null, null, null,
                "deleted_at DESC");
        while (c.moveToNext()) {
            Project p = new Project();
            p.id = c.getLong(c.getColumnIndexOrThrow("_id"));
            p.name = c.getString(c.getColumnIndexOrThrow("name"));
            p.created = c.getLong(c.getColumnIndexOrThrow("created"));
            int pi = c.getColumnIndex("pos");
            p.pos = pi >= 0 ? c.getInt(pi) : 0;
            int di = c.getColumnIndex("deleted_at");
            p.deletedAt = (di >= 0 && !c.isNull(di))
                    ? c.getLong(di) : null;
            out.add(p);
        }
        c.close();
        return out;
    }

    /** Permanently delete a trashed project and everything in it
     * (sections, folders). */
    public void hardDeleteProject(long id) {
        SQLiteDatabase w = getWritableDatabase();
        w.delete("sections", "project_id=?",
                new String[]{String.valueOf(id)});
        w.delete("folders", "project_id=?",
                new String[]{String.valueOf(id)});
        w.delete("projects", "_id=? AND deleted_at IS NOT NULL",
                new String[]{String.valueOf(id)});
    }

    // ---------------- note flags ----------------

    public void setDone(long id, int done) {
        ContentValues v = new ContentValues();
        v.put("done", done);
        getWritableDatabase().update("notes", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    // ---------------- projects ----------------

    public long insertProject(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created", System.currentTimeMillis());
        v.put("pos", maxProjectPos() + 1);
        return getWritableDatabase().insert("projects", null, v);
    }

    /** Highest project pos: new projects go at the end. */
    private int maxProjectPos() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT MAX(pos) FROM projects", null);
            if (c.moveToFirst() && !c.isNull(0)) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    /** Insert a project with an explicit id (backup restore). */
    public void insertProjectWithId(long id, String name, long created) {
        ContentValues v = new ContentValues();
        v.put("_id", id);
        v.put("name", name);
        v.put("created", created);
        getWritableDatabase().insert("projects", null, v);
    }

    public List<Project> allProjects() {
        List<Project> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("projects", null,
                "deleted_at IS NULL", null,
                null, null, "pos ASC, created ASC");
        while (c.moveToNext()) {
            Project p = new Project();
            p.id = c.getLong(c.getColumnIndexOrThrow("_id"));
            p.name = c.getString(c.getColumnIndexOrThrow("name"));
            p.created = c.getLong(c.getColumnIndexOrThrow("created"));
            int pi = c.getColumnIndex("pos");
            p.pos = pi >= 0 ? c.getInt(pi) : 0;
            int di = c.getColumnIndex("deleted_at");
            p.deletedAt = (di >= 0 && !c.isNull(di))
                    ? c.getLong(di) : null;
            out.add(p);
        }
        c.close();
        return out;
    }

    /** Set one project's manual position. */
    public void setProjectPos(long id, int pos) {
        ContentValues v = new ContentValues();
        v.put("pos", pos);
        getWritableDatabase().update("projects", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    public Project getProject(long id) {
        Cursor c = getReadableDatabase().query("projects", null, "_id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        Project p = null;
        if (c.moveToFirst()) {
            p = new Project();
            p.id = c.getLong(c.getColumnIndexOrThrow("_id"));
            p.name = c.getString(c.getColumnIndexOrThrow("name"));
            p.created = c.getLong(c.getColumnIndexOrThrow("created"));
            int pi = c.getColumnIndex("pos");
            p.pos = pi >= 0 ? c.getInt(pi) : 0;
            int di = c.getColumnIndex("deleted_at");
            p.deletedAt = (di >= 0 && !c.isNull(di))
                    ? c.getLong(di) : null;
        }
        c.close();
        return p;
    }

    public void renameProject(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("projects", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Delete a project; each section's body text returns to the
     * Bucket as its own note, so no writing is lost. */
    /** Delete a project and everything in it. Sections do NOT return
     * to the Bucket — delete means delete. */
    public void deleteProject(long id) {
        SQLiteDatabase w = getWritableDatabase();
        w.delete("sections", "project_id=?",
                new String[]{String.valueOf(id)});
        w.delete("folders", "project_id=?",
                new String[]{String.valueOf(id)});
        w.delete("projects", "_id=?", new String[]{String.valueOf(id)});
    }

    /** Total words across a project's section bodies. */
    public int projectWordCount(long projectId) {
        return intQuery("SELECT SUM(words) FROM sections WHERE project_id="
                + projectId + " AND deleted_at IS NULL");
    }

    // ---------------- sections ----------------

    /** New sections always land loose at the end of the top level —
     * never auto-filed into a folder. The body starts empty: the
     * writer fills it in the editor. */
    public long insertSection(long projectId, String name) {
        ContentValues v = new ContentValues();
        v.put("project_id", projectId);
        v.put("name", name);
        v.put("pos", maxTopLevelPos(projectId) + 1);
        v.putNull("folder_id");
        v.put("body", "");
        v.put("words", 0);
        return getWritableDatabase().insert("sections", null, v);
    }

    /** Insert a section with an explicit id (backup restore / undo). */
    public void insertSectionWithId(long id, long projectId, String name,
                                     int pos, int collapsed) {
        insertSectionWithId(id, projectId, name, pos, collapsed, -1, "");
    }

    /** Insert a section with an explicit id and folder (backup restore /
     * undo). A folderId < 0 leaves the section loose at the top level. */
    public void insertSectionWithId(long id, long projectId, String name,
                                     int pos, int collapsed, long folderId) {
        insertSectionWithId(id, projectId, name, pos, collapsed, folderId,
                "");
    }

    /** Full insert with an explicit id, folder, and body (backup
     * restore / undo of a written section). */
    public void insertSectionWithId(long id, long projectId, String name,
                                     int pos, int collapsed, long folderId,
                                     String body) {
        ContentValues v = new ContentValues();
        v.put("_id", id);
        v.put("project_id", projectId);
        v.put("name", name);
        v.put("pos", pos);
        v.put("collapsed", collapsed);
        if (folderId < 0) v.putNull("folder_id");
        else v.put("folder_id", folderId);
        v.put("body", body == null ? "" : body);
        v.put("words", countWords(body));
        getWritableDatabase().insert("sections", null, v);
    }

    /** Highest pos across the merged top level (folders + loose
     * sections) — new top-level items append after it. */
    private int maxTopLevelPos(long projectId) {
        int pos = -1;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT MAX(pos) FROM (SELECT pos FROM folders"
                            + " WHERE project_id=? UNION ALL"
                            + " SELECT pos FROM sections"
                            + " WHERE project_id=? AND folder_id IS NULL)",
                    new String[]{String.valueOf(projectId),
                            String.valueOf(projectId)});
            if (c.moveToFirst() && !c.isNull(0)) pos = c.getInt(0);
        } catch (Exception e) {
            Log.w("NotesDb", "Suppressed exception", e);
        }
        finally {
            if (c != null) c.close();
        }
        return pos;
    }

    /** Every section of a project, loose or in a folder: loose first in
     * top-level order, then folder sections. For counts, backup walks,
     * and restore-merge — not for display order (use topLevelOrder). */
    public List<Section> sectionsFor(long projectId) {
        List<Section> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sections", null,
                "project_id=? AND deleted_at IS NULL",
                new String[]{String.valueOf(projectId)},
                null, null,
                "CASE WHEN folder_id IS NULL THEN 0 ELSE 1 END,"
                        + " pos ASC, _id ASC");
        try {
            while (c.moveToNext()) out.add(sectionFromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** Lightweight headers for project list views: name, snippet,
     * metadata — but NOT the full body. Use getSection(id) when
     * opening the editor. */
    public List<Section> sectionHeadersFor(long projectId) {
        List<Section> out = new ArrayList<>();
        String[] cols = new String[]{"_id", "project_id", "name", "pos",
                "collapsed", "folder_id",
                "substr(body,1,200) AS body", "words"};
        Cursor c = getReadableDatabase().query("sections", cols,
                "project_id=? AND deleted_at IS NULL",
                new String[]{String.valueOf(projectId)},
                null, null,
                "CASE WHEN folder_id IS NULL THEN 0 ELSE 1 END,"
                        + " pos ASC, _id ASC");
        try {
            while (c.moveToNext()) {
                Section s = new Section();
                s.id = c.getLong(0);
                s.projectId = c.getLong(1);
                s.name = c.getString(2);
                s.pos = c.getInt(3);
                s.collapsed = c.isNull(4) ? 0 : c.getInt(4);
                s.folderId = c.isNull(5) ? -1 : c.getLong(5);
                s.body = c.isNull(6) ? "" : c.getString(6);
                s.words = c.isNull(7) ? 0 : c.getInt(7);
                out.add(s);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Section getSection(long id) {
        Cursor c = getReadableDatabase().query("sections", null, "_id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        Section s = c.moveToFirst() ? sectionFromCursor(c) : null;
        c.close();
        return s;
    }

    private Section sectionFromCursor(Cursor c) {
        Section s = new Section();
        s.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        s.projectId = c.getLong(c.getColumnIndexOrThrow("project_id"));
        s.name = c.getString(c.getColumnIndexOrThrow("name"));
        s.pos = c.getInt(c.getColumnIndexOrThrow("pos"));
        int ci = c.getColumnIndex("collapsed");
        s.collapsed = (ci < 0 || c.isNull(ci)) ? 0 : c.getInt(ci);
        int fi = c.getColumnIndex("folder_id");
        s.folderId = (fi < 0 || c.isNull(fi)) ? -1 : c.getLong(fi);
        // Pre-v8 databases have no body/words columns.
        int bi = c.getColumnIndex("body");
        s.body = (bi < 0 || c.isNull(bi)) ? "" : c.getString(bi);
        int wi = c.getColumnIndex("words");
        s.words = (wi < 0 || c.isNull(wi)) ? 0 : c.getInt(wi);
        return s;
    }

    /** Save a section's body text; the cached word count follows the
     * text. */
    public void updateSectionBody(long id, String body) {
        if (body == null) body = "";
        ContentValues v = new ContentValues();
        v.put("body", body);
        v.put("words", countWords(body));
        getWritableDatabase().update("sections", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Persist a section's collapsed/expanded state. Kept for the DB
     * column's sake; sections no longer collapse in the UI (they are
     * leaf text blocks — folders are the collapsible level). */
    public void setSectionCollapsed(long id, int collapsed) {
        ContentValues v = new ContentValues();
        v.put("collapsed", collapsed);
        getWritableDatabase().update("sections", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Sections matching a query (name or body), for cross-scope
     * search. Filed notes no longer exist — sections are the
     * searchable writing inside projects. */
    public List<Section> searchSections(String query) {
        List<Section> out = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return out;
        String like = "%" + q + "%";
        Cursor c = getReadableDatabase().query("sections", null,
                "(name LIKE ? OR body LIKE ?) AND deleted_at IS NULL",
                new String[]{like, like}, null, null, "_id DESC");
        while (c.moveToNext()) out.add(sectionFromCursor(c));
        c.close();
        return out;
    }

    /** Persist a whole new loose-section order (drag-and-drop commit).
     * Only loose sections move here; in-folder order is untouched. The
     * merged top-level numbering is rebuilt behind it. */
    public void reorderSections(long projectId, List<Long> orderedIds) {
        SQLiteDatabase w = getWritableDatabase();
        for (int i = 0; i < orderedIds.size(); i++) {
            ContentValues v = new ContentValues();
            v.put("pos", i);
            w.update("sections", v,
                    "_id=? AND project_id=? AND folder_id IS NULL",
                    new String[]{String.valueOf(orderedIds.get(i)),
                            String.valueOf(projectId)});
        }
        renumberTopLevel(projectId);
    }

    /** Persist a whole new order inside one folder. */
    public void reorderSectionsInFolder(long folderId, List<Long> orderedIds) {
        SQLiteDatabase w = getWritableDatabase();
        for (int i = 0; i < orderedIds.size(); i++) {
            ContentValues v = new ContentValues();
            v.put("pos", i);
            w.update("sections", v, "_id=? AND folder_id=?",
                    new String[]{String.valueOf(orderedIds.get(i)),
                            String.valueOf(folderId)});
        }
    }

    public void renameSection(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("sections", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Delete a section without moving anything to the Bucket.
     * Undo paths only: the caller restores the text itself. */
    public void deleteSectionQuiet(long id) {
        Section s = getSection(id);
        if (s == null) return;
        SQLiteDatabase w = getWritableDatabase();
        w.delete("sections", "_id=?", new String[]{String.valueOf(id)});
        if (s.folderId >= 0) {
            renumberSectionsInFolder(s.folderId);
        } else {
            renumberSections(s.projectId);
            renumberTopLevel(s.projectId);
        }
    }

    /** Delete a section; its body text moves to the Bucket as a note
     * so no writing is lost. Its own container (its folder, or the
     * loose top level) is renumbered behind it. Returns the new Bucket
     * note id, or -1 when the body was empty. */
    public long deleteSection(long id) {
        Section s = getSection(id);
        if (s == null) return -1;
        SQLiteDatabase w = getWritableDatabase();
        long bucketId = -1;
        String body = s.body == null ? "" : s.body.trim();
        if (!body.isEmpty()) {
            ContentValues v = new ContentValues();
            v.put("title", s.name == null || s.name.trim().isEmpty()
                    ? "Untitled piece" : s.name.trim());
            v.put("transcript", body);
            v.put("tags", "");
            v.put("created", System.currentTimeMillis());
            v.putNull("audio_path");
            v.put("words", countWords(body));
            v.put("pos", nextBucketPos(w));
            v.put("done", 0);
            v.putNull("project_id");
            v.putNull("section_id");
            bucketId = w.insert("notes", null, v);
        }
        w.delete("sections", "_id=?", new String[]{String.valueOf(id)});
        if (s.folderId >= 0) {
            renumberSectionsInFolder(s.folderId);
        } else {
            renumberSections(s.projectId);
            renumberTopLevel(s.projectId);
        }
        return bucketId;
    }

    /** Jump a section to the front of its own container (its folder, or
     * the loose top level). */
    public void moveSectionToTop(long projectId, long sectionId) {
        Section s = getSection(sectionId);
        if (s == null || s.projectId != projectId) return;
        updateSectionPos(sectionId, Integer.MIN_VALUE);
        if (s.folderId >= 0) {
            renumberSectionsInFolder(s.folderId);
        } else {
            renumberSections(projectId);
            renumberTopLevel(projectId);
        }
    }

    /** Jump a section to the end of its own container. */
    public void moveSectionToBottom(long projectId, long sectionId) {
        Section s = getSection(sectionId);
        if (s == null || s.projectId != projectId) return;
        updateSectionPos(sectionId, Integer.MAX_VALUE);
        if (s.folderId >= 0) {
            renumberSectionsInFolder(s.folderId);
        } else {
            renumberSections(projectId);
            renumberTopLevel(projectId);
        }
    }

    private void updateSectionPos(long id, int pos) {
        ContentValues v = new ContentValues();
        v.put("pos", pos);
        getWritableDatabase().update("sections", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Renumber the loose sections 0..n-1 in their current order. The
     * merged top-level numbering is NOT rebuilt here — callers that
     * changed top-level membership follow with renumberTopLevel. */
    private void renumberSections(long projectId) {
        List<Section> all = looseSections(projectId);
        SQLiteDatabase w = getWritableDatabase();
        for (int i = 0; i < all.size(); i++) {
            ContentValues v = new ContentValues();
            v.put("pos", i);
            w.update("sections", v, "_id=?",
                    new String[]{String.valueOf(all.get(i).id)});
        }
    }

    /** Renumber one folder's sections 0..n-1 in their current order. */
    public void renumberSectionsInFolder(long folderId) {
        List<Section> all = sectionsInFolder(folderId);
        SQLiteDatabase w = getWritableDatabase();
        for (int i = 0; i < all.size(); i++) {
            ContentValues v = new ContentValues();
            v.put("pos", i);
            w.update("sections", v, "_id=?",
                    new String[]{String.valueOf(all.get(i).id)});
        }
    }

    // ---------------- folders ----------------

    /** New folders append at the end of the top level. */
    public long insertFolder(long projectId, String name) {
        ContentValues v = new ContentValues();
        v.put("project_id", projectId);
        v.put("name", name);
        v.put("pos", maxTopLevelPos(projectId) + 1);
        v.put("collapsed", 0);
        return getWritableDatabase().insert("folders", null, v);
    }

    /** Insert a folder with an explicit id (backup restore / undo). */
    public void insertFolderWithId(long id, long projectId, String name,
                                   int pos, int collapsed) {
        ContentValues v = new ContentValues();
        v.put("_id", id);
        v.put("project_id", projectId);
        v.put("name", name);
        v.put("pos", pos);
        v.put("collapsed", collapsed);
        getWritableDatabase().insert("folders", null, v);
    }

    public List<Folder> foldersFor(long projectId) {
        List<Folder> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("folders", null,
                "project_id=?", new String[]{String.valueOf(projectId)},
                null, null, "pos ASC, _id ASC");
        while (c.moveToNext()) out.add(folderFromCursor(c));
        c.close();
        return out;
    }

    public Folder getFolder(long id) {
        Cursor c = getReadableDatabase().query("folders", null, "_id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        Folder f = c.moveToFirst() ? folderFromCursor(c) : null;
        c.close();
        return f;
    }

    private Folder folderFromCursor(Cursor c) {
        Folder f = new Folder();
        f.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        f.projectId = c.getLong(c.getColumnIndexOrThrow("project_id"));
        f.name = c.getString(c.getColumnIndexOrThrow("name"));
        f.pos = c.getInt(c.getColumnIndexOrThrow("pos"));
        int ci = c.getColumnIndex("collapsed");
        f.collapsed = (ci < 0 || c.isNull(ci)) ? 0 : c.getInt(ci);
        return f;
    }

    public void renameFolder(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("folders", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Persist a folder's collapsed/expanded state. */
    public void setFolderCollapsed(long id, int collapsed) {
        ContentValues v = new ContentValues();
        v.put("collapsed", collapsed);
        getWritableDatabase().update("folders", v, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** A folder's sections, in order. */
    public List<Section> sectionsInFolder(long folderId) {
        List<Section> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sections", null,
                "folder_id=? AND deleted_at IS NULL",
                new String[]{String.valueOf(folderId)},
                null, null, "pos ASC, _id ASC");
        while (c.moveToNext()) out.add(sectionFromCursor(c));
        c.close();
        return out;
    }

    /** Sections sitting loose at the top level (no folder), in
     * top-level order. */
    public List<Section> looseSections(long projectId) {
        List<Section> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sections", null,
                "project_id=? AND folder_id IS NULL AND deleted_at IS NULL",
                new String[]{String.valueOf(projectId)},
                null, null, "pos ASC, _id ASC");
        while (c.moveToNext()) out.add(sectionFromCursor(c));
        c.close();
        return out;
    }

    /** One slot in the merged top-level order: either a folder or a
     * loose section. */
    public static class TopItem {
        public final boolean isFolder;
        public final long id;
        public TopItem(boolean isFolder, long id) {
            this.isFolder = isFolder;
            this.id = id;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof TopItem)) return false;
            TopItem t = (TopItem) o;
            return isFolder == t.isFolder && id == t.id;
        }
        @Override public int hashCode() {
            return (isFolder ? 1 : 0) * 31 + (int) (id ^ (id >>> 32));
        }
    }

    /** The merged top-level order: folders and loose sections by pos,
     * folders first on ties. */
    public List<TopItem> topLevelOrder(long projectId) {
        List<Folder> folders = foldersFor(projectId);
        List<Section> loose = looseSections(projectId);
        List<TopItem> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < folders.size() || j < loose.size()) {
            if (j >= loose.size()) {
                out.add(new TopItem(true, folders.get(i++).id));
            } else if (i >= folders.size()) {
                out.add(new TopItem(false, loose.get(j++).id));
            } else if (folders.get(i).pos <= loose.get(j).pos) {
                out.add(new TopItem(true, folders.get(i++).id));
            } else {
                out.add(new TopItem(false, loose.get(j++).id));
            }
        }
        return out;
    }

    /** Write a whole new merged top-level order (folder drag commit,
     * group/ungroup). Loose sections and folders share the 0..n-1
     * numbering. */
    public void reorderTopLevel(long projectId, List<TopItem> order) {
        SQLiteDatabase w = getWritableDatabase();
        for (int i = 0; i < order.size(); i++) {
            TopItem t = order.get(i);
            ContentValues v = new ContentValues();
            v.put("pos", i);
            if (t.isFolder) {
                w.update("folders", v, "_id=? AND project_id=?",
                        new String[]{String.valueOf(t.id),
                                String.valueOf(projectId)});
            } else {
                w.update("sections", v,
                        "_id=? AND project_id=? AND folder_id IS NULL",
                        new String[]{String.valueOf(t.id),
                                String.valueOf(projectId)});
            }
        }
    }

    /** Rebuild the merged top-level numbering from current pos values
     * (folders-first on ties), rewriting pos 0..n-1. */
    public void renumberTopLevel(long projectId) {
        reorderTopLevel(projectId, topLevelOrder(projectId));
    }

    /**
     * Group sections into a new folder. The folder takes the top-level
     * slot of the first grouped loose section (end of top level when
     * none is loose); the sections get folder positions 0..n-1 in the
     * given order. Sections pulled out of other folders leave those
     * renumbered behind them. Returns the new folder id, or -1.
     */
    public long groupSectionsIntoFolder(long projectId,
                                        List<Long> sectionIds, String name) {
        if (sectionIds == null || sectionIds.size() < 2) return -1;
        List<TopItem> order = topLevelOrder(projectId);
        java.util.Set<Long> wanted = new java.util.HashSet<>(sectionIds);
        int at = order.size();
        for (int i = 0; i < order.size(); i++) {
            TopItem t = order.get(i);
            if (!t.isFolder && wanted.contains(t.id)) {
                at = i;
                break;
            }
        }
        SQLiteDatabase w = getWritableDatabase();
        ContentValues fv = new ContentValues();
        fv.put("project_id", projectId);
        fv.put("name", name);
        fv.put("pos", at);
        fv.put("collapsed", 0);
        long fid = w.insert("folders", null, fv);
        if (fid == -1) return -1;
        java.util.Set<Long> oldFolders = new java.util.HashSet<>();
        boolean hadLoose = false;
        int n = 0;
        // Pre-group container state, so a rolled-back group leaves no
        // section pointing at the deleted folder.
        java.util.List<long[]> saved = new java.util.ArrayList<>();
        for (long sid : sectionIds) {
            Section s = getSection(sid);
            if (s == null || s.projectId != projectId) continue;
            if (s.folderId >= 0) oldFolders.add(s.folderId);
            else hadLoose = true;
            saved.add(new long[]{sid, s.folderId, s.pos});
            ContentValues v = new ContentValues();
            v.put("folder_id", fid);
            v.put("pos", n++);
            w.update("sections", v, "_id=?",
                    new String[]{String.valueOf(sid)});
        }
        if (n < 2) {
            // Nothing valid to group — roll the folder back AND put
            // every touched section back exactly where it was. Without
            // the restore, a section keeps folder_id pointing at the
            // deleted folder: invisible in every container.
            for (long[] sv : saved) {
                ContentValues v = new ContentValues();
                if (sv[1] < 0) v.putNull("folder_id");
                else v.put("folder_id", sv[1]);
                v.put("pos", (int) sv[2]);
                w.update("sections", v, "_id=?",
                        new String[]{String.valueOf(sv[0])});
            }
            w.delete("folders", "_id=?",
                    new String[]{String.valueOf(fid)});
            for (long of : oldFolders) renumberSectionsInFolder(of);
            if (hadLoose) renumberSections(projectId);
            renumberTopLevel(projectId);
            return -1;
        }
        for (long of : oldFolders) renumberSectionsInFolder(of);
        if (hadLoose) renumberSections(projectId);
        renumberTopLevel(projectId);
        return fid;
    }

    /**
     * Dissolve a folder: its sections go loose at the folder's old
     * top-level slot, in order; the folder is deleted. Notes are never
     * touched — folders are just containers.
     */
    public void ungroupFolder(long projectId, long folderId) {
        Folder f = getFolder(folderId);
        if (f == null || f.projectId != projectId) return;
        List<Section> inner = sectionsInFolder(folderId);
        List<TopItem> order = topLevelOrder(projectId);
        List<TopItem> next = new ArrayList<>();
        for (TopItem t : order) {
            if (t.isFolder && t.id == folderId) {
                for (Section s : inner) {
                    next.add(new TopItem(false, s.id));
                }
            } else {
                next.add(t);
            }
        }
        SQLiteDatabase w = getWritableDatabase();
        for (Section s : inner) {
            ContentValues v = new ContentValues();
            v.putNull("folder_id");
            w.update("sections", v, "_id=?",
                    new String[]{String.valueOf(s.id)});
        }
        w.delete("folders", "_id=?",
                new String[]{String.valueOf(folderId)});
        reorderTopLevel(projectId, next);
    }

    /** Deleting a folder is ungrouping it: its sections go loose at
     * the folder's position. Folders never delete notes. */
    public void deleteFolder(long projectId, long folderId) {
        ungroupFolder(projectId, folderId);
    }

    /** Delete just the folder row (its sections must be handled
     * separately): the trash-drop path, where the folder and its
     * contents are permanently deleted. */
    public void deleteFolderRow(long folderId) {
        getWritableDatabase().delete("folders", "_id=?",
                new String[]{String.valueOf(folderId)});
    }

    /** A folder's index in the project's top-level order. */
    public int topLevelIndex(long projectId, long folderId) {
        List<TopItem> order = topLevelOrder(projectId);
        for (int i = 0; i < order.size(); i++) {
            TopItem t = order.get(i);
            if (t.isFolder && t.id == folderId) return i;
        }
        return order.size();
    }

    /** Set a folder's top-level position directly. */
    public void setFolderPos(long folderId, int pos) {
        Folder f = getFolder(folderId);
        if (f == null) return;
        List<TopItem> order = topLevelOrder(f.projectId);
        List<TopItem> next = new ArrayList<TopItem>();
        TopItem item = null;
        for (TopItem t : order) {
            if (t.isFolder && t.id == folderId) item = t;
            else next.add(t);
        }
        if (item == null) return;
        if (pos < 0) pos = 0;
        if (pos > next.size()) pos = next.size();
        next.add(pos, item);
        reorderTopLevel(f.projectId, next);
    }

    /**
     * Move a section into a folder, out to the top level, or to a new
     * slot inside its current container. targetFolderId < 0 means the
     * loose top level; slot clamps into range. Both containers are
     * renumbered; the merged top-level numbering is rebuilt.
     */
    public void moveSectionToContainer(long sectionId, long targetFolderId,
                                       int slot) {
        Section s = getSection(sectionId);
        if (s == null) return;
        if (targetFolderId >= 0) {
            Folder tf = getFolder(targetFolderId);
            if (tf == null || tf.projectId != s.projectId) return;
        }
        long oldFolder = s.folderId;
        List<Section> target = targetFolderId >= 0
                ? sectionsInFolder(targetFolderId)
                : looseSections(s.projectId);
        List<Long> ids = new ArrayList<>();
        for (Section x : target) {
            if (x.id != sectionId) ids.add(x.id);
        }
        if (slot < 0) slot = 0;
        if (slot > ids.size()) slot = ids.size();
        ids.add(slot, sectionId);
        if (oldFolder != targetFolderId) {
            SQLiteDatabase w = getWritableDatabase();
            ContentValues v = new ContentValues();
            if (targetFolderId < 0) v.putNull("folder_id");
            else v.put("folder_id", targetFolderId);
            w.update("sections", v, "_id=?",
                    new String[]{String.valueOf(sectionId)});
            if (oldFolder >= 0) renumberSectionsInFolder(oldFolder);
            else renumberSections(s.projectId);
        }
        if (targetFolderId >= 0) {
            reorderSectionsInFolder(targetFolderId, ids);
        } else {
            reorderSections(s.projectId, ids);
        }
        renumberTopLevel(s.projectId);
    }

    // ---------------- aggregates ----------------

    private int intQuery(String sql) {
        int out = 0;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(sql, null);
            if (c.moveToFirst() && !c.isNull(0)) out = c.getInt(0);
        } catch (Exception e) {
            Log.w("NotesDb", "Suppressed exception", e);
        }
        finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Total words across notes created at or after sinceMs, wherever
     * they live now (Bin or filed) — it's the day's output that counts. */
    public int wordsSince(long sinceMs) {
        int sum = 0;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT SUM(words) FROM notes WHERE created >= ?",
                    new String[]{String.valueOf(sinceMs)});
            if (c.moveToFirst() && !c.isNull(0)) sum = c.getInt(0);
        } catch (Exception e) {
            Log.w("NotesDb", "Suppressed exception", e);
        }
        finally {
            if (c != null) c.close();
        }
        return sum;
    }

    private Note fromCursor(Cursor c) {
        Note n = new Note();
        n.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        n.title = c.getString(c.getColumnIndexOrThrow("title"));
        n.transcript = c.getString(c.getColumnIndexOrThrow("transcript"));
        n.tags = c.getString(c.getColumnIndexOrThrow("tags"));
        n.created = c.getLong(c.getColumnIndexOrThrow("created"));
        n.audioPath = c.getString(c.getColumnIndexOrThrow("audio_path"));
        n.words = c.getInt(c.getColumnIndexOrThrow("words"));
        n.pos = c.getInt(c.getColumnIndexOrThrow("pos"));
        n.done = c.getInt(c.getColumnIndexOrThrow("done"));
        int pi = c.getColumnIndex("project_id");
        n.projectId = (pi < 0 || c.isNull(pi)) ? -1 : c.getLong(pi);
        int si = c.getColumnIndex("section_id");
        n.sectionId = (si < 0 || c.isNull(si)) ? -1 : c.getLong(si);
        return n;
    }

    public static int countWords(String t) {
        if (t == null) return 0;
        String s = t.trim();
        return s.isEmpty() ? 0 : s.split("\\s+").length;
    }

    public static String makeTitle(String transcript) {
        String t = transcript == null ? "" : transcript.trim();
        if (t.isEmpty()) return "Untitled piece";
        String[] words = t.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, words.length); i++) {
            if (i > 0) sb.append(" ");
            sb.append(words[i]);
        }
        String title = sb.toString();
        return title.length() > 60 ? title.substring(0, 60) + "\u2026" : title;
    }
}
