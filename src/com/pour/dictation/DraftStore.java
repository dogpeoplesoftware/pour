package com.pour.dictation;

import android.content.Context;
import android.util.Log;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Crash safety for long dictation sessions. The in-progress transcript is
 * autosaved (throttled) while dictating and cleared on every successful
 * save, so a killed app, a dead battery, or a rotation only ever costs a
 * few seconds of words — the next launch offers to recover them.
 *
 * Drafts are stored per note (one file per note id), so a session for
 * chapter 7 can never overwrite or clear an unrecovered draft from
 * chapter 5.
 */
public class DraftStore {

    private static final long THROTTLE_MS = 5000;
    /** Drafts older than this are never offered for recovery. */
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;

    private static long lastWriteMs = 0;
    private static String lastText = null;
    private static long lastNoteId = Long.MIN_VALUE;
    /** Latest text awaiting a throttled write (may be newer than lastText). */
    private static String pendingText = null;
    private static long pendingNoteId = Long.MIN_VALUE;
    /** True when a delayed flush is scheduled. */
    private static boolean flushScheduled = false;
    private static final Object FLUSH_LOCK = new Object();
    private static android.os.Handler flushHandler = null;

    private static android.os.Handler getFlushHandler() {
        synchronized (FLUSH_LOCK) {
            if (flushHandler == null) {
                flushHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
            }
            return flushHandler;
        }
    }

    public static class Draft {
        /** -1 when the interrupted session was a fresh note. */
        public long noteId;
        public String text;
        /** Wall-clock ms of the last autosave. */
        public long time;
    }

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "pour-pro/drafts");
    }

    private static File file(Context ctx, long noteId) {
        return new File(dir(ctx), "draft_" + noteId + ".txt");
    }

    /**
     * Persist the in-progress text for this note. Throttled by elapsed
     * time: at most one disk write per THROTTLE_MS, regardless of whether
     * the text changed. When called too soon, the latest text is held in
     * memory and a delayed flush writes it when the throttle expires.
     * Safe to call on every transcript update from any thread. Never
     * touches another note's draft.
     */
    public static synchronized void autosave(Context ctx, long noteId,
                                             String text) {
        if (text == null || text.trim().isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        // Throttle by time alone — live transcription changes the text
        // on every partial result, so text-equality can't gate writes.
        if (now - lastWriteMs < THROTTLE_MS) {
            // Hold the newest text; the scheduled flush will write it.
            pendingText = text;
            pendingNoteId = noteId;
            scheduleFlush(ctx);
            return;
        }
        writeNow(ctx, noteId, text);
    }

    /** Schedule a delayed write of any pending text. */
    private static void scheduleFlush(final Context ctx) {
        synchronized (FLUSH_LOCK) {
            if (flushScheduled) return;
            flushScheduled = true;
        }
        final Context appCtx = ctx.getApplicationContext();
        long delay = THROTTLE_MS - (SystemClock.uptimeMillis() - lastWriteMs);
        if (delay < 0) delay = 0;
        getFlushHandler().postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (FLUSH_LOCK) {
                    flushScheduled = false;
                }
                flushPending(appCtx);
            }
        }, delay);
    }

    /** Write pending text now if there is any. */
    private static synchronized void flushPending(Context ctx) {
        if (pendingText == null) return;
        String text = pendingText;
        long noteId = pendingNoteId;
        pendingText = null;
        pendingNoteId = Long.MIN_VALUE;
        writeNow(ctx, noteId, text);
    }

    /** Unconditional disk write (caller handles throttling). */
    private static void writeNow(Context ctx, long noteId, String text) {
        try {
            File d = dir(ctx);
            if (!d.isDirectory()) d.mkdirs();
            File f = file(ctx, noteId);
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes("UTF-8"));
                out.getFD().sync();
            } finally {
                out.close();
            }
            lastText = text;
            lastWriteMs = SystemClock.uptimeMillis();
            lastNoteId = noteId;
        } catch (Exception e) {
            Log.w("DraftStore", "Suppressed exception", e);
        }
    }

    /**
     * Write any pending text to disk immediately, ignoring the throttle.
     * For lifecycle flushes: the words are on disk *before* anything can
     * go wrong. Also cancels any scheduled flush (it would be redundant).
     */
    public static synchronized void forceFlush(Context ctx) {
        synchronized (FLUSH_LOCK) {
            flushScheduled = false;
        }
        try {
            getFlushHandler().removeCallbacksAndMessages(null);
        } catch (Exception ignored) { }
        if (pendingText != null) {
            flushPending(ctx);
        }
    }

    /**
     * Write the draft to disk right now, ignoring the throttle. For
     * lifecycle flushes (host pausing) and interruption pauses: the words
     * are on disk *before* anything can go wrong.
     */
    public static synchronized void flushNow(Context ctx, long noteId,
                                             String text) {
        if (text == null || text.trim().isEmpty()) return;
        lastText = null;
        lastWriteMs = 0;
        pendingText = null;
        pendingNoteId = Long.MIN_VALUE;
        writeNow(ctx, noteId, text);
    }

    /**
     * The newest recoverable draft across all notes, or null when there
     * is none. Older unrecovered drafts stay on disk and surface once
     * the newer one is resolved. A corrupt newest file falls back to
     * the next-newest instead of giving up.
     */
    public static synchronized Draft load(Context ctx) {
        try {
            File d = dir(ctx);
            if (!d.isDirectory()) return null;
            File[] files = d.listFiles();
            if (files == null || files.length == 0) return null;
            java.util.ArrayList<File> cands = new java.util.ArrayList<>();
            for (File f : files) {
                if (!f.isFile()
                        || !f.getName().startsWith("draft_")) {
                    continue;
                }
                if (System.currentTimeMillis() - f.lastModified()
                        > MAX_AGE_MS) {
                    // Too old to ever offer — delete it here so stale
                    // drafts can't accumulate on disk.
                    try { f.delete(); } catch (Exception e) {
                        Log.w("DraftStore", "Suppressed exception", e);
                    }
                    continue;
                }
                cands.add(f);
            }
            java.util.Collections.sort(cands,
                    new java.util.Comparator<File>() {
                        @Override public int compare(File a, File b) {
                            return Long.compare(b.lastModified(),
                                    a.lastModified());
                        }
                    });
            for (File f : cands) {
                Draft draft = readOne(f);
                if (draft != null) return draft;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Read one draft file; null when it's empty or unreadable. */
    private static Draft readOne(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            } finally {
                in.close();
            }
            String text = new String(bos.toByteArray(), "UTF-8");
            if (text.trim().isEmpty()) return null;
            Draft draft = new Draft();
            draft.text = text;
            draft.time = f.lastModified();
            try {
                String name = f.getName();
                String num = name.substring("draft_".length(),
                        name.length() - ".txt".length());
                draft.noteId = Long.parseLong(num);
            } catch (Exception e) {
                draft.noteId = -1;
            }
            return draft;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Drop this note's draft: called on successful save and on discard. */
    public static synchronized void clear(Context ctx, long noteId) {
        if (noteId == lastNoteId) {
            lastText = null;
            lastWriteMs = 0;
            lastNoteId = Long.MIN_VALUE;
        }
        if (noteId == pendingNoteId) {
            pendingText = null;
            pendingNoteId = Long.MIN_VALUE;
        }
        try {
            File f = file(ctx, noteId);
            if (f.isFile()) f.delete();
        } catch (Exception e) {
            Log.w("DraftStore", "Suppressed exception", e);
        }
    }
}
