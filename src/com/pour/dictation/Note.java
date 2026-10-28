package com.pour.dictation;

/** A single saved dictation note. */
public class Note {
    public long id;
    public String title;
    public String transcript;
    public String tags;
    public long created;
    /** Last body edit (millis); 0 = never edited since creation. */
    public long modified;
    public String audioPath;
    public int words;
    /** Position inside its section (0 = first). Unused for Bin notes. */
    public int pos;
    /** 1 when the writer marked this note done. */
    public int done;
    /** Project this note is filed in; -1 means it lives in the Bin. */
    public long projectId = -1;
    /** Section inside the project; -1 means unfiled (Bin). */
    public long sectionId = -1;
}
