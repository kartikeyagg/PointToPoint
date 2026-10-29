package com.kartikey.p2pchat.conf.net;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpPeerNetworkTest {

    /** A TEST-NET-1 address nothing answers on. */
    private static final InetSocketAddress NOWHERE = new InetSocketAddress("192.0.2.1", 9);

    private final List<AutoCloseable> toClose = new ArrayList<>();

    static final class Recorder implements PeerNetwork.Listener {
        final List<ParticipantId> connects = new CopyOnWriteArrayList<>();
        final List<ParticipantId> disconnects = new CopyOnWriteArrayList<>();
        final BlockingQueue<byte[]> media = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> control = new LinkedBlockingQueue<>();
        final AtomicInteger mediaCount = new AtomicInteger();
        volatile boolean countOnly;

        @Override
        public void onConnected(ParticipantId peer) {
            connects.add(peer);
        }

        @Override
        public void onDisconnected(ParticipantId peer) {
            disconnects.add(peer);
        }

        @Override
        public void onMedia(ParticipantId from, byte[] packet) {
            mediaCount.incrementAndGet();
            if (!countOnly) {
                media.add(packet);
            }
        }

        @Override
        public void onControl(ParticipantId from, byte[] message) {
            control.add(message);
        }
    }

    record Node(UdpPeerNetwork net, Recorder rec, Identity identity) {
        ParticipantId id() {
            return identity.id();
        }

        List<InetSocketAddress> loop() {
            return List.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), net.port()));
        }
    }

    private Node node(Predicate<ParticipantId> accept, java.util.function.Consumer<UdpPeerNetwork> config) throws Exception {
        Identity id = Identity.generate();
        UdpPeerNetwork net = new UdpPeerNetwork(id, 0, accept, List.of());
        if (config != null) {
            config.accept(net);
        }
        Recorder rec = new Recorder();
        net.setListener(rec);
        net.start();
        toClose.add(net);
        return new Node(net, rec, id);
    }

    private Node node() throws Exception {
        return node(p -> true, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    private static void await(String what, long millis, BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    private static void connectPair(Node a, Node b) throws InterruptedException {
        a.net.connect(b.id(), b.loop(), null);
        await("connection", 5_000, () -> a.net.connected().contains(b.id()) && b.net.connected().contains(a.id())
                && a.rec.connects.contains(b.id()) && b.rec.connects.contains(a.id()));
    }

    @Test
    @Timeout(20)
    void connectsAndExchangesMediaAndControl() throws Exception {
        Node a = node(), b = node();
        assertFalse(a.net.candidates().isEmpty());
        connectPair(a, b);

        assertTrue(a.net.sendMedia(b.id(), "frame".getBytes()));
        assertArrayEquals("frame".getBytes(), b.rec.media.poll(2, TimeUnit.SECONDS));
        assertTrue(b.net.sendControl(a.id(), "hello control".getBytes()));
        assertArrayEquals("hello control".getBytes(), a.rec.control.poll(2, TimeUnit.SECONDS));
        assertTrue(a.net.sendControl(b.id(), new byte[0]));
        assertArrayEquals(new byte[0], b.rec.control.poll(2, TimeUnit.SECONDS));

        PeerNetwork.LinkStats s = a.net.stats(b.id());
        assertNotNull(s);
        assertFalse(s.relayed());
        assertTrue(s.bytesSent() > 0 && s.bytesReceived() > 0);
        assertEquals(a.net.sessionId(b.id()), b.net.sessionId(a.id()));
        assertFalse(a.net.sendMedia(b.id(), new byte[UdpPeerNetwork.MAX_MEDIA + 1]));
        assertFalse(a.net.sendControl(Identity.generate().id(), new byte[1]));

        // a clean disconnect is seen on both sides at once
        a.net.disconnect(b.id());
        await("remote disconnect", 2_000, () -> b.rec.disconnects.contains(a.id()));
        await("local disconnect", 2_000, () -> a.rec.disconnects.contains(b.id()));
        assertTrue(a.net.connected().isEmpty());
        assertNull(a.net.stats(b.id()));
    }

    @Test
    @Timeout(30)
    void simultaneousOpenConvergesToOneSession() throws Exception {
        for (int round = 0; round < 4; round++) {
            Node a = node(), b = node();
            Thread ta = new Thread(() -> a.net.connect(b.id(), b.loop(), null));
            Thread tb = new Thread(() -> b.net.connect(a.id(), a.loop(), null));
            ta.start();
            tb.start();
            ta.join();
            tb.join();
            await("both connected", 5_000, () -> a.net.connected().contains(b.id()) && b.net.connected().contains(a.id()));
            Thread.sleep(1_200);
            assertEquals(1, a.rec.connects.size(), "connects at a");
            assertEquals(1, b.rec.connects.size(), "connects at b");
            assertTrue(a.rec.disconnects.isEmpty() && b.rec.disconnects.isEmpty());
            assertEquals(a.net.sessionId(b.id()), b.net.sessionId(a.id()));
            assertTrue(a.net.sendControl(b.id(), new byte[] {7}));
            assertArrayEquals(new byte[] {7}, b.rec.control.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(60)
    void controlIsExactAndInOrderUnderTwentyPercentLoss() throws Exception {
        Node a = node(), b = node();
        connectPair(a, b);
        a.net.setDropRate(0.2);
        b.net.setDropRate(0.2);
        Random rnd = new Random(42);
        List<byte[]> sent = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            byte[] m = new byte[4 + rnd.nextInt(600)];
            rnd.nextBytes(m);
            ByteBuffer.wrap(m).putInt(i);
            sent.add(m);
        }
        byte[] big = new byte[200 * 1024];
        rnd.nextBytes(big);
        sent.add(big);
        long start = System.nanoTime();
        Thread sender = new Thread(() -> {
            for (byte[] m : sent) {
                while (!a.net.sendControl(b.id(), m)) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        sender.start();
        for (int i = 0; i < sent.size(); i++) {
            byte[] got = b.rec.control.poll(40, TimeUnit.SECONDS);
            assertNotNull(got, "message " + i + " not delivered");
            assertArrayEquals(sent.get(i), got, "message " + i);
        }
        sender.join();
        double secs = (System.nanoTime() - start) / 1e9;
        System.out.printf("[net] 1000 msgs + 200 KB under 20%% loss each way: %.2f s, loss estimate %.2f%n", secs,
                a.net.stats(b.id()).lossRate());
        assertNull(b.rec.control.poll(200, TimeUnit.MILLISECONDS), "duplicate delivery");
        assertTrue(a.rec.disconnects.isEmpty() && b.rec.disconnects.isEmpty());
    }

    @Test
    @Timeout(20)
    void acceptPeerFalseRejects() throws Exception {
        Node a = node(), b = node(p -> false, null);
        a.net.connect(b.id(), b.loop(), null);
        Thread.sleep(1_500);
        assertTrue(a.net.connected().isEmpty());
        assertTrue(b.net.connected().isEmpty());
        assertTrue(a.rec.connects.isEmpty() && b.rec.connects.isEmpty());
        assertEquals(0, b.net.halfOpenCount());
        // and connecting to ourselves is a no-op
        a.net.connect(a.id(), a.loop(), null);
        Thread.sleep(300);
        assertTrue(a.net.connected().isEmpty());
    }

    @Test
    @Timeout(20)
    void impostorReplayingAHelloCannotComplete() throws Exception {
        Node a = node(), b = node();
        Identity mallory = Identity.generate();
        try (DatagramSocket m = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            m.setSoTimeout(3_000);
            InetSocketAddress mAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), m.getLocalPort());
            // a tries to reach b but its HELLO lands at mallory's socket
            a.net.connect(b.id(), List.of(mAddr), null);
            byte[] buf = new byte[2048];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            m.receive(pkt);
            byte[] hello = Arrays.copyOf(buf, pkt.getLength());
            assertEquals(Handshake.HELLO, hello[0]);
            a.net.close(); // the real a is gone; mallory only has its recorded HELLO

            InetSocketAddress bAddr = b.loop().get(0);
            m.send(new DatagramPacket(hello, hello.length, bAddr));
            pkt.setLength(buf.length);
            m.receive(pkt);
            Handshake.Ack ack = Handshake.parseAck(buf, pkt.getLength());
            Handshake.Hello h = Handshake.parseHello(hello, hello.length);
            byte[] th = Handshake.transcriptHash(h.eph(), h.id().encoded(), h.nonce(), ack.ephR(), ack.idR(),
                    ack.nonceR());
            // forged confirms: garbage, and one properly signed by mallory's own key
            byte[][] forged = {
                    Handshake.encodeConfirm(h.nonce(), ack.nonceR(), new byte[64], new byte[16]),
                    Handshake.encodeConfirm(h.nonce(), ack.nonceR(), mallory.sign(Handshake.initiatorSigInput(th)),
                            new byte[16]),
            };
            for (int i = 0; i < 5; i++) {
                for (byte[] f : forged) {
                    m.send(new DatagramPacket(f, f.length, bAddr));
                }
                m.send(new DatagramPacket(hello, hello.length, bAddr));
            }
            Thread.sleep(700);
            assertTrue(b.net.connected().isEmpty());
            assertTrue(b.rec.connects.isEmpty());
        }
    }

    @Test
    @Timeout(30)
    void relaysThroughAMemberWhenNoDirectPath() throws Exception {
        java.util.function.Consumer<UdpPeerNetwork> fast = n -> n.relayAfterMillis = 600;
        Node a = node(p -> true, fast), b = node(p -> true, fast), c = node(p -> true, fast);
        connectPair(a, b);
        connectPair(c, b);
        a.net.connect(c.id(), List.of(NOWHERE), b.id());
        c.net.connect(a.id(), List.of(NOWHERE), b.id());
        await("relayed connection", 8_000, () -> a.net.connected().contains(c.id()) && c.net.connected().contains(a.id()));
        assertTrue(a.net.stats(c.id()).relayed());
        assertTrue(c.net.stats(a.id()).relayed());
        assertEquals(a.net.sessionId(c.id()), c.net.sessionId(a.id()));

        assertTrue(a.net.sendMedia(c.id(), "via b".getBytes()));
        assertArrayEquals("via b".getBytes(), c.rec.media.poll(2, TimeUnit.SECONDS));
        byte[] big = new byte[50_000];
        new Random(1).nextBytes(big);
        assertTrue(c.net.sendControl(a.id(), big));
        byte[] got = a.rec.control.poll(5, TimeUnit.SECONDS);
        assertArrayEquals(big, got);
        assertEquals(1, a.rec.connects.stream().filter(c.id()::equals).count());
    }

    @Test
    @Timeout(20)
    void deadPeerTimesOut() throws Exception {
        Node a = node(), b = node(p -> true, n -> {
            n.timeoutMillis = 3_000;
            n.pingIntervalMillis = 500;
        });
        connectPair(a, b);
        long killed = System.nanoTime();
        a.net.setDropRate(1.0); // a goes silent without a CLOSE, like a crashed machine
        await("timeout", 6_000, () -> b.rec.disconnects.contains(a.id()));
        long ms = (System.nanoTime() - killed) / 1_000_000;
        System.out.println("[net] dead peer detected after " + ms + " ms (timeout 3000)");
        assertTrue(ms >= 2_500, "too early: " + ms);
        assertTrue(b.net.connected().isEmpty());
    }

    @Test
    @Timeout(40)
    void survivesRandomAndMutatedDatagrams() throws Exception {
        Node a = node(), b = node();
        connectPair(a, b);
        long sid = b.net.sessionId(a.id());
        Random rnd = new Random(7);
        Identity[] ids = {Identity.generate(), Identity.generate(), Identity.generate()};
        byte[][] templates = {
                Handshake.encodeHello(Handshake.newEphemeral().getPublic().getEncoded(), ids[0].id(), new byte[16]),
                Handshake.encodeAck(new byte[16], Handshake.newEphemeral().getPublic().getEncoded(), ids[1].id(),
                        new byte[16], new byte[64], new byte[16]),
                Handshake.encodeConfirm(new byte[16], new byte[16], new byte[64], new byte[16]),
                ByteBuffer.allocate(80).put(Handshake.DATA).putLong(sid).putLong(5).array(),
        };
        InetSocketAddress bAddr = b.loop().get(0);
        try (DatagramSocket fuzz = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            for (int i = 0; i < 20_000; i++) {
                byte[] d;
                switch (i % 4) {
                    case 0 -> {
                        d = new byte[rnd.nextInt(1500)];
                        rnd.nextBytes(d);
                        if (d.length > 0 && rnd.nextBoolean()) {
                            d[0] = (byte) (0xD1 + rnd.nextInt(4));
                        }
                    }
                    case 1 -> {
                        byte[] t = templates[rnd.nextInt(templates.length)];
                        d = Arrays.copyOf(t, rnd.nextInt(10) == 0 ? rnd.nextInt(t.length + 1) : t.length);
                        for (int k = 0; k < 1 + rnd.nextInt(4) && d.length > 1; k++) {
                            d[1 + rnd.nextInt(d.length - 1)] ^= (byte) (1 + rnd.nextInt(255));
                        }
                    }
                    case 2 -> {
                        d = new byte[17 + rnd.nextInt(200)];
                        rnd.nextBytes(d);
                        ByteBuffer.wrap(d).put(Handshake.DATA).putLong(sid);
                    }
                    default -> {
                        d = Handshake.encodeHello(Handshake.newEphemeral().getPublic().getEncoded(),
                                ids[rnd.nextInt(ids.length)].id(), randomBytes(rnd, 16));
                    }
                }
                fuzz.send(new DatagramPacket(d, d.length, bAddr));
                if (i % 500 == 0) {
                    Thread.sleep(2);
                }
            }
        }
        Thread.sleep(300);
        assertTrue(b.net.halfOpenCount() <= 256);
        assertTrue(b.net.malformedCount() > 0);
        assertEquals(sid, b.net.sessionId(a.id()));
        assertTrue(a.net.sendControl(b.id(), "still alive".getBytes()));
        assertArrayEquals("still alive".getBytes(), b.rec.control.poll(3, TimeUnit.SECONDS));
        assertTrue(b.net.sendControl(a.id(), "yes".getBytes()));
        assertArrayEquals("yes".getBytes(), a.rec.control.poll(3, TimeUnit.SECONDS));
        assertTrue(a.rec.disconnects.isEmpty() && b.rec.disconnects.isEmpty());
    }

    @Test
    @Timeout(30)
    void throughputAndLatency() throws Exception {
        Node a = node(), b = node();
        connectPair(a, b);
        b.rec.countOnly = true;
        byte[] pkt = new byte[1000];
        int total = 40_000;
        long start = System.nanoTime();
        for (int i = 0; i < total; i++) {
            a.net.sendMedia(b.id(), pkt);
            if ((i & 255) == 255) {
                Thread.onSpinWait();
            }
        }
        long sendNs = System.nanoTime() - start;
        int last = -1;
        while (b.rec.mediaCount.get() != last) {
            last = b.rec.mediaCount.get();
            Thread.sleep(100);
        }
        int got = b.rec.mediaCount.get();
        double rate = got / (sendNs / 1e9);
        System.out.printf("[net] media: sent %d x 1000 B in %.0f ms, received %d (%.0f pkt/s, %.1f MB/s)%n", total,
                sendNs / 1e6, got, rate, rate * 1000 / 1e6);
        assertTrue(rate >= 5_000, "media rate " + rate);

        // control round trip: b echoes, a measures
        b.net.setListener(new PeerNetwork.Listener() {
            public void onConnected(ParticipantId p) {}

            public void onDisconnected(ParticipantId p) {}

            public void onMedia(ParticipantId f, byte[] d) {}

            public void onControl(ParticipantId f, byte[] m) {
                b.net.sendControl(f, m);
            }
        });
        int rounds = 200;
        long t0 = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            assertTrue(a.net.sendControl(b.id(), new byte[] {(byte) i}));
            assertNotNull(a.rec.control.poll(2, TimeUnit.SECONDS));
        }
        double rttUs = (System.nanoTime() - t0) / 1e3 / rounds;
        System.out.printf("[net] control round trip on loopback: %.0f us avg, stats rtt %d ms%n", rttUs,
                a.net.stats(b.id()).rttMillis());
    }

    @Test
    @Timeout(10)
    void closeStopsAllThreads() throws Exception {
        Node a = node(), b = node();
        connectPair(a, b);
        a.net.close();
        b.net.close();
        a.net.close(); // idempotent
        Thread.sleep(50);
        long alive = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("p2pconf-net-") && t.isAlive()).count();
        toClose.clear();
        // other tests' nodes are closed in their own teardown, so none of ours should remain
        assertEquals(0, alive);
        assertFalse(a.net.sendMedia(b.id(), new byte[1]));
    }

    private static byte[] randomBytes(Random rnd, int n) {
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }
}
