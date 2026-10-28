package com.kartikey.p2pchat.conf.stream;

import com.kartikey.p2pchat.conf.Crypto;
import com.kartikey.p2pchat.conf.ParticipantId;

/** Derives the 64-bit stream id carried in every packet header. */
public final class StreamIds {
    private StreamIds() {}

    /**
     * 56 bits of sha256(sender identity) followed by an 8-bit track number. Deriving it from the
     * identity means a stream id is bound to its sender: nobody can claim another member's stream
     * without failing the signature check against that member's key.
     */
    public static long of(ParticipantId sender, int track) {
        if (track < 0 || track > 255) throw new IllegalArgumentException("track out of range: " + track);
        byte[] h = Crypto.sha256(sender.encoded());
        long v = 0;
        for (int i = 0; i < 7; i++) v = (v << 8) | (h[i] & 0xFF);
        return (v << 8) | track;
    }
}
