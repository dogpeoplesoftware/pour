package com.pour.dictation;

/** A titled block of writing inside a project ("Chapter One", "Cold
 * open", "Sources" — the writer's own words for their structure).
 * Sections are the writable leaf: tapping one opens the editor, and
 * the body holds the dictated or typed text. */
public class Section {
    public long id;
    public long projectId;
    public String name;
    /** The section's own text. */
    public String body;
    /** Cached word count of the body. */
    public int words;
    public int pos;
    /** Legacy: sections no longer collapse in the UI (they are leaf
     * text blocks now — folders are the collapsible level). The DB
     * column and backup round-trip stay so old data is preserved. */
    public int collapsed;
    /** Containing folder id, or -1 when the section sits loose at the
     * top level. Folders are opt-in: -1 is the default. */
    public long folderId = -1;
}
