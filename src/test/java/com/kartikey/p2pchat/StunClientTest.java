package com.kartikey.p2pchat;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StunClientTest {

    /** Builds a binding success response with an XOR-MAPPED-ADDRESS, as a real server would. */
    static byte[] response(byte[] txId, InetSocketAddress mapped) {
        byte[] addr = mapped.getAddress().getAddress();
        byte[] mask = ByteBuffer.allocate(16).putInt(StunClient.MAGIC_COOKIE).put(txId).array();
        byte[] x = addr.clone();
        for (int i = 0; i < x.length; i++) {
            x[i] ^= mask[i];
        }
        int valueLen = 4 + x.length;
        ByteBuffer b = ByteBuffer.allocate(20 + 4 + valueLen);
        b.putShort((short) 0x0101).putShort((short) (4 + valueLen)).putInt(StunClient.MAGIC_COOKIE).put(txId);
        b.putShort((short) 0x0020).putShort((short) valueLen);
        b.put((byte) 0).put((byte) (x.length == 4 ? 1 : 2));
        b.putShort((short) (mapped.getPort() ^ (StunClient.MAGIC_COOKIE >>> 16))).put(x);
        return b.array();
    }

    @Test
    void parsesXorMappedIpv4AndIpv6() throws Exception {
        byte[] tx = StunClient.newTransactionId();
        InetSocketAddress v4 = new InetSocketAddress(InetAddress.getByName("106.219.163.60"), 27520);
        byte[] r4 = response(tx, v4);
        assertEquals(v4, StunClient.parseResponse(r4, r4.length, tx));

        InetSocketAddress v6 = new InetSocketAddress(InetAddress.getByName("2401:4900:8845:546f::1"), 7283);
        byte[] r6 = response(tx, v6);
        assertEquals(v6, StunClient.parseResponse(r6, r6.length, tx));
    }

    @Test
    void rejectsWrongTransactionAndNonStun() throws Exception {
        byte[] tx = StunClient.newTransactionId();
        byte[] r = response(tx, new InetSocketAddress(InetAddress.getByName("1.2.3.4"), 5));
        assertNull(StunClient.parseResponse(r, r.length, StunClient.newTransactionId()));

        byte[] chat = new PacketCodec(null).encode(Packet.control(Packet.Type.HELLO, 1));
        assertFalse(StunClient.isStunMessage(chat, chat.length));
        assertTrue(StunClient.isStunMessage(r, r.length));
    }

    @Test
    void classifiesNatBehaviour() throws Exception {
        InetAddress pub = InetAddress.getByName("13.71.3.98");
        InetSocketAddress a = new InetSocketAddress(pub, 48720);
        assertEquals(StunClient.NatType.STABLE,
                StunClient.classify(List.of(a, a), new ArrayList<>(), 7283).type());
        assertEquals(StunClient.NatType.CHANGES_PER_DESTINATION,
                StunClient.classify(List.of(a, new InetSocketAddress(pub, 1234)), new ArrayList<>(), 7283).type());
        assertEquals(StunClient.NatType.UNKNOWN,
                StunClient.classify(List.of(), new ArrayList<>(), 7283).type());
    }

    @Test
    void formatsAddressesTheWayUsersTypeThem() throws Exception {
        assertEquals("1.2.3.4:5", StunClient.format(new InetSocketAddress(InetAddress.getByName("1.2.3.4"), 5)));
        assertEquals("[2001:db8:0:0:0:0:0:1]:7283",
                StunClient.format(new InetSocketAddress(InetAddress.getByName("2001:db8::1"), 7283)));
    }

    @Test
    void probeAgainstFakeServerReportsMappedAddress() throws Exception {
        InetSocketAddress fakeMapped = new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 40000);
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket client = new DatagramSocket(0)) {
            Thread t = new Thread(() -> {
                try {
                    byte[] buf = new byte[512];
                    for (int i = 0; i < 2; i++) {
                        DatagramPacket dp = new DatagramPacket(buf, buf.length);
                        server.receive(dp);
                        byte[] tx = Arrays.copyOfRange(buf, 8, 20);
                        byte[] resp = response(tx, fakeMapped);
                        server.send(new DatagramPacket(resp, resp.length, dp.getSocketAddress()));
                    }
                } catch (Exception ignored) {
                    // test ends
                }
            });
            t.start();
            String addr = "127.0.0.1:" + server.getLocalPort();
            StunClient.Report r = StunClient.probe(client, List.of(addr, addr), false, 1000);
            assertEquals(StunClient.NatType.STABLE, r.type());
            assertEquals(fakeMapped, r.publicAddress());
            t.join(2000);
        }
    }
}
