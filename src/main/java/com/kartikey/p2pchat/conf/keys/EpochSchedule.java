package com.kartikey.p2pchat.conf.keys;

import com.kartikey.p2pchat.conf.Crypto;

/** When the admin should rotate the group key: periodically, and at once after removing a member. */
public final class EpochSchedule {

    public static final long ROTATE_MILLIS = 60_000;

    private EpochSchedule() {}

    public static boolean due(long lastRotationMillis, long now, boolean memberKicked) {
        return memberKicked || now - lastRotationMillis >= ROTATE_MILLIS;
    }

    public static byte[] newKey() {
        return Crypto.random(Crypto.KEY_LEN);
    }
}
