package com.kartikey.p2pchat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal STUN (RFC 5389) binding client: asks a public STUN server which ip:port our
 * UDP socket appears as from the internet. This is a one-off lookup, like "what is my ip";
 * chat traffic never goes through the STUN server.
 */
public final class StunClient {

    static final int MAGIC_COOKIE = 0x2112A442;
    private static final short BINDING_REQUEST = 0x0001;
    private static final short BINDING_SUCCESS = 0x0101;
    private static final short ATTR_MAPPED_ADDRESS = 0x0001;
    private static final short ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    private static final short ATTR_XOR_MAPPED_ADDRESS_OLD = (short) 0x8020;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Two servers listening on <em>different ports</em>: if our public port differs between
     * them, the NAT picks a new port per destination and our address cannot be shared.
     */
    public static final List<String> DEFAULT_SERVERS = List.of("stun.l.google.com:19302", "stun.cloudflare.com:3478");

    private StunClient() {}

    /** NAT behaviour learned from the probes. */
    public enum NatType {
        /** Public address equals the local socket address: no NAT (public IPv4 or IPv6). */
        NONE,
        /** Same public ip:port towards every server: share it with your peer. */
        STABLE,
        /** Different public port per destination: hole punching only works with a reachable peer. */
        CHANGES_PER_DESTINATION,
        /** No server answered (UDP blocked or offline). */
        UNKNOWN
    }

    /** Result of probing one address family. */
    public record Report(NatType type, InetSocketAddress publicAddress, List<String> details) {

        public String summary() {
            return switch (type) {
                case NONE -> "Your address is " + format(publicAddress)
                        + " (no NAT). Give it to your peer; connect at the same time so firewalls let replies in.";
                case STABLE -> "Your public address is " + format(publicAddress)
                        + ". Give it to your peer (ip AND port).";
                case CHANGES_PER_DESTINATION -> "Your NAT picks a new public port for every destination ("
                        + String.join(", ", details) + "), so it cannot be shared in advance. "
                        + "This only connects if your peer is directly reachable (IPv6, public IP or port forwarding).";
                case UNKNOWN -> "Could not reach any STUN server (" + String.join(", ", details) + ").";
            };
        }
    }

    /** Formats an address the way users type it: 1.2.3.4:5 or [2001:db8::1]:5. */
    public static String format(InetSocketAddress a) {
        InetAddress ip = a.getAddress();
        String host = ip == null ? a.getHostString() : ip.getHostAddress();
        int zone = host.indexOf('%');
        if (zone >= 0) {
            host = host.substring(0, zone);
        }
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + a.getPort();
    }

    /** Builds a binding request carrying the given 12-byte transaction id. */
    public static byte[] bindingRequest(byte[] txId) {
        return ByteBuffer.allocate(20).putShort(BINDING_REQUEST).putShort((short) 0)
                .putInt(MAGIC_COOKIE).put(txId).array();
    }

    public static byte[] newTransactionId() {
        byte[] id = new byte[12];
        RANDOM.nextBytes(id);
        return id;
    }

    /** True if the datagram looks like a STUN message (so it is not a chat packet). */
    public static boolean isStunMessage(byte[] data, int length) {
        return length >= 20 && (data[0] & 0xC0) == 0
                && ByteBuffer.wrap(data, 4, 4).getInt() == MAGIC_COOKIE;
    }

    /** Extracts the mapped address from a binding success response, or null. */
    public static InetSocketAddress parseResponse(byte[] data, int length, byte[] expectedTxId) {
        if (!isStunMessage(data, length)) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        if (buf.getShort() != BINDING_SUCCESS) {
            return null;
        }
        int bodyLen = buf.getShort() & 0xFFFF;
        buf.getInt(); // cookie
        byte[] txId = new byte[12];
        buf.get(txId);
        if (expectedTxId != null && !Arrays.equals(txId, expectedTxId)) {
            return null;
        }
        InetSocketAddress plainMapped = null;
        int end = Math.min(20 + bodyLen, length);
        while (buf.position() + 4 <= end) {
            short type = buf.getShort();
            int len = buf.getShort() & 0xFFFF;
            if (buf.position() + len > end) {
                break;
            }
            byte[] value = new byte[len];
            buf.get(value);
            buf.position(Math.min(buf.position() + ((4 - len % 4) % 4), end));
            if (type == ATTR_XOR_MAPPED_ADDRESS || type == ATTR_XOR_MAPPED_ADDRESS_OLD) {
                InetSocketAddress a = decodeAddress(value, true, txId);
                if (a != null) {
                    return a;
                }
            } else if (type == ATTR_MAPPED_ADDRESS) {
                plainMapped = decodeAddress(value, false, txId);
            }
        }
        return plainMapped;
    }

