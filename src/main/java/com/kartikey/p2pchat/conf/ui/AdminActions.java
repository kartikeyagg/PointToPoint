package com.kartikey.p2pchat.conf.ui;

import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.ui.ConferenceController.ParticipantInfo;

import java.util.Objects;

/** Which admin actions are offered for a row; plain logic so it can be tested without Swing. */
public record AdminActions(boolean admin, boolean host, ParticipantId self) {

    public static AdminActions of(ConferenceController c) {
        return new AdminActions(c.isAdmin(), c.isHost(), c.self());
    }

    public boolean showToolbar() {
        return admin;
    }

    private boolean isSelf(ParticipantInfo t) {
        return self != null && Objects.equals(self, t.id());
    }

    public boolean canAllowSpeak(ParticipantInfo t) {
        return admin;
    }

    public boolean canAllowCamera(ParticipantInfo t) {
        return admin;
    }

    public boolean canGrantAdmin(ParticipantInfo t) {
        return admin && !t.admin() && !t.host();
    }

    public boolean canRevokeAdmin(ParticipantInfo t) {
        return host && t.admin() && !t.host();
    }

    public boolean canTransferHost(ParticipantInfo t) {
        return host && !t.host() && !isSelf(t);
    }

    public boolean canKick(ParticipantInfo t) {
        return admin && !t.host() && !isSelf(t) && (host || !t.admin());
    }

    public boolean any(ParticipantInfo t) {
        return canAllowSpeak(t) || canAllowCamera(t) || canGrantAdmin(t) || canRevokeAdmin(t)
                || canTransferHost(t) || canKick(t);
    }
}
