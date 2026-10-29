package com.kartikey.p2pchat.conf.app;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.PeerNetwork;
import com.kartikey.p2pchat.conf.node.ConferenceNode;
import com.kartikey.p2pchat.conf.node.Invite;
import com.kartikey.p2pchat.conf.node.MediaIO;
import com.kartikey.p2pchat.conf.ui.ConferenceController;

import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Gives a {@link ConferenceNode} the single thread it needs. Network callbacks, the 10 ms tick and
 * every call from the window are funnelled onto that thread, so the node itself needs no locks.
 */
public final class NodeRunner implements ConferenceController, AutoCloseable {

    private static final long TICK_MILLIS = 10, QUERY_TIMEOUT_MILLIS = 2_000;

    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "conference-node");
        t.setDaemon(true);
        return t;
    });
    private final PeerNetwork net;
    private final MediaIO media;
    private final CountDownLatch ended = new CountDownLatch(1);
    private volatile ConferenceNode node;
    private volatile ConferenceView view;
    private volatile String endReason = "";

    private NodeRunner(PeerNetwork net, MediaIO media, Function<PeerNetwork, ConferenceNode> make) {
        this.net = net;
        this.media = media;
        PeerNetwork onThread = new OnThread(net);
        run(() -> {
            node = make.apply(onThread);
            node.setView(new Relay());
        });
        thread.scheduleAtFixedRate(() -> {
            try {
                node.tick(System.currentTimeMillis());
            } catch (RuntimeException e) {
                System.err.println("conference node: " + e); // keep ticking: one bad event must not stop the meeting
            }
        }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    public static NodeRunner host(Identity me, PeerNetwork net, MediaIO media, ConferenceNode.Settings settings,
                                  String title) {
        return new NodeRunner(net, media, n -> ConferenceNode.host(me, n, media, settings, title, System::currentTimeMillis));
    }

    public static NodeRunner join(Identity me, PeerNetwork net, MediaIO media, ConferenceNode.Settings settings,
                                  Invite invite) {
        return new NodeRunner(net, media, n -> ConferenceNode.join(me, n, media, settings, invite, System::currentTimeMillis));
    }

    /** Blocks until the meeting ends for us (left, removed, ended, or the join failed). */
    public String awaitEnd() throws InterruptedException {
        ended.await();
        return endReason;
    }

    // ---------------------------------------------------------------- thread plumbing

    private void run(Runnable r) {
        try {
            thread.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    System.err.println("conference node: " + e);
                }
            });
        } catch (RejectedExecutionException closed) {
            // after close(): ignore
        }
    }

    private <T> T ask(Callable<T> query, T fallback) {
        try {
            return thread.submit(query).get(QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Posts every network callback to the node thread. */
    private final class OnThread implements PeerNetwork {
        private final PeerNetwork d;

        OnThread(PeerNetwork d) {
            this.d = d;
        }

        @Override public ParticipantId self() { return d.self(); }
        @Override public List<InetSocketAddress> candidates() { return d.candidates(); }
        @Override public void connect(ParticipantId p, List<InetSocketAddress> c, ParticipantId via) { d.connect(p, c, via); }
        @Override public void disconnect(ParticipantId p) { d.disconnect(p); }
        @Override public Set<ParticipantId> connected() { return d.connected(); }
        @Override public boolean sendMedia(ParticipantId p, byte[] packet) { return d.sendMedia(p, packet); }
        @Override public boolean sendControl(ParticipantId p, byte[] message) { return d.sendControl(p, message); }
        @Override public LinkStats stats(ParticipantId p) { return d.stats(p); }
        @Override public void close() { d.close(); }

        @Override
        public void setListener(Listener l) {
            d.setListener(new Listener() {
                @Override public void onConnected(ParticipantId p) { run(() -> l.onConnected(p)); }
                @Override public void onDisconnected(ParticipantId p) { run(() -> l.onDisconnected(p)); }
                @Override public void onMedia(ParticipantId from, byte[] packet) { run(() -> l.onMedia(from, packet)); }
                @Override public void onControl(ParticipantId from, byte[] message) { run(() -> l.onControl(from, message)); }
            });
        }
    }

    /** Forwards node callbacks to the window and notices the end of the meeting. */
    private final class Relay implements ConferenceView {
        @Override public void onVideo(ParticipantId who, BufferedImage frame) { ConferenceView v = view; if (v != null) v.onVideo(who, frame); }
        @Override public void onParticipantsChanged() { ConferenceView v = view; if (v != null) v.onParticipantsChanged(); }
        @Override public void onPolicyChanged() { ConferenceView v = view; if (v != null) v.onPolicyChanged(); }
        @Override public void onChat(ParticipantId from, String name, String text, long at) { ConferenceView v = view; if (v != null) v.onChat(from, name, text, at); }
        @Override public void onActiveSpeakers(List<ParticipantId> s) { ConferenceView v = view; if (v != null) v.onActiveSpeakers(s); }
        @Override public void onNotice(String message) { ConferenceView v = view; if (v != null) v.onNotice(message); }

        @Override
        public void onEnded(String reason) {
            endReason = reason;
            ConferenceView v = view;
            if (v != null) {
                v.onEnded(reason);
            }
            ended.countDown();
        }
    }

    // ---------------------------------------------------------------- ConferenceController

    @Override public ParticipantId self() { return net.self(); }
    @Override public String meetingTitle() { return ask(() -> node.meetingTitle(), ""); }
    @Override public String inviteCode() { return ask(() -> node.inviteCode(), ""); }
    @Override public List<ParticipantInfo> participants() { return ask(() -> node.participants(), List.of()); }
    @Override public boolean isAdmin() { return ask(() -> node.isAdmin(), false); }
    @Override public boolean isHost() { return ask(() -> node.isHost(), false); }
    @Override public boolean canSpeak() { return ask(() -> node.canSpeak(), false); }
    @Override public long openMicUntil() { return ask(() -> node.openMicUntil(), 0L); }
    @Override public Health health() { return ask(() -> node.health(), new Health(0, 0, 0, 0, 0, 0, 0, 0, false)); }
    @Override public void setMicOn(boolean on) { run(() -> node.setMicOn(on)); }
    @Override public void setCameraOn(boolean on) { run(() -> node.setCameraOn(on)); }
    @Override public void setScreenShare(boolean on) { run(() -> node.setScreenShare(on)); }
    @Override public void setGallery(List<ParticipantId> tiles) { run(() -> node.setGallery(tiles)); }
    @Override public void sendChat(String text) { run(() -> node.sendChat(text)); }
    @Override public void allowSpeak(ParticipantId p, boolean allow) { run(() -> node.allowSpeak(p, allow)); }
    @Override public void openMicForAll(int seconds) { run(() -> node.openMicForAll(seconds)); }
    @Override public void closeMicForAll() { run(() -> node.closeMicForAll()); }
    @Override public void allowCamera(ParticipantId p, boolean allow) { run(() -> node.allowCamera(p, allow)); }
    @Override public void grantAdmin(ParticipantId p) { run(() -> node.grantAdmin(p)); }
    @Override public void revokeAdmin(ParticipantId p) { run(() -> node.revokeAdmin(p)); }
    @Override public void transferHost(ParticipantId p) { run(() -> node.transferHost(p)); }
    @Override public void kick(ParticipantId p) { run(() -> node.kick(p)); }
    @Override public void endMeeting() { run(() -> node.endMeeting()); }
    @Override public void leave() { run(() -> node.leave()); }
    @Override public void setView(ConferenceView v) { this.view = v; }

    @Override
    public void close() {
        if (node != null) {
            ask(() -> {
                node.leave();
                return true;
            }, false);
        }
        thread.shutdownNow();
        try {
            thread.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        net.close();
        media.close();
    }
}
