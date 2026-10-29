package com.kartikey.p2pchat.conf.node.gossip;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Tiny bounds-checked reader and writer; every malformed input surfaces as IllegalArgumentException. */
final class Wire {

    private Wire() {}

    static final class Reader {
        private final byte[] d;
        private int pos;

        Reader(byte[] data, int offset) {
            this.d = data;
            this.pos = offset;
        }

        int remaining() {
            return d.length - pos;
        }

        int u8() {
            need(1);
            return d[pos++] & 0xff;
        }

        int u16() {
            need(2);
            int v = ((d[pos] & 0xff) << 8) | (d[pos + 1] & 0xff);
            pos += 2;
            return v;
        }

        int i32() {
            need(4);
            int v = ((d[pos] & 0xff) << 24) | ((d[pos + 1] & 0xff) << 16) | ((d[pos + 2] & 0xff) << 8) | (d[pos + 3] & 0xff);
            pos += 4;
            return v;
        }

        long i64() {
            long hi = i32() & 0xffffffffL;
            long lo = i32() & 0xffffffffL;
            return (hi << 32) | lo;
        }

        byte[] bytes(int n) {
            if (n < 0) throw new IllegalArgumentException("negative length");
            need(n);
            byte[] out = Arrays.copyOfRange(d, pos, pos + n);
            pos += n;
            return out;
        }

        String string(int n) {
            return new String(bytes(n), StandardCharsets.UTF_8);
        }

        void end() {
            if (remaining() != 0) throw new IllegalArgumentException("trailing bytes");
        }

        private void need(int n) {
            if (n > remaining()) throw new IllegalArgumentException("truncated message");
        }
    }

    static final class Writer {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        Writer u8(int v) {
            o.write(v);
            return this;
        }

        Writer u16(int v) {
            o.write(v >>> 8);
            o.write(v);
            return this;
        }

        Writer i32(int v) {
            u16(v >>> 16);
            return u16(v & 0xffff);
        }

        Writer i64(long v) {
            i32((int) (v >>> 32));
            return i32((int) v);
        }

        Writer bytes(byte[] b) {
            o.write(b, 0, b.length);
            return this;
        }

        byte[] done() {
            return o.toByteArray();
        }
    }
}
