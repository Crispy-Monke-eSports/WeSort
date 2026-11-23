package com.porter.wesort.data;

import java.util.Arrays;

/** Location-free incident codes shared by the agent and Manager. */
public enum AgentIncident {
    DEATH("Player died"),
    INVENTORY_UNVERIFIED("Awaiting server inventory"),
    NAVIGATION_PAUSED("Navigation paused"),
    WAITING_ON_6B6T_LOBBY("Waiting for the 6b6t lobby portal"),
    WRONG_SERVER("Minecraft server does not match Manager"),
    AUTH_FAILED("Manager authentication failed"),
    NO_CAPACITY("No free inventory slot");

    private final String title;

    AgentIncident(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public static boolean accepts(String code) {
        return Arrays.stream(values()).anyMatch(incident -> incident.name().equals(code));
    }
}
