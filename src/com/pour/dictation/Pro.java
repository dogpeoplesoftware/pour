package com.pour.dictation;

/**
 * Pro gate. Open-source build: everything is unlocked.
 * isPro() always returns true. formatIsPro() marks rich export
 * formats; plain text stays free.
 */
public final class Pro {

    private Pro() { }

    /** The master gate. Open-source build: everything is unlocked. */
    public static boolean isPro(android.content.Context ctx) {
        return true;
    }

    /** Rich export formats are pro; plain text stays free. */
    public static boolean formatIsPro(ProjectExporter.Format f) {
        return f != ProjectExporter.Format.TXT;
    }
}
