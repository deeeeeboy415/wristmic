package com.wristmic.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Wire format shared with wristmic_receiver.py. */
final class Proto {
    private Proto() {}

    // watch -> PC
    static final int START = 0x01, AUDIO = 0x02, STOP = 0x03, PING = 0x05;
    // PC -> watch
    static final int PONG = 0x11, DONE = 0x13;

    /** The pairing key shared with the receiver; empty until the watch is paired. */
    private static volatile byte[] key = new byte[0];

    /** Sets the pairing key from its hex form. Anything invalid leaves the watch unpaired. */
    static void setKey(String hexKey) {
        byte[] k;
        try {
            k = hexKey == null ? new byte[0] : hex(hexKey.trim());
        } catch (RuntimeException e) {
            k = new byte[0];
        }
        key = k.length >= 16 ? k : new byte[0];
    }

    static boolean hasKey() {
        return key.length >= 16;
    }

    /** True for a pairing key the receiver would accept: 32 to 128 hex digits. */
    static boolean validKey(String hexKey) {
        return hexKey != null && hexKey.matches("[0-9a-fA-F]{32,128}") && hexKey.length() % 2 == 0;
    }

    /** [type:1][length:2, big-endian][payload] */
    static void frame(OutputStream out, int type, byte[] payload) throws IOException {
        int n = payload == null ? 0 : payload.length;
        out.write(type);
        out.write((n >> 8) & 0xff);
        out.write(n & 0xff);
        if (n > 0) out.write(payload, 0, n);
    }

    static byte[] hmac(byte[]... parts) {
        byte[] k = key;
        if (k.length == 0) throw new IllegalStateException("not paired");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            for (byte[] p : parts) mac.update(p);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] int32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    static byte[] hex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd length");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }
}
