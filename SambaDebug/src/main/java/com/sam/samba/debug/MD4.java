package com.sam.samba.debug;

/**
 * 纯 Java MD4 实现，用于计算 SMB NT Hash。
 * 参考 RFC 1320。
 */
public class MD4 {

    private final int[] state = new int[4];
    private final long[] count = new long[2];
    private final byte[] buffer = new byte[64];

    public MD4() {
        reset();
    }

    public void reset() {
        state[0] = 0x67452301;
        state[1] = 0xefcdab89;
        state[2] = 0x98badcfe;
        state[3] = 0x10325476;
        count[0] = 0;
        count[1] = 0;
        java.util.Arrays.fill(buffer, (byte) 0);
    }

    public byte[] digest(byte[] input) {
        reset();
        update(input, 0, input.length);
        return finish();
    }

    public void update(byte[] input, int offset, int length) {
        int index = (int) (count[0] / 8) % 64;
        count[0] += (long) length * 8;
        if (count[0] < (long) length * 8) {
            count[1]++;
        }
        count[1] += (long) length >> 61;

        int i = 0;
        if (index + length >= 64) {
            System.arraycopy(input, offset, buffer, index, 64 - index);
            transform(buffer, 0);
            i = 64 - index;
            while (i + 63 < length) {
                transform(input, offset + i);
                i += 64;
            }
            index = 0;
        }
        System.arraycopy(input, offset + i, buffer, index, length - i);
    }

    public byte[] finish() {
        int index = (int) (count[0] / 8) % 64;
        int paddingLen = (index < 56) ? (56 - index) : (120 - index);
        byte[] padding = new byte[paddingLen + 8];
        padding[0] = (byte) 0x80;
        long bitCount = count[0] + (count[1] << 3);
        for (int i = 0; i < 8; i++) {
            padding[paddingLen + i] = (byte) (bitCount >>> (i * 8));
        }
        update(padding, 0, padding.length);

        byte[] result = new byte[16];
        for (int i = 0; i < 4; i++) {
            result[i * 4]     = (byte) (state[i]);
            result[i * 4 + 1] = (byte) (state[i] >>> 8);
            result[i * 4 + 2] = (byte) (state[i] >>> 16);
            result[i * 4 + 3] = (byte) (state[i] >>> 24);
        }
        return result;
    }

    private void transform(byte[] block, int offset) {
        int[] x = new int[16];
        for (int i = 0; i < 16; i++) {
            x[i] = (block[offset + i * 4] & 0xff)

                    | ((block[offset + i * 4 + 1] & 0xff) << 8)
                    | ((block[offset + i * 4 + 2] & 0xff) << 16)
                    | ((block[offset + i * 4 + 3] & 0xff) << 24);
        }

        int a = state[0], b = state[1], c = state[2], d = state[3];

        // Round 1
        a = ff(a, b, c, d, x[ 0],  3); d = ff(d, a, b, c, x[ 1],  7);
        c = ff(c, d, a, b, x[ 2], 11); b = ff(b, c, d, a, x[ 3], 19);
        a = ff(a, b, c, d, x[ 4],  3); d = ff(d, a, b, c, x[ 5],  7);
        c = ff(c, d, a, b, x[ 6], 11); b = ff(b, c, d, a, x[ 7], 19);
        a = ff(a, b, c, d, x[ 8],  3); d = ff(d, a, b, c, x[ 9],  7);
        c = ff(c, d, a, b, x[10], 11); b = ff(b, c, d, a, x[11], 19);
        a = ff(a, b, c, d, x[12],  3); d = ff(d, a, b, c, x[13],  7);
        c = ff(c, d, a, b, x[14], 11); b = ff(b, c, d, a, x[15], 19);

        // Round 2
        a = gg(a, b, c, d, x[ 0],  3); d = gg(d, a, b, c, x[ 4],  5);
        c = gg(c, d, a, b, x[ 8],  9); b = gg(b, c, d, a, x[12], 13);
        a = gg(a, b, c, d, x[ 1],  3); d = gg(d, a, b, c, x[ 5],  5);
        c = gg(c, d, a, b, x[ 9],  9); b = gg(b, c, d, a, x[13], 13);
        a = gg(a, b, c, d, x[ 2],  3); d = gg(d, a, b, c, x[ 6],  5);
        c = gg(c, d, a, b, x[10],  9); b = gg(b, c, d, a, x[14], 13);
        a = gg(a, b, c, d, x[ 3],  3); d = gg(d, a, b, c, x[ 7],  5);
        c = gg(c, d, a, b, x[11],  9); b = gg(b, c, d, a, x[15], 13);

        // Round 3
        a = hh(a, b, c, d, x[ 0],  3); d = hh(d, a, b, c, x[ 8],  9);
        c = hh(c, d, a, b, x[ 4], 11); b = hh(b, c, d, a, x[12], 15);
        a = hh(a, b, c, d, x[ 2],  3); d = hh(d, a, b, c, x[10],  9);
        c = hh(c, d, a, b, x[ 6], 11); b = hh(b, c, d, a, x[14], 15);
        a = hh(a, b, c, d, x[ 1],  3); d = hh(d, a, b, c, x[ 9],  9);
        c = hh(c, d, a, b, x[ 5], 11); b = hh(b, c, d, a, x[13], 15);
        a = hh(a, b, c, d, x[ 3],  3); d = hh(d, a, b, c, x[11],  9);
        c = hh(c, d, a, b, x[ 7], 11); b = hh(b, c, d, a, x[15], 15);

        state[0] += a; state[1] += b; state[2] += c; state[3] += d;
    }

    private static int rotateLeft(int x, int n) { return (x << n) | (x >>> (32 - n)); }

    private static int ff(int a, int b, int c, int d, int x, int s) {
        return rotateLeft(a + ((b & c) | (~b & d)) + x, s);
    }

    private static int gg(int a, int b, int c, int d, int x, int s) {
        return rotateLeft(a + ((b & c) | (b & d) | (c & d)) + x + 0x5a827999, s);
    }

    private static int hh(int a, int b, int c, int d, int x, int s) {
        return rotateLeft(a + (b ^ c ^ d) + x + 0x6ed9eba1, s);
    }
}
