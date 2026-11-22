package com.porter.wesort.stash;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 6b6t lobby mode arms from the connected server address: any 6b6t subdomain (join, play, bare),
 * case-insensitive, with or without :port. Never matches lookalikes, so a fake server cannot
 * trigger lobby behavior.
 */
class StashServerTest {
    @Test
    void matches6b6tServers() {
        assertTrue(Stash.is6b6tServer("6b6t.org"));
        assertTrue(Stash.is6b6tServer("join.6b6t.org"));
        assertTrue(Stash.is6b6tServer("play.6b6t.org"));
        assertTrue(Stash.is6b6tServer("JOIN.6B6T.ORG"));
        assertTrue(Stash.is6b6tServer("play.6b6t.org:25565"));
        assertTrue(Stash.is6b6tServer("  join.6b6t.org  "));
    }

    @Test
    void rejectsLookalikes() {
        assertFalse(Stash.is6b6tServer(null));
        assertFalse(Stash.is6b6tServer(""));
        assertFalse(Stash.is6b6tServer("6b6t.com"));
        assertFalse(Stash.is6b6tServer("6b6t.org.evil.com"));
        assertFalse(Stash.is6b6tServer("not6b6t.org"));
        assertFalse(Stash.is6b6tServer("2b2t.org"));
        assertFalse(Stash.is6b6tServer("singleplayer"));
        assertFalse(Stash.is6b6tServer("unknown"));
    }
}
