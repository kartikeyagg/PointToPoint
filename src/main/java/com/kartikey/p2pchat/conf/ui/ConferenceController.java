package com.kartikey.p2pchat.conf.ui;

import com.kartikey.p2pchat.conf.ParticipantId;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * What the conference window needs from a running meeting. The node implements it; the UI only
 * talks through it, so the window can be built and tested against a fake.
 *
 * <p>Actions return immediately (the work happens on the node's thread). Queries return snapshots.
 */
public interface ConferenceController {

    ParticipantId self();

    String meetingTitle();

    /** Text to hand to people so they can join (only useful while an admin is online). */
    String inviteCode();

    /** Everyone currently known, admins first. Can be large: the UI must filter, not render all. */
    List<ParticipantInfo> participants();

    boolean isAdmin();

    boolean isHost();

    /** Whether our own mic may be heard right now (allowed, or open-mic window running). */
    boolean canSpeak();

    /** End of the open-mic window in epoch millis, or 0 when it is closed. */
    long openMicUntil();

    Health health();

    // --- own media

    void setMicOn(boolean on);

    void setCameraOn(boolean on);

    void setScreenShare(boolean on);

    /** Whose video to show in the gallery, at most {@link #MAX_TILES}; others aren't downloaded. */
    void setGallery(List<ParticipantId> tiles);

    void sendChat(String text);

    // --- admin actions (ignored unless we are an admin; host-only ones unless we are host)

    void allowSpeak(ParticipantId p, boolean allow);

    void openMicForAll(int seconds);

    void closeMicForAll();

    void allowCamera(ParticipantId p, boolean allow);

    void grantAdmin(ParticipantId p);

    void revokeAdmin(ParticipantId p);

    void transferHost(ParticipantId p);

    void kick(ParticipantId p);

    void endMeeting();

    void leave();

    void setView(ConferenceView view);

    int MAX_TILES = 10;

    /** One row of the participant list. {@code name} is self-chosen and only for display. */
    record ParticipantInfo(ParticipantId id, String name, boolean admin, boolean host, boolean canSpeak,
                           boolean cameraOn, boolean speaking) {}

    /** Health counters shown in the status bar. */
    record Health(int peers, int rttMillis, double loss, int treeDepth, long uploadKbps, long downloadKbps,
                  long tampered, int audioDelayMillis, boolean adminOnline) {}

    /**
     * Callbacks from the meeting to the window. They arrive on the node's thread; the UI must move
     * to the Swing thread itself.
     */
    interface ConferenceView {
        void onVideo(ParticipantId who, BufferedImage frame);

        void onParticipantsChanged();

        void onPolicyChanged();

        void onChat(ParticipantId from, String name, String text, long atMillis);

        void onActiveSpeakers(List<ParticipantId> speakers);

        void onNotice(String message);

        void onEnded(String reason);
    }
}
