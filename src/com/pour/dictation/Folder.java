package com.pour.dictation;

/** A named container grouping sections inside a project. Folders are
 * strictly opt-in: a project stays flat until the writer groups
 * sections into one. The generic term is "folder" — no imposed
 * chapter/part/scene vocabulary. */
public class Folder {
    public long id;
    public long projectId;
    public String name;
    /** Position in the merged top-level order (folders + loose
     * sections), rewritten by renumberTopLevel. */
    public int pos;
    /** 1 when the writer collapsed this folder; persisted in the DB. */
    public int collapsed;
}
