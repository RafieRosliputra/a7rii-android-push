package com.johorstudio.tether;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Self-contained QR encoder (ISO/IEC 18004): byte mode, ECC level M, versions 1..10,
 * automatic mask selection. encode() returns a boolean matrix (true = dark module);
 * toBitmap() renders it black-on-white with a 4-module quiet zone and integer scaling.
 */
public final class QrCode {
    private QrCode() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // Byte-mode data capacity (bytes) for ECC M, versions 1..10.
    private static final int[] CAPACITY = {0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213};

    // ECC codewords per block for ECC M, versions 1..10.
    private static final int[] ECC_LEN = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};

    // Block layout (count, data codewords per block) for ECC M, versions 1..10.
    private static final int[] BLK1_COUNT = {0, 1, 1, 1, 2, 2, 4, 4, 2, 3, 4};
    private static final int[] BLK1_DATA  = {0, 16, 28, 44, 32, 43, 27, 31, 38, 36, 43};
    private static final int[] BLK2_COUNT = {0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 1};
    private static final int[] BLK2_DATA  = {0, 0, 0, 0, 0, 0, 0, 0, 39, 37, 44};

    // Alignment-pattern centre coordinates, versions 2..10 (index 0/1 = none).
    private static final int[][] ALIGN = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}
    };

    private static final int PENALTY_N1 = 3, PENALTY_N2 = 3, PENALTY_N3 = 40, PENALTY_N4 = 10;

    // ------------------------------------------------------------------ public API

    /** Encode a UTF-8 string as a square QR matrix (byte mode, ECC M, auto version 1..10). */
    public static boolean[][] encode(String s) {
        if (s == null) throw new IllegalArgumentException("text is null");
        byte[] data = s.getBytes(UTF8);
        int version = 0;
        for (int v = 1; v <= 10; v++) if (data.length <= CAPACITY[v]) { version = v; break; }
        if (version == 0) throw new IllegalArgumentException("text too long (max " + CAPACITY[10] + " bytes)");
        return build(version, data);
    }

    /** Render a QR code as a crisp (non-anti-aliased) black-on-white bitmap with a 4-module quiet zone. */
    public static Bitmap toBitmap(String s, int px) {
        boolean[][] qr = encode(s);
        int n = qr.length;
        int quiet = 4;
        int total = n + 2 * quiet;
        int scale = Math.max(1, px / total);
        int dim = total * scale;
        int[] pixels = new int[dim * dim];
        Arrays.fill(pixels, Color.WHITE);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!qr[y][x]) continue;
                int top = (y + quiet) * scale, left = (x + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (top + dy) * dim;
                    for (int dx = 0; dx < scale; dx++) pixels[row + left + dx] = Color.BLACK;
                }
            }
        }
        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.ARGB_8888);
    }

    // ------------------------------------------------------------------ construction

    private static boolean[][] build(int version, byte[] data) {
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] function = new boolean[size][size];

        drawFunctionPatterns(version, modules, function);
        drawCodewords(modules, function, addEccAndInterleave(version, encodeData(version, data)));

        int mask = chooseMask(modules, function);
        applyMask(modules, function, mask);
        drawFormatBits(modules, function, mask);
        return modules;
    }

    private static boolean bit(int value, int i) { return ((value >>> i) & 1) != 0; }

    private static void setFunction(boolean[][] m, boolean[][] f, int y, int x, boolean dark) {
        m[y][x] = dark;
        f[y][x] = true;
    }

    private static void drawFunctionPatterns(int version, boolean[][] m, boolean[][] f) {
        int size = m.length;
        for (int i = 0; i < size; i++) {
            setFunction(m, f, 6, i, i % 2 == 0);
            setFunction(m, f, i, 6, i % 2 == 0);
        }
        drawFinder(m, f, 3, 3);
        drawFinder(m, f, size - 4, 3);
        drawFinder(m, f, 3, size - 4);
        int[] pos = ALIGN[version];
        int n = pos.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (!((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)))
                    drawAlignment(m, f, pos[i], pos[j]);
        drawFormatBits(m, f, 0); // dummy mask; overwritten after the mask is chosen
        drawVersion(m, f, version);
    }

    private static void drawFinder(boolean[][] m, boolean[][] f, int cx, int cy) {
        int size = m.length;
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (0 <= x && x < size && 0 <= y && y < size)
                    setFunction(m, f, y, x, dist != 2 && dist != 4);
            }
    }

    private static void drawAlignment(boolean[][] m, boolean[][] f, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++)
                setFunction(m, f, cy + dy, cx + dx, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private static void drawFormatBits(boolean[][] m, boolean[][] f, int mask) {
        int size = m.length;
        int data = mask; // ECC M -> format bits "00"
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        // first copy: around the top-left finder (col 8 top, then row 8 left)
        for (int i = 0; i <= 5; i++) setFunction(m, f, i, 8, bit(bits, i));
        setFunction(m, f, 7, 8, bit(bits, 6));
        setFunction(m, f, 8, 8, bit(bits, 7));
        setFunction(m, f, 8, 7, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(m, f, 8, 14 - i, bit(bits, i));
        // second copy: row 8 right side, then col 8 bottom side
        for (int i = 0; i < 8; i++) setFunction(m, f, 8, size - 1 - i, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(m, f, size - 15 + i, 8, bit(bits, i));
        setFunction(m, f, size - 8, 8, true); // dark module
    }

    private static void drawVersion(boolean[][] m, boolean[][] f, int version) {
        if (version < 7) return;
        int size = m.length;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3;
            int c = i / 3;
            setFunction(m, f, c, a, b);
            setFunction(m, f, a, c, b);
        }
    }

    // ------------------------------------------------------------------ data

    private static final class BitBuffer {
        final byte[] data = new byte[256];
        int bitLen;

        void append(int value, int len) {
            for (int i = len - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) data[bitLen >>> 3] |= (byte) (1 << (7 - (bitLen & 7)));
                bitLen++;
            }
        }

        byte[] bytes() { return Arrays.copyOf(data, (bitLen + 7) / 8); }
    }

    private static byte[] encodeData(int version, byte[] text) {
        int dataCodewords = BLK1_COUNT[version] * BLK1_DATA[version] + BLK2_COUNT[version] * BLK2_DATA[version];
        int capBits = dataCodewords * 8;
        BitBuffer bb = new BitBuffer();
        bb.append(0x4, 4);                          // byte mode
        bb.append(text.length, version < 10 ? 8 : 16); // character count
        for (byte b : text) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capBits - bb.bitLen));       // terminator
        bb.append(0, (8 - bb.bitLen % 8) % 8);                // align to byte
        int pad = 0xEC;
        while (bb.bitLen < capBits) { bb.append(pad, 8); pad ^= 0xEC ^ 0x11; }
        return bb.bytes();
    }

    // ------------------------------------------------------------------ Reed-Solomon (GF(256), primitive polynomial 0x11D)

    private static final byte[] EXP = new byte[512];
    private static final byte[] LOG = new byte[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = (byte) x;
            LOG[x] = (byte) i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[(LOG[a] & 0xFF) + (LOG[b] & 0xFF)] & 0xFF;
    }

    private static int[] rsDivisor(int degree) {
        int[] g = new int[degree + 1];
        g[0] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = degree; j > 0; j--) g[j] = g[j - 1] ^ gfMul(g[j], root);
            g[0] = gfMul(g[0], root);
            root = gfMul(root, 0x02);
        }
        return g;
    }

    private static byte[] rsRemainder(byte[] data, int[] divisor) {
        int degree = divisor.length - 1;
        byte[] rem = new byte[data.length + degree];
        System.arraycopy(data, 0, rem, 0, data.length);
        for (int i = 0; i < data.length; i++) {
            int factor = rem[i] & 0xFF;
            if (factor != 0)
                for (int j = 0; j <= degree; j++)
                    rem[i + degree - j] ^= (byte) gfMul(divisor[j], factor);
        }
        return Arrays.copyOfRange(rem, data.length, rem.length);
    }

    private static byte[] addEccAndInterleave(int version, byte[] data) {
        int eccLen = ECC_LEN[version];
        int numBlocks = BLK1_COUNT[version] + BLK2_COUNT[version];
        int totalData = BLK1_COUNT[version] * BLK1_DATA[version] + BLK2_COUNT[version] * BLK2_DATA[version];
        byte[] out = new byte[totalData + numBlocks * eccLen];

        byte[][] blockData = new byte[numBlocks][];
        byte[][] blockEcc = new byte[numBlocks][];
        int[] div = rsDivisor(eccLen);
        int k = 0, bi = 0;
        for (int g = 0; g < 2; g++) {
            int count = g == 0 ? BLK1_COUNT[version] : BLK2_COUNT[version];
            int dlen = g == 0 ? BLK1_DATA[version] : BLK2_DATA[version];
            for (int b = 0; b < count; b++) {
                byte[] db = new byte[dlen];
                System.arraycopy(data, k, db, 0, dlen);
                k += dlen;
                blockData[bi] = db;
                blockEcc[bi] = rsRemainder(db, div);
                bi++;
            }
        }

        int p = 0;
        int maxData = Math.max(BLK1_DATA[version], BLK2_DATA[version]);
        for (int i = 0; i < maxData; i++)
            for (int b = 0; b < numBlocks; b++)
                if (i < blockData[b].length) out[p++] = blockData[b][i];
        for (int i = 0; i < eccLen; i++)
            for (int b = 0; b < numBlocks; b++)
                out[p++] = blockEcc[b][i];
        return out;
    }

    // ------------------------------------------------------------------ matrix fill + mask

    private static void drawCodewords(boolean[][] m, boolean[][] f, byte[] data) {
        int size = m.length;
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!f[y][x] && i < data.length * 8) {
                        m[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] f, int mask) {
        int size = m.length;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (f[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    case 7: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    default: throw new IllegalArgumentException("bad mask");
                }
                m[y][x] ^= invert;
            }
    }

    private static int chooseMask(boolean[][] m, boolean[][] f) {
        int best = 0, minPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(m, f, mask);
            drawFormatBits(m, f, mask);
            int penalty = penaltyScore(m);
            if (penalty < minPenalty) { minPenalty = penalty; best = mask; }
            applyMask(m, f, mask); // undo
        }
        return best;
    }

    private static int penaltyScore(boolean[][] m) {
        int size = m.length;
        int result = 0;
        for (int y = 0; y < size; y++) result += linePenalty(m[y]);
        boolean[] col = new boolean[size];
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) col[y] = m[y][x];
            result += linePenalty(col);
        }
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += PENALTY_N2;
            }
        int dark = 0;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (m[y][x]) dark++;
        int total = size * size;
        int percent = (dark * 100 + total / 2) / total;
        int low = percent / 5 * 5, high = low + 5;
        result += Math.min(Math.abs(low - 50) / 5, Math.abs(high - 50) / 5) * PENALTY_N4;
        return result;
    }

    private static int linePenalty(boolean[] line) {
        int size = line.length;
        int result = 0;
        boolean runColor = false;
        int runLen = 0;
        int[] hist = new int[7];
        for (int i = 0; i < size; i++) {
            if (line[i] == runColor) {
                runLen++;
                if (runLen == 5) result += PENALTY_N1;
                else if (runLen > 5) result += 1;
            } else {
                addHistory(hist, runLen, size);
                if (!runColor) result += countFinderPatterns(hist) * PENALTY_N3;
                runColor = line[i];
                runLen = 1;
            }
        }
        if (runColor) { addHistory(hist, runLen, size); runLen = 0; }
        addHistory(hist, runLen + size, size);
        return result + countFinderPatterns(hist) * PENALTY_N3;
    }

    private static void addHistory(int[] hist, int runLen, int size) {
        if (hist[0] == 0) runLen += size; // light border before the first run
        System.arraycopy(hist, 0, hist, 1, hist.length - 1);
        hist[0] = runLen;
    }

    private static int countFinderPatterns(int[] hist) {
        int n = hist[1];
        boolean core = n > 0 && hist[2] == n && hist[3] == n * 3 && hist[4] == n && hist[5] == n;
        int c = 0;
        if (core && hist[0] >= n * 4 && hist[6] >= n) c++;
        if (core && hist[6] >= n * 4 && hist[0] >= n) c++;
        return c;
    }
}
