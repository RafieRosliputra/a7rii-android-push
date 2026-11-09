package com.johorstudio.tether;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal PTP-over-USB (still image class) implementation on top of Android USB host API. */
public class Ptp {
    public static final int OC_GetDeviceInfo = 0x1001;
    public static final int OC_OpenSession = 0x1002;
    public static final int OC_CloseSession = 0x1003;
    public static final int OC_GetObjectInfo = 0x1008;
    public static final int OC_GetObject = 0x1009;
    public static final int OC_GetStorageIDs = 0x1004;
    public static final int OC_GetObjectHandles = 0x1007;
    public static final int OC_GetThumb = 0x100A;
    public static final int OC_GetPartialObject = 0x101B;
    public static final int OC_Sony_SDIOConnect = 0x9201;
    public static final int OC_Sony_GetSDIOGetExtDeviceInfo = 0x9202;
    public static final int OC_Sony_GetAllDevicePropData = 0x9209;
    public static final int DPC_Sony_ObjectInMemory = 0xD215;

    public static final int RC_OK = 0x2001;
    public static final int RC_SessionAlreadyOpen = 0x201E;

    public static final int EC_ObjectAdded = 0x4002;
    public static final int EC_Sony_ObjectAdded = 0xC201;

    public static final int TYPE_COMMAND = 1, TYPE_DATA = 2, TYPE_RESPONSE = 3, TYPE_EVENT = 4;

    public interface Progress { void onProgress(long done, long total); }

    public static class Response {
        public int code;
        public int[] params = new int[0];
        public byte[] data;
        public boolean ok() { return code == RC_OK; }
    }

    public static class Event {
        public int code;
        public int[] params;
    }

    public static class DeviceInfo {
        public int vendorExtId;
        public String vendorExtDesc = "", manufacturer = "", model = "", version = "", serial = "";
        public int[] operations = new int[0];
        public boolean supportsOp(int op) { for (int o : operations) if (o == op) return true; return false; }
    }

    public static class ObjectInfo {
        public int storageId, format, size, parent, thumbSize;
        public String filename = "";
        public String captureDate = "";
    }

    public interface Logger { void log(String s); }

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint epIn, epOut, epIntr;
    private int tid = 0;
    private byte[] buf = new byte[16384];          // small transactions
    private byte[] bigBuf;                         // GetObject (large reads)
    private boolean bigOk = Build.VERSION.SDK_INT >= 28;
    private Logger logger;

    private Ptp(UsbDeviceConnection c, UsbInterface i, UsbEndpoint in, UsbEndpoint out, UsbEndpoint intr) {
        conn = c; intf = i; epIn = in; epOut = out; epIntr = intr;
    }

    public void setLogger(Logger l) { logger = l; }
    private void dbg(String s) { if (logger != null) logger.log(s); }

    public String describe() {
        return String.format("if#%d cls=%d in=0x%02X(%d) out=0x%02X(%d) int=%s",
                intf.getId(), intf.getInterfaceClass(),
                epIn.getAddress(), epIn.getMaxPacketSize(), epOut.getAddress(), epOut.getMaxPacketSize(),
                epIntr == null ? "-" : String.format("0x%02X", epIntr.getAddress()));
    }

    /** PTP class request: Get Device Status (0x67). Returns response code or -1. */
    public int getDeviceStatus() {
        byte[] b = new byte[32];
        int n = conn.controlTransfer(0xA1, 0x67, 0, intf.getId(), b, b.length, 2000);
        if (n < 4) return -1;
        return (b[2] & 0xFF) | ((b[3] & 0xFF) << 8);
    }

    /** PTP class request: Device Reset (0x66) + clear halts + drain stale data. */
    public void resetDevice() {
        int r = conn.controlTransfer(0x21, 0x66, 0, intf.getId(), null, 0, 3000);
        dbg("Device Reset -> " + r);
        clearHalt(epIn); clearHalt(epOut);
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        drain();
        tid = 0;
    }

