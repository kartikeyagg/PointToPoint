package com.kartikey.p2pchat.conf.media;

/** A compressed video frame as the codec produced it, before it is cut into packets. */
public record EncodedFrame(byte[] data, boolean keyframe, long timestampMicros, int width, int height) {}
