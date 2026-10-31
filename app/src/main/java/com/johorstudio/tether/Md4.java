package com.johorstudio.tether;

/** MD4 (RFC 1320). Android has no MD4 provider, but NTLM needs it for the NT hash. */
public final class Md4 {
    private Md4() {}

    public static byte[] digest(byte[] msg) {
        int n = msg.length;
        int padLen = ((n + 8) / 64 + 1) * 64;
        byte[] m = new byte[padLen];
        System.arraycopy(msg, 0, m, 0, n);
        m[n] = (byte) 0x80;
        long bits = (long) n * 8;
        for (int i = 0; i < 8; i++) m[padLen - 8 + i] = (byte) (bits >>> (8 * i));

        int a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;
        int[] x = new int[16];
        for (int blk = 0; blk < padLen; blk += 64) {
            for (int i = 0; i < 16; i++) {
                int o = blk + i * 4;
                x[i] = (m[o] & 0xff) | ((m[o + 1] & 0xff) << 8) | ((m[o + 2] & 0xff) << 16) | ((m[o + 3] & 0xff) << 24);
            }
            int aa = a, bb = b, cc = c, dd = d;
            int[] s1 = {3, 7, 11, 19};
            for (int i = 0; i < 16; i++) {
                int f = (b & c) | (~b & d);
                int t = Integer.rotateLeft(a + f + x[i], s1[i % 4]);
                a = d; d = c; c = b; b = t;
            }
            int[] s2 = {3, 5, 9, 13};
            int[] o2 = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15};
            for (int i = 0; i < 16; i++) {
                int g = (b & c) | (b & d) | (c & d);
                int t = Integer.rotateLeft(a + g + x[o2[i]] + 0x5a827999, s2[i % 4]);
                a = d; d = c; c = b; b = t;
            }
            int[] s3 = {3, 9, 11, 15};
            int[] o3 = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
            for (int i = 0; i < 16; i++) {
                int h = b ^ c ^ d;
                int t = Integer.rotateLeft(a + h + x[o3[i]] + 0x6ed9eba1, s3[i % 4]);
                a = d; d = c; c = b; b = t;
            }
            a += aa; b += bb; c += cc; d += dd;
        }
        byte[] out = new byte[16];
        int[] r = {a, b, c, d};
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) out[i * 4 + j] = (byte) (r[i] >>> (8 * j));
        return out;
    }
}
