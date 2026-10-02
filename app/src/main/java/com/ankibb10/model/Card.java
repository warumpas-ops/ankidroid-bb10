package com.ankibb10.model;

public class Card {
    public static final int TYPE_NEW     = 0;
    public static final int TYPE_LEARN   = 1;
    public static final int TYPE_REVIEW  = 2;
    public static final int TYPE_RELEARN = 3;
    public static final int QUEUE_NEW    = 0;
    public static final int QUEUE_LEARN  = 1;
    public static final int QUEUE_REVIEW = 2;
    public static final int QUEUE_SUSP   = -1;

    public long   id;
    public long   nid;
    public long   did;
    public int    ord;
    public long   mod;
    public int    usn;
    public int    type;
    public int    queue;
    public int    due;
    public int    ivl;
    public int    factor;
    public int    reps;
    public int    lapses;
    public int    left;
    public int    odue;
    public long   odid;
    public int    flags;
    public int    ease;

    public String frontHtml;
    public String backHtml;
    public long   startedAt;
}
