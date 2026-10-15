package com.pour.dictation;

/** A writing project: an ordered set of user-named sections holding
 * notes. Typeless by design — the writer decides what it is. */
public class Project {
    public long id;
    public String name;
    public long created;
    public int pos;
    /** NULL = live, non-NULL = in trash (millis since epoch). */
    public Long deletedAt;
}
