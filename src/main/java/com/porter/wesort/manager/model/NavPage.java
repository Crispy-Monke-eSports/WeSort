package com.porter.wesort.manager.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The website's page buttons, in the website's order, and which of them an account sees. Some pages have an in-game
 * version; the others show a note that points to the website. Pure.
 */
public enum NavPage {
    STASH("Stash", true),
    ORDERS("Orders", true),
    BOTS("Bots", true),
    ACTIVITY("Activity", true),
    SETTINGS("Settings", true),
    /** Not on the website's bar (it lives inside Settings there); the owner's user list has its own page here. */
    USERS("Users", true);

    private final String title;
    private final boolean inGame;

    NavPage(String title, boolean inGame) {
        this.title = title;
        this.inGame = inGame;
    }

    public String title() {
        return title;
    }

    /** True when this window has the page itself; false when it can only point to the website. */
    public boolean inGame() {
        return inGame;
    }

    public static List<NavPage> visible(AuthState auth, boolean botHasActivity, boolean botPicked) {
        List<NavPage> out = new ArrayList<>();
        if (auth == null || !auth.loggedIn()) return out;
        boolean forced = auth.forcedStreamer();
        out.add(STASH);
        out.add(ORDERS);
        if (auth.can("activity")) out.add(BOTS);
        if (auth.can("activity") && botHasActivity) out.add(ACTIVITY);
        out.add(SETTINGS);
        if (auth.owner()) out.add(USERS);
        return out;
    }

    /** The note a page without an in-game version shows; {@code address} is the server address, or null to hide it. */
    public String websiteNote(String address) {
        return title + " is only on the website" + (address == null ? "." : ". Open " + address + " in a browser.");
    }
}
