package com.ankibb10.model;

import java.util.ArrayList;
import java.util.List;

public class DeckNode {
    public Deck deck;
    public String fullName;
    public String displayName;
    public int level;
    public boolean hasChildren;
    public boolean isCollapsed = false;
    public List<DeckNode> children = new ArrayList<DeckNode>();

    public int sumNew;
    public int sumLearn;
    public int sumReview;

    public DeckNode(Deck deck, String fullName, String displayName, int level) {
        this.deck = deck;
        this.fullName = fullName;
        this.displayName = displayName;
        this.level = level;
    }
}