    private void clearHalt(UsbEndpoint e) {
        conn.controlTransfer(0x02, 0x01, 0, e.getAddress(), null, 0, 1000);
    }

    /** Throw away anything left in the IN pipe from an earlier session. */
    public void drain() {
        int total = 0;
        for (int i = 0; i < 5000; i++) {
            int n = conn.bulkTransfer(epIn, buf, buf.length, 150);
            if (n <= 0) break;
            total += n;
        }
        if (total > 0) dbg("Buang " + total + " bait data lama");
    }

    public static UsbInterface findPtpInterface(UsbDevice dev) {
        UsbInterface fallback = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface it = dev.getInterface(i);
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) return it;
            if (it.getEndpointCount() == 3 && fallback == null) fallback = it;
        }
        return fallback;
    }

    public static Ptp open(UsbManager mgr, UsbDevice dev) throws IOException {
        UsbInterface intf = findPtpInterface(dev);
        if (intf == null) throw new IOException("Tiada interface PTP/MTP pada peranti ini");
        UsbEndpoint in = null, out = null, intr = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e; else out = e;
            } else if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                intr = e;
            }
        }
        if (in == null || out == null) throw new IOException("Endpoint bulk tidak dijumpai");
        UsbDeviceConnection c = mgr.openDevice(dev);
        if (c == null) throw new IOException("Gagal buka peranti USB (tiada kebenaran?)");
        if (!c.claimInterface(intf, true)) { c.close(); throw new IOException("Gagal claim interface USB"); }
        return new Ptp(c, intf, in, out, intr);
    }

    public void close() {
        try { conn.releaseInterface(intf); } catch (Exception ignored) {}
        try { conn.close(); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- transport

    private void writeAll(byte[] data, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int chunk = Math.min(len - off, 16384);
            int n = Build.VERSION.SDK_INT >= 28
                    ? conn.bulkTransfer(epOut, data, off, chunk, 5000)
                    : bulkOutCompat(data, off, chunk);
            if (n < 0) throw new IOException("USB tulis gagal");
            off += n;
        }
    }

    private int bulkOutCompat(byte[] data, int off, int len) {
        if (off == 0) return conn.bulkTransfer(epOut, data, len, 5000);
        byte[] tmp = new byte[len];
        System.arraycopy(data, off, tmp, 0, len);
        return conn.bulkTransfer(epOut, tmp, len, 5000);
    }

    private byte[] cur; // buffer used by the current transaction

    private int readPacket(int timeout) {
        return conn.bulkTransfer(epIn, cur, cur.length, timeout);
    }

    private static ByteBuffer le(byte[] b, int off, int len) {
        return ByteBuffer.wrap(b, off, len).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Run one PTP transaction. Incoming data goes to sink if given, otherwise into Response.data. */
    public synchronized Response transact(int op, int[] params, byte[] dataOut, OutputStream sink, Progress progress)
            throws IOException {
        if (params == null) params = new int[0];
        int myTid = (op == OC_OpenSession) ? 0 : ++tid;
        if (op == OC_OpenSession) tid = 0;

        int clen = 12 + 4 * params.length;
        boolean quiet = (op == OC_Sony_GetAllDevicePropData);
        if (!quiet) dbg(String.format("→ op 0x%04X tid %d", op, myTid));
        ByteBuffer cmd = ByteBuffer.allocate(clen).order(ByteOrder.LITTLE_ENDIAN);
        cmd.putInt(clen).putShort((short) TYPE_COMMAND).putShort((short) op).putInt(myTid);
        for (int p : params) cmd.putInt(p);
        writeAll(cmd.array(), clen);

        if (dataOut != null) {
            int dlen = 12 + dataOut.length;
            ByteBuffer d = ByteBuffer.allocate(dlen).order(ByteOrder.LITTLE_ENDIAN);
            d.putInt(dlen).putShort((short) TYPE_DATA).putShort((short) op).putInt(myTid).put(dataOut);
            writeAll(d.array(), dlen);
        }

        // Large single reads (>16 KB) fail instantly on some phones, so always read 16 KB at a time.
        cur = buf;

        Response r = new Response();
        ByteArrayOutputStream mem = null;
        int zeroReads = 0, junk = 0;
        long t0 = System.currentTimeMillis();
        while (true) {
            int n = readPacket((op == OC_GetObject || op == OC_GetObjectInfo) ? 20000 : 5000);
            if (n < 0) {
                throw new IOException(String.format("USB baca gagal (op 0x%04X, %d ms, bufsz %d)",
                        op, System.currentTimeMillis() - t0, cur.length));
            }
            if (n == 0) { if (++zeroReads > 3) throw new IOException("Tiada respons daripada kamera"); continue; }
            if (n < 12) throw new IOException("Paket PTP terlalu pendek (" + n + ")");
            byte[] buf = cur;
            ByteBuffer h = le(buf, 0, n);
            long len = h.getInt() & 0xFFFFFFFFL;
            int type = h.getShort() & 0xFFFF;
            int code = h.getShort() & 0xFFFF;
            int ptid = h.getInt();

            // Not a container header for this transaction → stale data from an earlier transfer; skip it.
            boolean valid = len >= 12 && (type == TYPE_DATA || type == TYPE_RESPONSE);
            if (!valid || ptid != myTid) {
                if (++junk > 2000) throw new IOException("Aliran USB rosak (tidak dapat selaras semula)");
                if (valid && type == TYPE_DATA) {          // whole old data phase: discard it
                    long left = len - n;
                    while (left > 0) { int m = readPacket(3000); if (m <= 0) break; left -= m; }
                }
                if (junk == 1) dbg(String.format("Buang paket lama (tid %d, mahu %d)", ptid, myTid));
                continue;
            }

            if (type == TYPE_DATA) {
                long total = len - 12;
                OutputStream os = sink;
                if (os == null) { mem = new ByteArrayOutputStream((int) Math.min(total, 4 * 1024 * 1024)); os = mem; }
                long got = Math.min(n - 12, total);
                os.write(buf, 12, (int) got);
                if (progress != null) progress.onProgress(got, total);
                while (got < total) {
                    int m = readPacket(15000);
                    if (m < 0) throw new IOException("USB baca data gagal");
                    if (m == 0) continue;
                    int use = (int) Math.min(m, total - got);
                    os.write(buf, 0, use);
                    got += use;
                    if (progress != null) progress.onProgress(got, total);
                }
                continue; // response follows
            }
            if (type == TYPE_RESPONSE) {
                r.code = code;
                if (!quiet || code != RC_OK) dbg(String.format("← 0x%04X (op 0x%04X)", code, op));
                int np = (int) Math.max(0, (Math.min(len, n) - 12) / 4);
                r.params = new int[np];
                for (int i = 0; i < np; i++) r.params[i] = h.getInt();
                break;
            }
            // anything else: ignore and keep reading
        }
        if (mem != null) r.data = mem.toByteArray();
        return r;
    }

    public Response transact(int op, int... params) throws IOException {
        return transact(op, params, null, null, null);
    }

    /** Poll the interrupt endpoint for an event. Returns null on timeout. */
    public Event pollEvent(int timeoutMs) {
        if (epIntr == null) { try { Thread.sleep(timeoutMs); } catch (InterruptedException ignored) {} return null; }
        byte[] b = new byte[Math.max(64, epIntr.getMaxPacketSize())];
        int n = conn.bulkTransfer(epIntr, b, b.length, timeoutMs);
        if (n < 12) return null;
        ByteBuffer h = le(b, 0, n);
        int len = h.getInt();
        int type = h.getShort() & 0xFFFF;
        if (type != TYPE_EVENT) return null;
        Event e = new Event();
        e.code = h.getShort() & 0xFFFF;
        h.getInt();
        int np = Math.max(0, (Math.min(len, n) - 12) / 4);
        e.params = new int[np];
        for (int i = 0; i < np; i++) e.params[i] = h.getInt();
        return e;
    }

    // ---------------------------------------------------------------- parsing helpers

    private static String readString(ByteBuffer b) {
        if (!b.hasRemaining()) return "";
        int n = b.get() & 0xFF;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && b.remaining() >= 2; i++) {
            char c = b.getChar();
            if (c != 0) sb.append(c);
        }
        return sb.toString();
    }

    private static int[] readU16Array(ByteBuffer b) {
        int n = b.getInt();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = b.getShort() & 0xFFFF;
        return a;
    }

    public DeviceInfo getDeviceInfo() throws IOException {
        Response r = transact(OC_GetDeviceInfo);
        if (!r.ok() || r.data == null) throw new IOException(String.format("GetDeviceInfo gagal 0x%04X", r.code));
        ByteBuffer b = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN);
        DeviceInfo d = new DeviceInfo();
        try {
            b.getShort();                 // standard version
            d.vendorExtId = b.getInt();
            b.getShort();                 // vendor ext version
            d.vendorExtDesc = readString(b);
            b.getShort();                 // functional mode
            d.operations = readU16Array(b);
            readU16Array(b);              // events
            readU16Array(b);              // device props
            readU16Array(b);              // capture formats
            readU16Array(b);              // image formats
            d.manufacturer = readString(b);
            d.model = readString(b);
            d.version = readString(b);
            d.serial = readString(b);
        } catch (Exception ignored) { /* partial info is fine */ }
        return d;
    }

    public ObjectInfo getObjectInfo(int handle) throws IOException {
        Response r = transact(OC_GetObjectInfo, handle);
        if (!r.ok() || r.data == null || r.data.length < 53)
            throw new IOException(String.format("GetObjectInfo gagal 0x%04X", r.code));
        ByteBuffer b = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN);
        ObjectInfo o = new ObjectInfo();
        o.storageId = b.getInt(0);
        o.format = b.getShort(4) & 0xFFFF;
        o.size = b.getInt(8);
        o.thumbSize = b.getInt(14);
        o.parent = b.getInt(38);
        b.position(52);
        o.filename = readString(b);
        o.captureDate = readString(b);
        return o;
    }

    public void openSession() throws IOException {
        Response r = transact(OC_OpenSession, 1);
        if (r.code != RC_OK && r.code != RC_SessionAlreadyOpen)
            throw new IOException(String.format("OpenSession gagal 0x%04X", r.code));
    }

    public void closeSession() {
        try { transact(OC_CloseSession); } catch (Exception ignored) {}
    }

    /** Sony "PC Remote" handshake (same sequence libgphoto2 uses). */
    public void sonyHandshake() throws IOException {
        transact(OC_Sony_SDIOConnect, 1, 0, 0);
        transact(OC_Sony_SDIOConnect, 2, 0, 0);
        transact(OC_Sony_GetSDIOGetExtDeviceInfo, 0xC8);
        transact(OC_Sony_SDIOConnect, 3, 0, 0);
    }

    /**
     * Sony: read all device properties (0x9209) and return the value of ObjectInMemory (0xD215).
     * A value above 0x8000 means a captured image is waiting at handle 0xFFFFC001.
     * Returns -1 if the property could not be found/parsed.
     */
    public int sonyObjectInMemory() throws IOException {
        Response r = transact(OC_Sony_GetAllDevicePropData);
        if (!r.ok() || r.data == null || r.data.length < 8) return -1;
        ByteBuffer b = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN);
        try {
            long count = b.getLong();
            for (long i = 0; i < count && b.remaining() >= 6; i++) {
                int code = b.getShort() & 0xFFFF;
                int type = b.getShort() & 0xFFFF;
                b.get();            // get/set
                b.get();            // is enabled (Sony)
                skipValue(b, type); // factory default
                int pos = b.position();
                long cur = readNumber(b, type);
                if (code == DPC_Sony_ObjectInMemory) return (int) cur;
                b.position(pos);
                skipValue(b, type); // current value
                int form = b.get() & 0xFF;
                if (form == 1) { skipValue(b, type); skipValue(b, type); skipValue(b, type); }
                else if (form == 2) { int n = b.getShort() & 0xFFFF; for (int k = 0; k < n; k++) skipValue(b, type); }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private static int typeSize(int t) {
        switch (t & 0xFF) {
            case 1: case 2: return 1;
            case 3: case 4: return 2;
            case 5: case 6: return 4;
            case 7: case 8: return 8;
            case 9: case 10: return 16;
            default: return 0;
        }
    }

    private static void skipValue(ByteBuffer b, int type) {
        if (type == 0xFFFF) { int n = b.get() & 0xFF; b.position(b.position() + 2 * n); return; }
        int sz = typeSize(type);
        if ((type & 0x4000) != 0) { int n = b.getInt(); b.position(b.position() + n * sz); return; }
        b.position(b.position() + sz);
    }

    private static long readNumber(ByteBuffer b, int type) {
        switch (type) {
            case 1: return b.get();
            case 2: return b.get() & 0xFF;
            case 3: return b.getShort();
            case 4: return b.getShort() & 0xFFFF;
            case 5: return b.getInt();
            case 6: return b.getInt() & 0xFFFFFFFFL;
            case 7: case 8: return b.getLong();
            default: skipValue(b, type); return -1;
        }
    }

    public Response getObject(int handle, OutputStream sink, Progress p) throws IOException {
        return transact(OC_GetObject, new int[]{handle}, null, sink, p);
    }

    /** List storage IDs (0x1004). */
    public int[] getStorageIDs() throws IOException {
        Response r = transact(OC_GetStorageIDs);
        if (!r.ok() || r.data == null) throw new IOException(String.format("GetStorageIDs gagal 0x%04X", r.code));
        return u32Array(r.data);
    }

    /** Object handles on one storage, all formats, root parent (0x1007). */
    public int[] getObjectHandles(int storageId) throws IOException {
        Response r = transact(OC_GetObjectHandles, storageId, 0, 0xFFFFFFFF);
        if (!r.ok() || r.data == null) throw new IOException(String.format("GetObjectHandles gagal 0x%04X", r.code));
        return u32Array(r.data);
    }

    /** Thumbnail bytes (0x100A); null when the camera has none / the op fails. */
    public byte[] getThumb(int handle) throws IOException {
        Response r = transact(OC_GetThumb, handle);
        if (!r.ok() || r.data == null || r.data.length == 0) return null;
        return r.data;
    }

    /** First `count` bytes of an object (0x101B), for the EXIF-thumbnail fallback. */
    public void getPartialObject(int handle, int offset, int count, OutputStream sink, Progress p) throws IOException {
        Response r = transact(OC_GetPartialObject, new int[]{handle, offset, count}, null, sink, p);
        if (!r.ok()) throw new IOException(String.format("GetPartialObject gagal 0x%04X", r.code));
    }

    /** Browse helper: every object handle across every storage (all formats, root parent). */
    public int[] listObjectHandles() throws IOException {
        List<Integer> hs = new ArrayList<>();
        for (int sid : getStorageIDs()) for (int h : getObjectHandles(sid)) hs.add(h);
        int[] a = new int[hs.size()];
        for (int i = 0; i < a.length; i++) a[i] = hs.get(i);
        return a;
    }

    /** Browse helper: full ObjectInfo for every object (caller filters formats). */
    public List<ObjectInfo> listObjects() throws IOException {
        List<ObjectInfo> out = new ArrayList<>();
        for (int h : listObjectHandles()) {
            try { out.add(getObjectInfo(h)); } catch (IOException ignored) {}
        }
        return out;
    }

    private static int[] u32Array(byte[] data) {
        int n = data.length / 4;
        int[] a = new int[n];
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) a[i] = b.getInt();
        return a;
    }

    static String str(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
