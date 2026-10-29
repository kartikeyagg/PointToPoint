package com.kartikey.p2pchat.conf.net;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reliable, ordered message stream over an unreliable datagram link (one per peer and session).
 * Pure logic on a millisecond clock with no I/O or threads; the owner holds the lock.
 *
 * <p>Messages are cut into segments {@code seq(4) | flags(1) | data(<=1100)} (FIRST/LAST mark
 * message boundaries) and reassembled in order on the other side, up to {@link #MAX_MESSAGE}.
 * Acks are {@code cumulative next-expected(4) | SACK bitmap(8)} for the 64 segments after it, sent
 * after two segments or {@link #ACK_DELAY_MILLIS}, and at once on reordering or duplicates.
 * Retransmission timeout per RFC 6298 (SRTT/RTTVAR, Karn's rule, exponential backoff); fast
 * retransmit when three later segments are SACKed; a simple AIMD congestion window. Unacked data is
 * capped by bytes and by {@link #WINDOW} segments; the receiver buffers at most {@link #WINDOW}
 * segments ahead, so memory stays bounded whatever the peer sends.
 */
final class ReliableStream {

    static final int SEGMENT_DATA = 1100;
    static final int HEADER = 5;
    static final int MAX_MESSAGE = 256 * 1024;
    static final int WINDOW = 1024;
    static final int ACK_LEN = 12;
    static final long ACK_DELAY_MILLIS = 10;
    static final byte FIRST = 1;
    static final byte LAST = 2;

    private static final long MIN_RTO = 50;
    private static final long MAX_RTO = 5_000;
    private static final long MAX_BACKOFF_RTO = 10_000;
    private static final double MIN_CWND = 8;
    private static final double MAX_CWND = WINDOW;

    /** Thrown when the peer violates the framing; the owner should drop the link. */
    static final class ProtocolException extends Exception {
        ProtocolException(String m) {
            super(m);
        }
    }

    private static final class Seg {
        final long seq;
        final byte flags;
        final byte[] data;
        long sentAt = -1;
        int tx;
        boolean sacked;
        boolean fastRetx;

        Seg(long seq, byte flags, byte[] data) {
            this.seq = seq;
            this.flags = flags;
            this.data = data;
        }
    }

    private final long maxUnackedBytes;

    // ---- sender
    private final ArrayDeque<Seg> unacked = new ArrayDeque<>();
    private long sndUna;
    private long sndNext;
    private long sndMax;
    private long unackedBytes;
    private double cwnd = 16;
    private double ssthresh = MAX_CWND;
    private double srtt = -1;
    private double rttvar;
    private long rto;
    private int backoff = 1;
    private long lastProgressAt;
    private int probes;
    private long recoverUntil = -1;
    private long transmissions;
    private long retransmissions;

    // ---- receiver
    private long rcvNext;
    private final Map<Long, Seg> ooo = new HashMap<>();
    private long oooBytes;
    private byte[] assembly;
    private int assemblyLen = -1;
    private int ackPending;
    private boolean ackNow;
    private long ackDueAt = -1;

    ReliableStream(long maxUnackedBytes, long initialRtoMillis) {
        this.maxUnackedBytes = maxUnackedBytes;
        this.rto = clamp(initialRtoMillis, MIN_RTO, 3_000);
    }

    /** Seeds SRTT from a handshake round trip. */
    void seedRtt(long rttMillis) {
        if (srtt < 0 && rttMillis > 0) {
            sampleRtt(rttMillis);
        }
    }

    // ================================================================ sender

    /** Queues a message; false if it is too large or would exceed the unacked limits. */
    boolean offer(byte[] msg) {
        if (msg.length > MAX_MESSAGE) {
            return false;
        }
        int n = Math.max(1, (msg.length + SEGMENT_DATA - 1) / SEGMENT_DATA);
        if (unacked.size() + n > WINDOW || unackedBytes + msg.length > maxUnackedBytes) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            int from = i * SEGMENT_DATA, to = Math.min(msg.length, from + SEGMENT_DATA);
            byte flags = (byte) ((i == 0 ? FIRST : 0) | (i == n - 1 ? LAST : 0));
            unacked.addLast(new Seg(sndNext++, flags, Arrays.copyOfRange(msg, from, to)));
        }
        unackedBytes += msg.length;
        return true;
    }

    /** Emits due (re)transmissions as segment payloads. */
    void poll(long now, Consumer<byte[]> out) {
        probe(now, out);
        int inflight = 0;
        boolean timedOut = false;
        for (Seg s : unacked) {
            if (s.sacked) {
                continue;
            }
            if (s.sentAt < 0) {
                if (inflight >= (int) cwnd) {
                    break;
                }
                transmit(s, now, out);
                inflight++;
                continue;
            }
            inflight++;
            if (s.fastRetx) {
                s.fastRetx = false;
                transmit(s, now, out);
            } else if (now - s.sentAt >= currentRto()) {
                if (!timedOut) {
                    timedOut = true;
                    ssthresh = Math.max(cwnd / 2, MIN_CWND);
                    cwnd = MIN_CWND;
                    backoff = Math.min(backoff * 2, 64);
                }
                transmit(s, now, out);
            }
        }
    }

    /**
     * Tail-loss probe: when nothing has been acknowledged for about two round trips, resend the
     * oldest outstanding segment early (at most twice per stall) without collapsing the window, so
     * a lost last segment does not cost a full backed-off RTO. After that the RTO takes over.
     */
    private void probe(long now, Consumer<byte[]> out) {
        if (probes >= 2 || srtt < 0) {
            return;
        }
        for (Seg s : unacked) {
            if (s.sacked) {
                continue;
            }
            if (s.sentAt < 0) {
                return;
            }
            long pto = (long) (2 * srtt) + ACK_DELAY_MILLIS + 5;
            if (now - Math.max(lastProgressAt, s.sentAt) >= pto && now - s.sentAt < currentRto()) {
                probes++;
                transmit(s, now, out);
            }
            return;
        }
    }

    private void transmit(Seg s, long now, Consumer<byte[]> out) {
        if (s.tx > 0) {
            retransmissions++;
        }
        transmissions++;
        s.tx++;
        s.sentAt = now;
        sndMax = Math.max(sndMax, s.seq + 1);
        byte[] p = new byte[HEADER + s.data.length];
        ByteBuffer.wrap(p).putInt((int) s.seq).put(s.flags).put(s.data);
        out.accept(p);
    }

    /** Processes an ACK payload; malformed or impossible acks are ignored. */
    void onAck(byte[] b, int off, int len, long now) {
        if (len != ACK_LEN) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(b, off, len);
        long cum = expand(buf.getInt(), sndUna);
        long sack = buf.getLong();
        if (cum < sndUna || cum > sndMax) {
            return;
        }
        long sample = -1;
        boolean advanced = false;
        while (!unacked.isEmpty() && unacked.peekFirst().seq < cum) {
            Seg s = unacked.pollFirst();
            if (s.tx == 1 && !s.sacked) {
                sample = now - s.sentAt;
            }
            unackedBytes -= s.data.length;
            grow();
            advanced = true;
        }
        if (unacked.isEmpty()) {
            unackedBytes = 0;
        }
        sndUna = cum;
        if (advanced) {
            backoff = 1;
            lastProgressAt = now;
            probes = 0;
        }
        int sackedAbove = 0;
        Seg[] arr = unacked.toArray(new Seg[0]);
        for (Seg s : arr) {
            long i = s.seq - cum - 1;
            if (i >= 0 && i < 64 && (sack & (1L << i)) != 0 && !s.sacked && s.sentAt >= 0) {
                s.sacked = true;
                lastProgressAt = now;
                probes = 0;
                if (s.tx == 1) {
                    sample = now - s.sentAt;
                }
            }
        }
        if (sample >= 0) {
            sampleRtt(sample);
        }
        // fast retransmit: a segment with >= 3 SACKed segments after it is presumed lost
        long minAge = srtt < 0 ? MIN_RTO : (long) Math.max(srtt, 5);
        for (int k = arr.length - 1; k >= 0; k--) {
            Seg s = arr[k];
            if (s.sacked) {
                sackedAbove++;
            } else if (sackedAbove >= 3 && s.sentAt >= 0 && !s.fastRetx && now - s.sentAt >= minAge) {
                s.fastRetx = true;
                if (s.seq >= recoverUntil) {
                    recoverUntil = sndMax;
                    cwnd = Math.max(cwnd / 2, MIN_CWND);
                    ssthresh = cwnd;
                }
            }
        }
    }

    private void grow() {
        cwnd = Math.min(MAX_CWND, cwnd < ssthresh ? cwnd + 1 : cwnd + 1 / cwnd);
    }

    private void sampleRtt(long r) {
        double m = Math.max(1, r);
        if (srtt < 0) {
            srtt = m;
            rttvar = m / 2;
        } else {
            rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - m);
            srtt = 0.875 * srtt + 0.125 * m;
        }
        rto = clamp((long) (srtt + Math.max(10, 4 * rttvar) + ACK_DELAY_MILLIS), MIN_RTO, MAX_RTO);
    }

    long currentRto() {
        return Math.min(rto * backoff, MAX_BACKOFF_RTO);
    }

    /** Smoothed RTT in ms, or -1 before the first sample. */
    double srtt() {
        return srtt;
    }

    boolean hasUnacked() {
        return !unacked.isEmpty();
    }

    long transmissions() {
        return transmissions;
    }

    long retransmissions() {
        return retransmissions;
    }

    // ================================================================ receiver

    /**
     * Processes a segment payload, passing every completed message to {@code deliver} in order.
     *
     * @throws ProtocolException on framing violations (message overflow, boundary mismatch)
     */
    void onSegment(byte[] b, int off, int len, long now, Consumer<byte[]> deliver) throws ProtocolException {
        if (len < HEADER || len > HEADER + SEGMENT_DATA) {
            throw new ProtocolException("bad segment length " + len);
        }
        ByteBuffer buf = ByteBuffer.wrap(b, off, len);
        long seq = expand(buf.getInt(), rcvNext);
        byte flags = buf.get();
        if (seq < rcvNext) {
            ackNow = true;
            return;
        }
        if (seq >= rcvNext + WINDOW) {
            return;
        }
        if (seq > rcvNext) {
            if (!ooo.containsKey(seq)) {
                ooo.put(seq, new Seg(seq, flags, Arrays.copyOfRange(b, off + HEADER, off + len)));
                oooBytes += len - HEADER;
            }
            ackNow = true;
            return;
        }
        consume(flags, b, off + HEADER, len - HEADER, deliver);
        rcvNext++;
        Seg s;
        while ((s = ooo.remove(rcvNext)) != null) {
            oooBytes -= s.data.length;
            consume(s.flags, s.data, 0, s.data.length, deliver);
            rcvNext++;
        }
        ackPending++;
        if (ackPending >= 2 || !ooo.isEmpty()) {
            ackNow = true;
        } else if (ackDueAt < 0) {
            ackDueAt = now + ACK_DELAY_MILLIS;
        }
    }

    private void consume(byte flags, byte[] b, int off, int len, Consumer<byte[]> deliver) throws ProtocolException {
        if ((flags & FIRST) != 0) {
            if (assemblyLen >= 0) {
                throw new ProtocolException("message start inside a message");
            }
            assemblyLen = 0;
            if (assembly == null || assembly.length > 4 * SEGMENT_DATA) {
                assembly = new byte[SEGMENT_DATA];
            }
        } else if (assemblyLen < 0) {
            throw new ProtocolException("continuation without start");
        }
        if (assemblyLen + len > MAX_MESSAGE) {
            throw new ProtocolException("message too large");
        }
        if (assemblyLen + len > assembly.length) {
            assembly = Arrays.copyOf(assembly, Math.min(MAX_MESSAGE, Math.max(assembly.length * 2, assemblyLen + len)));
        }
        System.arraycopy(b, off, assembly, assemblyLen, len);
        assemblyLen += len;
        if ((flags & LAST) != 0) {
            deliver.accept(Arrays.copyOf(assembly, assemblyLen));
            assemblyLen = -1;
        }
    }

    boolean ackDue(long now) {
        return ackNow || (ackDueAt >= 0 && now >= ackDueAt);
    }

    byte[] buildAck() {
        long sack = 0;
        if (!ooo.isEmpty()) {
            for (int i = 0; i < 64; i++) {
                if (ooo.containsKey(rcvNext + 1 + i)) {
                    sack |= 1L << i;
                }
            }
        }
        ackNow = false;
        ackPending = 0;
        ackDueAt = -1;
        return ByteBuffer.allocate(ACK_LEN).putInt((int) rcvNext).putLong(sack).array();
    }

    long bufferedBytes() {
        return oooBytes + Math.max(0, assemblyLen) + unackedBytes;
    }

    /** Widens a 32-bit wire sequence number to the 64-bit value closest to {@code ref}. */
    static long expand(int wire, long ref) {
        long v = (ref & ~0xFFFFFFFFL) | (wire & 0xFFFFFFFFL);
        if (v < ref - (1L << 31)) {
            v += 1L << 32;
        } else if (v > ref + (1L << 31)) {
            v -= 1L << 32;
        }
        return v;
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
