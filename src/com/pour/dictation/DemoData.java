package com.pour.dictation;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample content for screenshots and demos. Populates the Bucket with
 * realistic dictated pieces and creates two example projects.
 *
 * DEMO_MODE is intentionally false — the parent agent enables it only
 * for demo builds. Never ship with demo data in a real release.
 */
public class DemoData {

    /** Set to true for demo/screenshot builds only. */
    public static final boolean DEMO_MODE = false;

    private static final String PREFS = "pour_prefs";
    private static final String KEY_DEMO_POPULATED = "demo_populated";

    /** Populate demo data once. Safe to call repeatedly — no-ops after first run. */
    public static void populateIfNeeded(Context ctx) {
        if (!DEMO_MODE) return;
        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_DEMO_POPULATED, false)) return;
        populate(ctx);
        prefs.edit().putBoolean(KEY_DEMO_POPULATED, true).apply();
    }

    public static void populate(Context ctx) {
        NotesDb db = new NotesDb(ctx);
        try {
            populateBucket(db);
            populateNovelDraft(db);
            populateWorkNotes(db);
        } finally {
            try { db.close(); } catch (Exception ignored) { }
        }
    }

    private static int words(String s) {
        return NotesDb.countWords(s);
    }

    private static void populateBucket(NotesDb db) {
        long now = System.currentTimeMillis();
        long day = 24L * 60L * 60L * 1000L;

        String[][] pieces = {
            {"Grocery list",
             "Okay so I need eggs milk bread uh peanut butter and those little tangerines Angi likes oh and coffee beans we're almost out and dish soap"},
            {"Story idea",
             "What if there's a lighthouse keeper who discovers the light has been signaling something out in the fog not warning ships away but calling something in and he's been doing it for thirty years without knowing"},
            {"Meeting notes",
             "Action items from this morning sync. Sarah owns the Q4 report deadline is Friday. Mike to follow up with the vendor about the delayed shipment. I need to send the revised mockups by end of day tomorrow"},
            {"Gift ideas for dad",
             "Dad's birthday is coming up. He mentioned wanting a new multitool. Or maybe that book about the Appalachian trail he was talking about at dinner. Could also do the photo print of the lake house"},
            {"Workout plan",
             "Monday chest and triceps. Wednesday back and biceps. Friday legs. Weekend long run Saturday morning early before it gets hot. Remember to stretch the hamstring this time"},
        };

        for (int i = 0; i < pieces.length; i++) {
            String title = pieces[i][0];
            String text = pieces[i][1];
            db.insert(title, text, "", now - (pieces.length - i) * day,
                    null, words(text));
        }
    }

    private static void populateNovelDraft(NotesDb db) {
        long projectId = db.insertProject("Novel Draft");

        long ch1 = db.insertSection(projectId, "Chapter 1");
        db.updateSectionBody(ch1,
                "The rain hadn't stopped in eleven days. Mara stood at the window of the old farmhouse, watching the fields turn to mud, and wondered if the road to town was even passable anymore. The letter had come yesterday by a courier on horseback, which told her everything about how bad things had gotten.");

        long ch2 = db.insertSection(projectId, "Chapter 2");
        db.updateSectionBody(ch2,
                "She packed before dawn. Two shirts, the compass her father had given her, and the letter folded small enough to fit in her boot. The horse was restless. Somewhere beyond the ridge, thunder rolled like a warning she chose to ignore.");

        long notes = db.insertSection(projectId, "Notes");
        db.updateSectionBody(notes,
                "Remember: Mara's brother shows up in chapter four. The storm is a metaphor for the political situation but don't be too on the nose about it. Research 1800s courier routes for accuracy.");

        // Group chapters into a folder.
        List<Long> chapterIds = new ArrayList<Long>();
        chapterIds.add(ch1);
        chapterIds.add(ch2);
        chapterIds.add(notes);
        db.groupSectionsIntoFolder(projectId, chapterIds, "Chapters");
    }

    private static void populateWorkNotes(NotesDb db) {
        long projectId = db.insertProject("Work Notes");

        long s1 = db.insertSection(projectId, "Q4 Planning");
        db.updateSectionBody(s1,
                "Priorities for next quarter. One, finish the migration before the holiday freeze. Two, hire for the backend role, we've been short staffed for two months. Three, revisit the pricing page, conversion dropped after the redesign.");

        long s2 = db.insertSection(projectId, "1:1 with manager");
        db.updateSectionBody(s2,
                "Talking points. Career growth, I want more ownership of the mobile roadmap. Feedback on the last sprint, what went well and what didn't. Ask about the conference budget for next year.");
    }
}