    private static InetSocketAddress decodeAddress(byte[] v, boolean xor, byte[] txId) {
        if (v.length < 8) {
            return null;
        }
        int family = v[1];
        int port = ((v[2] & 0xFF) << 8) | (v[3] & 0xFF);
        int addrLen = family == 0x01 ? 4 : family == 0x02 ? 16 : -1;
        if (addrLen < 0 || v.length < 4 + addrLen) {
            return null;
        }
        byte[] addr = Arrays.copyOfRange(v, 4, 4 + addrLen);
        if (xor) {
            port ^= MAGIC_COOKIE >>> 16;
            byte[] mask = ByteBuffer.allocate(16).putInt(MAGIC_COOKIE).put(txId).array();
            for (int i = 0; i < addrLen; i++) {
                addr[i] ^= mask[i];
            }
        }
        try {
            return new InetSocketAddress(InetAddress.getByAddress(addr), port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Probes {@code servers} from {@code socket} for one address family. The socket must not
     * be read by anyone else meanwhile.
     */
    public static Report probe(DatagramSocket socket, List<String> servers, boolean ipv6, int timeoutMs)
            throws IOException {
        int oldTimeout = socket.getSoTimeout();
        List<InetSocketAddress> seen = new ArrayList<>();
        List<String> details = new ArrayList<>();
        try {
            socket.setSoTimeout(timeoutMs);
            for (String server : servers) {
                InetSocketAddress target = resolve(server, ipv6);
                if (target == null) {
                    details.add(server + ": no " + (ipv6 ? "IPv6" : "IPv4") + " address");
                    continue;
                }
                InetSocketAddress mapped = queryOnce(socket, target);
                if (mapped == null) {
                    details.add(server + ": no answer");
                } else {
                    seen.add(mapped);
                    details.add(server + " sees " + format(mapped));
                }
            }
        } finally {
            socket.setSoTimeout(oldTimeout);
        }
        return classify(seen, details, socket.getLocalPort());
    }

    /** Convenience: binds {@code localPort} briefly and probes IPv4 and IPv6. */
    public static List<Report> probeBoth(int localPort, List<String> servers, int timeoutMs) throws IOException {
        try (DatagramSocket socket = new DatagramSocket(localPort)) {
            return List.of(probe(socket, servers, false, timeoutMs), probe(socket, servers, true, timeoutMs));
        }
    }

    static Report classify(List<InetSocketAddress> seen, List<String> details, int localPort) {
        if (seen.isEmpty()) {
            return new Report(NatType.UNKNOWN, null, details);
        }
        InetSocketAddress first = seen.get(0);
        boolean allSame = seen.stream().allMatch(first::equals);
        if (!allSame) {
            List<String> ports = seen.stream().map(a -> String.valueOf(a.getPort())).distinct().toList();
            return new Report(NatType.CHANGES_PER_DESTINATION, first, List.of("ports " + String.join("/", ports)));
        }
        boolean local = first.getPort() == localPort && isLocalAddress(first.getAddress());
        return new Report(local ? NatType.NONE : NatType.STABLE, first, details);
    }

    private static InetSocketAddress queryOnce(DatagramSocket socket, InetSocketAddress target) throws IOException {
        byte[] txId = newTransactionId();
        byte[] req = bindingRequest(txId);
        byte[] buf = new byte[1024];
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                socket.send(new DatagramPacket(req, req.length, target));
            } catch (IOException e) {
                return null; // e.g. no IPv6 route
            }
            long deadline = System.currentTimeMillis() + socket.getSoTimeout();
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(dp);
                } catch (SocketTimeoutException e) {
                    break;
                }
                InetSocketAddress mapped = parseResponse(buf, dp.getLength(), txId);
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        return null;
    }

    static InetSocketAddress resolve(String hostPort, boolean ipv6) {
        int colon = hostPort.lastIndexOf(':');
        String host = hostPort.substring(0, colon);
        int port = Integer.parseInt(hostPort.substring(colon + 1));
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (ipv6 ? a instanceof Inet6Address : a instanceof Inet4Address) {
                    return new InetSocketAddress(a, port);
                }
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    private static boolean isLocalAddress(InetAddress a) {
        try {
            return java.net.NetworkInterface.getByInetAddress(a) != null;
        } catch (IOException e) {
            return false;
        }
    }
}
