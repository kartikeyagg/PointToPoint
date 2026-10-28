package com.kartikey.p2pchat.conf.overlay;

/** One re-parenting performed by repair: {@code id} moved from {@code oldParent} to {@code newParent} in {@code stripe}. */
public record Reattach(long id, int stripe, long oldParent, long newParent) {}
