package com.kartikey.p2pchat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerLinkTest {

    private final List<AutoCloseable> toClose = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    /** Records every callback so tests can wait on them. */
    static final class Recorder implements PeerLink.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<Long> delivered = new LinkedBlockingQueue<>();
        final BlockingQueue<PeerLink.Status> statuses = new LinkedBlockingQueue<>();
        final List<String> warnings = new CopyOnWriteArrayList<>();
        final BlockingQueue<byte[]> data = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> reliable = new LinkedBlockingQueue<>();
        final AtomicInteger peerSessions = new AtomicInteger();

        @Override
        public void onData(byte[] d) {
            data.add(d);
        }

        @Override
        public void onReliable(byte[] d) {
            reliable.add(d);
        }

        @Override
        public void onPeerSession() {
            peerSessions.incrementAndGet();
        }

        @Override
        public void onStatus(PeerLink.Status status, String detail) {
            statuses.add(status);
        }

        @Override
        public void onMessage(String text) {
            messages.add(text);
        }

        @Override
        public void onDelivered(long id) {
            delivered.add(id);
        }

        @Override
        public void onWarning(String warning) {
            warnings.add(warning);
        }

        boolean awaitStatus(PeerLink.Status wanted, long ms) throws InterruptedException {
            long deadline = System.currentTimeMillis() + ms;
            for (long left = ms; left > 0; left = deadline - System.currentTimeMillis()) {
                PeerLink.Status s = statuses.poll(left, TimeUnit.MILLISECONDS);
                if (s == wanted) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Host firewalls (e.g. ufw) sometimes refuse a loopback send with EPERM; try again. */
    private static void sendWithRetry(DatagramSocket socket, DatagramPacket packet) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                socket.send(packet);
                return;
            } catch (java.net.SocketException e) {
                if (attempt == 20) {
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    private static int freePort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private PeerLink link(int localPort, int peerPort, String key, Recorder r) throws Exception {
        PeerLink l = new PeerLink(localPort, new InetSocketAddress("127.0.0.1", peerPort), new PacketCodec(key), r);
        toClose.add(l);
        return l;
    }

    @Test
    void peersConnectAndExchangeMessagesBothWays() throws Exception {
        int pa = freePort(), pb = freePort();
        Recorder ra = new Recorder(), rb = new Recorder();
        PeerLink a = link(pa, pb, "pw", ra).start();
        PeerLink b = link(pb, pa, "pw", rb).start();

        assertTrue(ra.awaitStatus(PeerLink.Status.CONNECTED, 5000));
        assertTrue(rb.awaitStatus(PeerLink.Status.CONNECTED, 5000));

        long id = a.send("hi from A ✓");
        assertEquals("hi from A ✓", rb.messages.poll(5, TimeUnit.SECONDS));
        assertEquals(id, ra.delivered.poll(5, TimeUnit.SECONDS));

        b.send("hi from B");
        assertEquals("hi from B", ra.messages.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void messageSentBeforePeerStartsIsDeliveredByRetransmission() throws Exception {
        int pa = freePort(), pb = freePort();
        Recorder ra = new Recorder(), rb = new Recorder();
        PeerLink a = link(pa, pb, null, ra).start();
        long id = a.send("early bird");

        Thread.sleep(1500); // peer comes online later
        link(pb, pa, null, rb).start();

        assertEquals("early bird", rb.messages.poll(5, TimeUnit.SECONDS));
        assertEquals(id, ra.delivered.poll(5, TimeUnit.SECONDS));
        assertNull(rb.messages.poll(1500, TimeUnit.MILLISECONDS), "retransmits must not duplicate");
    }

    @Test
    void mismatchedPassphraseNeverConnects() throws Exception {
        int pa = freePort(), pb = freePort();
        Recorder ra = new Recorder(), rb = new Recorder();
        link(pa, pb, "one", ra).start();
        link(pb, pa, "two", rb).start();

        assertFalse(ra.awaitStatus(PeerLink.Status.CONNECTED, 2000));
        assertTrue(ra.warnings.stream().anyMatch(w -> w.contains("unreadable")));
    }

    @Test
    void duplicateDatagramsAreDeliveredOnceButAlwaysAcked() throws Exception {
        try (DatagramSocket raw = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            raw.setSoTimeout(3000);
            Recorder r = new Recorder();
            PeerLink l = link(0, raw.getLocalPort(), null, r).start();
            PacketCodec codec = new PacketCodec(null);
            byte[] msg = codec.encode(new Packet(Packet.Type.MSG, 99, 1, "once".getBytes(StandardCharsets.UTF_8)));
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", l.localPort());

            int acks = 0;
            for (int i = 0; i < 3; i++) {
                sendWithRetry(raw, new DatagramPacket(msg, msg.length, target));
            }
            byte[] buf = new byte[2048];
            long deadline = System.currentTimeMillis() + 3000;
            while (acks < 3 && System.currentTimeMillis() < deadline) {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                raw.receive(dp);
                Packet p = codec.decode(buf, dp.getLength());
                if (p.type() == Packet.Type.ACK && p.seq() == 1) {
                    acks++;
                }
            }
            assertEquals(3, acks);
            assertEquals("once", r.messages.poll(1, TimeUnit.SECONDS));
            assertNull(r.messages.poll(500, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void peersConnectOverIpv6Loopback() throws Exception {
        int pa = freePort(), pb = freePort();
        Recorder ra = new Recorder(), rb = new Recorder();
        PacketCodec codec = new PacketCodec("pw");
        PeerLink a = new PeerLink(pa, new InetSocketAddress("::1", pb), codec, ra);
        PeerLink b = new PeerLink(pb, new InetSocketAddress("::1", pa), new PacketCodec("pw"), rb);
        toClose.add(a);
        toClose.add(b);
        a.start();
        b.start();
        a.send("over v6");
        assertEquals("over v6", rb.messages.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void ipv6PeersMatchWithinSameSlash64Only() throws Exception {
        InetAddress dialled = InetAddress.getByName("2001:db8:8845:546f:8140:9d6e:c31a:5f80");
        InetAddress temporary = InetAddress.getByName("2001:db8:8845:546f:2b1d:b2f9:1cdd:cdc8");
        InetAddress other = InetAddress.getByName("2001:db8:8845:5470::1");
        assertTrue(PeerLink.samePeer(temporary, dialled));
        assertFalse(PeerLink.samePeer(other, dialled));
        assertFalse(PeerLink.samePeer(InetAddress.getByName("1.2.3.5"), InetAddress.getByName("1.2.3.4")));
    }

    @Test
    void byeMarksPeerDisconnected() throws Exception {
        int pa = freePort(), pb = freePort();
        Recorder ra = new Recorder(), rb = new Recorder();
        link(pa, pb, null, ra).start();
        PeerLink b = link(pb, pa, null, rb).start();
        assertTrue(ra.awaitStatus(PeerLink.Status.CONNECTED, 5000));

        b.close();
        assertTrue(ra.awaitStatus(PeerLink.Status.DISCONNECTED, 3000));
    }

    @Test
    void reliableBytesArriveOnceAndInOrderDespiteLoss() throws Exception {
        int pa = freePort(), pb = freePort();
        Recorder ra = new Recorder(), rb = new Recorder();
        PeerLink a = link(pa, pb, "pw", ra).start();
        link(pb, pa, "pw", rb).start();
        assertTrue(ra.awaitStatus(PeerLink.Status.CONNECTED, 5000));

        // lose the first copy of every third RDATA
        Set<Long> dropped = ConcurrentHashMap.newKeySet();
        a.dropOutgoing = p -> p.type() == Packet.Type.RDATA && p.seq() % 3 == 0 && dropped.add(p.seq());
        int n = 200;
        for (int i = 0; i < n; i++) {
            assertTrue(a.sendReliable(new byte[]{(byte) i, (byte) (i >> 8)}));
        }
        for (int i = 0; i < n; i++) {
            byte[] got = rb.reliable.poll(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{(byte) i, (byte) (i >> 8)}, got, "item " + i);
        }
        assertNull(rb.reliable.poll(700, TimeUnit.MILLISECONDS), "retransmits must not duplicate");
        assertFalse(dropped.isEmpty());
    }

    @Test
    void datagramsReachThePeerOnceConnected() throws Exception {
        int pa = freePort(), pb = freePort();
        Recorder ra = new Recorder(), rb = new Recorder();
        PeerLink a = link(pa, pb, "pw", ra).start();
        assertFalse(a.sendData(new byte[]{1}), "nothing is sent before the link is up");
        link(pb, pa, "pw", rb).start();
        assertTrue(ra.awaitStatus(PeerLink.Status.CONNECTED, 5000));

        byte[] big = new byte[PacketCodec.MAX_PAYLOAD];
        big[big.length - 1] = 42;
        assertTrue(a.sendData(big));
        assertArrayEquals(big, rb.data.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void encryptedLinkRecoversWhenThePeerRestarts() throws Exception {
        int pa = freePort(), pb = freePort();
        Recorder ra = new Recorder(), rb = new Recorder();
        PeerLink a = link(pa, pb, "pw", ra).start();
        PeerLink b = link(pb, pa, "pw", rb).start();
        assertTrue(ra.awaitStatus(PeerLink.Status.CONNECTED, 5000));
        a.send("before");
        assertEquals("before", rb.messages.poll(5, TimeUnit.SECONDS));

        b.close(); // new process: new session id and new X25519 key
        Recorder rb2 = new Recorder();
        PeerLink b2 = link(pb, pa, "pw", rb2).start();
        assertTrue(rb2.awaitStatus(PeerLink.Status.CONNECTED, 5000));
        b2.send("after restart");
        assertEquals("after restart", ra.messages.poll(5, TimeUnit.SECONDS));
        a.send("back to you");
        assertEquals("back to you", rb2.messages.poll(5, TimeUnit.SECONDS));
        assertTrue(ra.peerSessions.get() >= 2);
    }
}
