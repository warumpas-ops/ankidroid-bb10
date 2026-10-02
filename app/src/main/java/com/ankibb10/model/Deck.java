package com.ankibb10.model;

public class Deck {
    public long id;
    public String name;
    public int newCount;
    public int learnCount;
    public int reviewCount;

    public Deck(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public int totalDue() {
        return learnCount + reviewCount;
    }
}
