package com.kartikey.p2pchat.conf.audio;

/** NORMAL forwards only the loudest few voices; CROWD mixes everyone up the relay tree. */
public enum AudioMode {
    NORMAL, CROWD;

    /** Crowd mode lasts while now is strictly before the admin-set deadline (so 0 = never opened). */
    public static AudioMode of(long openMicUntilMillis, long now) {
        return now < openMicUntilMillis ? CROWD : NORMAL;
    }
}
