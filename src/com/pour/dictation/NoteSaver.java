package com.pour.dictation;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

/** Shared note-saving used by every engine. */
public class NoteSaver {

    /**
     * Save a finished transcript as a note. Shows the confirmation toast.
     * Returns the new note's id, or -1 when nothing was saved.
     */
    public static long save(Context ctx, String transcriptText) {
        String text = ParaBreaks.apply(
                transcriptText == null ? "" : transcriptText.trim());
        if (text.isEmpty()) return -1;
        try {
            NotesDb db = new NotesDb(ctx.getApplicationContext());
            long id = db.insert(
                    NotesDb.makeTitle(text),
                    text,
                    "",
                    System.currentTimeMillis(),
                    null,
                    NotesDb.countWords(text));
            // A new dictation always lands in the Bin: unfiled, newest
            // first, ready to be filed into a project later.
            try { db.close(); } catch (Exception e) {
                Log.w("NoteSaver", "Suppressed exception", e);
            }
            if (id != -1) {
                Toast.makeText(ctx.getApplicationContext(),
                        "Saved to Bucket. "
                                + NotesDb.countWords(text) + " words.",
                        Toast.LENGTH_SHORT).show();
                return id;
            }
        } catch (Exception e) {
            Log.w("NoteSaver", "Suppressed exception", e);
        }
        return -1;
    }

    /**
     * Append a finished dictation session to an existing note (the "Dictate
     * more" flow): the note keeps its title, tags and creation date, and
     * only the transcript and word count are updated. Falls back to a new
     * note when the target vanished mid-session.
     * Returns the note id that was written, or -1 on failure.
     */
    public static long appendToNote(Context ctx, long noteId,
                                    String fullTranscript) {
        String text = ParaBreaks.apply(
                fullTranscript == null ? "" : fullTranscript.trim());
        if (text.isEmpty()) return -1;
        try {
            NotesDb db = new NotesDb(ctx.getApplicationContext());
            Note n = db.get(noteId);
            if (n == null) {
                try { db.close(); } catch (Exception e) {
                    Log.w("NoteSaver", "Suppressed exception", e);
                }
                return save(ctx, text);
            }
            n.transcript = text;
            n.words = NotesDb.countWords(text);
            // New words invalidate the reviewed state.
            n.done = 0;
            db.update(n);
            try { db.close(); } catch (Exception e) {
                Log.w("NoteSaver", "Suppressed exception", e);
            }
            Toast.makeText(ctx.getApplicationContext(),
                    "Added " + n.words + " words total.",
                    Toast.LENGTH_SHORT).show();
            return n.id;
        } catch (Exception e) {
            Log.w("NoteSaver", "Suppressed exception", e);
        }
        return -1;
    }

    /**
     * Append a finished dictation session to a section's body (the
     * "Dictate more" flow for sections): the section keeps its name,
     * and only the body and word count are updated. Falls back to a
     * new note when the section vanished mid-session.
     * Returns the section id on success, -1 when nothing was saved.
     */
    public static long appendToSection(Context ctx, long sectionId,
                                       String fullTranscript) {
        String text = ParaBreaks.apply(
                fullTranscript == null ? "" : fullTranscript.trim());
        if (text.isEmpty()) return -1;
        try {
            NotesDb db = new NotesDb(ctx.getApplicationContext());
            Section s = db.getSection(sectionId);
            if (s == null) {
                try { db.close(); } catch (Exception e) {
                    Log.w("NoteSaver", "Suppressed exception", e);
                }
                save(ctx, text);
                return sectionId;
            }
            db.updateSectionBody(sectionId, text);
            try { db.close(); } catch (Exception e) {
                Log.w("NoteSaver", "Suppressed exception", e);
            }
            Toast.makeText(ctx.getApplicationContext(),
                    "Added " + NotesDb.countWords(text) + " words total.",
                    Toast.LENGTH_SHORT).show();
            return sectionId;
        } catch (Exception e) {
            Log.w("NoteSaver", "Suppressed exception", e);
        }
        return -1;
    }
}
