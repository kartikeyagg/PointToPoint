package com.kartikey.p2pchat.conf.ui;

import com.kartikey.p2pchat.conf.ParticipantId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The chosen gallery tiles, never more than {@link ConferenceController#MAX_TILES}. */
public final class TileSelection {
    private final Set<ParticipantId> chosen = new LinkedHashSet<>();

    public TileSelection() {}

    public TileSelection(List<ParticipantId> initial) {
        for (ParticipantId p : initial) {
            add(p);
        }
    }

    /** Adds when there is room; returns whether it is now selected. */
    public boolean add(ParticipantId p) {
        if (chosen.contains(p)) {
            return true;
        }
        if (chosen.size() >= ConferenceController.MAX_TILES) {
            return false;
        }
        return chosen.add(p);
    }

    /** Flips membership; returns false when selecting was refused because the cap is reached. */
    public boolean toggle(ParticipantId p) {
        if (chosen.remove(p)) {
            return true;
        }
        return add(p);
    }

    public boolean contains(ParticipantId p) {
        return chosen.contains(p);
    }

    public int size() {
        return chosen.size();
    }

    public List<ParticipantId> list() {
        return new ArrayList<>(chosen);
    }
}
