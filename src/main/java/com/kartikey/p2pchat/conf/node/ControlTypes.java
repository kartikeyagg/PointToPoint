package com.kartikey.p2pchat.conf.node;

/**
 * First byte of every control message ({@code PeerNetwork.sendControl}). Each package owns a
 * range, so modules can add messages without touching each other's codecs; the node dispatches on
 * the range.
 */
public final class ControlTypes {

    /** {@code conf.node.gossip}: membership views, gossip, policy/key sync. */
    public static final int GOSSIP_FIRST = 0x01, GOSSIP_LAST = 0x1F;
    /** {@code conf.node.tree}: tree placement, repair, subscriptions, crowd mode. */
    public static final int TREE_FIRST = 0x20, TREE_LAST = 0x3F;
    /** {@code conf.node}: join handshake, chat, keyframe requests. */
    public static final int NODE_FIRST = 0x40, NODE_LAST = 0x5F;

    private ControlTypes() {}

    public static boolean inRange(byte[] message, int first, int last) {
        if (message.length == 0) {
            return false;
        }
        int t = message[0] & 0xff;
        return t >= first && t <= last;
    }
}
