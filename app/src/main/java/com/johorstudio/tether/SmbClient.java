package com.johorstudio.tether;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal SMB 2.0.2 / 2.1 client (direct TCP 445) with NTLMv2 authentication and optional signing.
 * Enough to: connect to a share, list folders, create folders and upload files (TrueNAS / Samba).
 */
public class SmbClient {
    // commands
    private static final int NEGOTIATE = 0, SESSION_SETUP = 1, LOGOFF = 2, TREE_CONNECT = 3,
            CREATE = 5, CLOSE = 6, WRITE = 9, QUERY_DIRECTORY = 0x0E;

    // NT status
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_PENDING = 0x00000103;
    public static final int STATUS_NO_MORE_FILES = 0x80000006;
    public static final int STATUS_MORE_PROCESSING = 0xC0000016;
    public static final int STATUS_LOGON_FAILURE = 0xC000006D;
    public static final int STATUS_ACCESS_DENIED = 0xC0000022;
    public static final int STATUS_BAD_NETWORK_NAME = 0xC00000CC;
    public static final int STATUS_OBJECT_NAME_COLLISION = 0xC0000035;
    public static final int STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034;
    public static final int STATUS_OBJECT_PATH_NOT_FOUND = 0xC000003A;

    public static class SmbException extends IOException {
        public final int status;
        SmbException(String msg, int status) { super(msg + " (" + statusName(status) + ")"); this.status = status; }
    }

    public static String statusName(int s) {
        switch (s) {
            case STATUS_LOGON_FAILURE: return "username/password salah";
            case STATUS_ACCESS_DENIED: return "akses ditolak";
            case STATUS_BAD_NETWORK_NAME: return "nama share tidak wujud";
            case STATUS_OBJECT_NAME_COLLISION: return "nama sudah wujud";
            case STATUS_OBJECT_NAME_NOT_FOUND: return "tidak dijumpai";
            case STATUS_OBJECT_PATH_NOT_FOUND: return "folder tidak dijumpai";
            case 0xC00000BB: return "tidak disokong oleh server";
            default: return String.format("0x%08X", s);
        }
    }

    public interface Logger { void log(String s); }
    public interface Progress { void onProgress(long done, long total); }

    public static class Entry {
        public String name;
        public boolean dir;
        public long size;
    }

    private final String host, share, user, pass, domain;
    private Socket sock;
    private DataInputStream in;
    private OutputStream out;
    private long messageId = 0;
    private int credits = 1;
    private long sessionId = 0;
    private int treeId = 0;
    private int dialect;
    private int maxWrite = 65536;
    private boolean signing = false;
    private byte[] sessionKey;
    private Logger logger;
    private final SecureRandom rnd = new SecureRandom();

    public SmbClient(String host, String share, String user, String pass, String domain) {
        this.host = host.trim();
        this.share = share.trim().replace("/", "").replace("\\", "");
        this.user = user.trim();
        this.pass = pass;
        this.domain = domain == null ? "" : domain.trim();
    }

    public void setLogger(Logger l) { logger = l; }
    private void dbg(String s) { if (logger != null) logger.log(s); }

    public boolean isConnected() { return sock != null && sock.isConnected() && !sock.isClosed() && treeId != 0; }

    // ------------------------------------------------------------------ connect

    public void connect() throws IOException {
        close();
        sock = new Socket();
        String h = host;
        int port = 445;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) { // "host:port" (not IPv6)
            h = host.substring(0, colon);
            port = Integer.parseInt(host.substring(colon + 1));
        }
        sock.connect(new InetSocketAddress(h, port), 8000);
        sock.setSoTimeout(30000);
        sock.setTcpNoDelay(true);
        in = new DataInputStream(sock.getInputStream());
        out = sock.getOutputStream();
        messageId = 0; credits = 1; sessionId = 0; treeId = 0; signing = false; sessionKey = null;

        negotiate();
        sessionSetup();
        treeConnect();
        dbg(String.format(Locale.US, "SMB 0x%03X bersambung ke \\\\%s\\%s%s", dialect, host, share,
                signing ? " (signed)" : ""));
    }

    public void close() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        sock = null; treeId = 0;
    }

    private void negotiate() throws IOException {
        ByteBuffer b = body(36 + 4);
        b.putShort((short) 36);      // StructureSize
        b.putShort((short) 2);       // DialectCount
        b.putShort((short) 1);       // SecurityMode: signing enabled
        b.putShort((short) 0);
        b.putInt(0);                 // Capabilities
        byte[] guid = new byte[16]; rnd.nextBytes(guid); b.put(guid);
        b.putLong(0);                // ClientStartTime
        b.putShort((short) 0x0202);
        b.putShort((short) 0x0210);
        Resp r = call(NEGOTIATE, b, 1);
        if (r.status != STATUS_SUCCESS) throw new SmbException("Negotiate gagal", r.status);
        ByteBuffer p = r.body();
        int secMode = p.getShort(2) & 0xFFFF;
        dialect = p.getShort(4) & 0xFFFF;
        maxWrite = p.getInt(36);
        if (dialect == 0x02FF) throw new IOException("Server mahu SMB 3 sahaja (aktifkan SMB2 di TrueNAS)");
        signing = (secMode & 0x02) != 0;
        dbg(String.format(Locale.US, "Negotiate: dialect 0x%04X, maxWrite %d, signing %s", dialect, maxWrite,
                signing ? "wajib" : "tidak wajib"));
    }

    private void sessionSetup() throws IOException {
        byte[] type1 = Ntlm.negotiateMessage();
        Resp r = sessionSetupCall(Spnego.init(type1));
        if (r.status != STATUS_MORE_PROCESSING) throw new SmbException("Session setup (1) gagal", r.status);
        sessionId = r.sessionId;
        byte[] secBuf = r.secBuffer();
        byte[] type2 = Spnego.findNtlm(secBuf);
        if (type2 == null) throw new IOException("Server tidak hantar cabaran NTLM");

        Ntlm.Auth auth = Ntlm.authenticate(type2, user, pass, domain, rnd);
        Resp r2 = sessionSetupCall(Spnego.response(auth.message));
        if (r2.status != STATUS_SUCCESS) throw new SmbException("Login gagal", r2.status);
        int flags = r2.body().getShort(2) & 0xFFFF;
        if ((flags & 0x0001) != 0) dbg("Amaran: login sebagai guest");
        sessionKey = auth.sessionKey;
        if ((flags & 0x0003) != 0) signing = false; // guest / anonymous sessions are never signed
    }

    private Resp sessionSetupCall(byte[] token) throws IOException {
        ByteBuffer b = body(24 + token.length);
        b.putShort((short) 25);
        b.put((byte) 0);                  // Flags
        b.put((byte) 1);                  // SecurityMode: signing enabled
        b.putInt(0);                      // Capabilities
        b.putInt(0);                      // Channel
        b.putShort((short) (64 + 24));    // SecurityBufferOffset
        b.putShort((short) token.length);
        b.putLong(0);                     // PreviousSessionId
        b.put(token);
        return call(SESSION_SETUP, b, 1);
    }

    private void treeConnect() throws IOException {
        String hostOnly = host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') > 0 ? host.substring(0, host.indexOf(':')) : host;
        byte[] path = utf16("\\\\" + hostOnly + "\\" + share);
        ByteBuffer b = body(8 + path.length);
        b.putShort((short) 9);
        b.putShort((short) 0);
        b.putShort((short) (64 + 8));
        b.putShort((short) path.length);
        b.put(path);
        Resp r = call(TREE_CONNECT, b, 1);
        if (r.status != STATUS_SUCCESS) throw new SmbException("Tidak dapat buka share '" + share + "'", r.status);
        treeId = r.treeId;
    }

    // ------------------------------------------------------------------ file ops

    private static String smbPath(String path) {
        String p = path == null ? "" : path.replace('/', '\\');
        while (p.startsWith("\\")) p = p.substring(1);
        while (p.endsWith("\\")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String join(String dir, String name) {
        String d = smbPath(dir);
        return d.isEmpty() ? name : d + "\\" + name;
    }

    /** CREATE; returns FileId (16 bytes). */
    private byte[] create(String path, int access, int attrs, int shareAccess, int disposition, int options)
            throws IOException {
        byte[] name = utf16(smbPath(path));
        byte[] nameBuf = name.length == 0 ? new byte[2] : name;
        ByteBuffer b = body(56 + nameBuf.length);
        b.putShort((short) 57);
        b.put((byte) 0);              // SecurityFlags
        b.put((byte) 0);              // RequestedOplockLevel: none
        b.putInt(2);                  // ImpersonationLevel: Impersonation
        b.putLong(0);                 // SmbCreateFlags
        b.putLong(0);                 // Reserved
        b.putInt(access);
        b.putInt(attrs);
        b.putInt(shareAccess);
        b.putInt(disposition);
        b.putInt(options);
        b.putShort((short) (64 + 56));
        b.putShort((short) name.length);
        b.putInt(0);                  // CreateContextsOffset
        b.putInt(0);                  // CreateContextsLength
        b.put(nameBuf);
        Resp r = call(CREATE, b, 1);
        if (r.status != STATUS_SUCCESS) throw new SmbException("Create '" + path + "'", r.status);
        byte[] fid = new byte[16];
        System.arraycopy(r.data, 64 + 64, fid, 0, 16);
        return fid;
    }

    private void closeFile(byte[] fid) throws IOException {
        ByteBuffer b = body(24);
        b.putShort((short) 24);
        b.putShort((short) 0);
        b.putInt(0);
        b.put(fid);
        call(CLOSE, b, 1);
    }

    /** List sub-entries of a folder ("" = share root). */
    public List<Entry> list(String dir) throws IOException {
        byte[] fid = create(dir, 0x00100081, 0, 7, 1 /*OPEN*/, 0x1 /*DIRECTORY_FILE*/);
        List<Entry> res = new ArrayList<>();
        try {
            boolean first = true;
            while (true) {
                byte[] pat = utf16("*");
                ByteBuffer b = body(32 + pat.length);
                b.putShort((short) 33);
                b.put((byte) 0x01);                 // FileDirectoryInformation
                b.put((byte) (first ? 0x01 : 0));  // RESTART_SCANS
                b.putInt(0);
                b.put(fid);
                b.putShort((short) (64 + 32));
                b.putShort((short) pat.length);
                b.putInt(65536);
                b.put(pat);
                first = false;
                Resp r = call(QUERY_DIRECTORY, b, 1);
                if (r.status == STATUS_NO_MORE_FILES) break;
                if (r.status != STATUS_SUCCESS) throw new SmbException("Senarai folder gagal", r.status);
                ByteBuffer p = r.body();
                int off = (p.getShort(2) & 0xFFFF);
                int len = p.getInt(4);
                ByteBuffer e = ByteBuffer.wrap(r.data, off, len).order(ByteOrder.LITTLE_ENDIAN);
                int pos = off;
                while (true) {
                    int next = e.getInt(pos);
                    long size = e.getLong(pos + 40);
                    int attr = e.getInt(pos + 56);
                    int nlen = e.getInt(pos + 60);
                    String nm = new String(r.data, pos + 64, nlen, StandardCharsets.UTF_16LE);
                    if (!nm.equals(".") && !nm.equals("..")) {
                        Entry en = new Entry();
                        en.name = nm; en.dir = (attr & 0x10) != 0; en.size = size;
                        res.add(en);
                    }
                    if (next == 0) break;
                    pos += next;
                }
            }
        } finally {
            closeFile(fid);
        }
        return res;
    }

    /** Create a folder. Returns true if created, false if it already existed. */
    public boolean mkdir(String path) throws IOException {
        try {
            byte[] fid = create(path, 0x00100081, 0x10, 7, 2 /*CREATE*/, 0x1);
            closeFile(fid);
            return true;
        } catch (SmbException e) {
            if (e.status == STATUS_OBJECT_NAME_COLLISION) return false;
            throw e;
        }
    }

    /** Create every folder along path (like mkdir -p). */
    public void mkdirs(String path) throws IOException {
        String p = smbPath(path);
        if (p.isEmpty()) return;
        String cur = "";
        for (String part : p.split("\\\\")) {
            if (part.isEmpty()) continue;
            cur = cur.isEmpty() ? part : cur + "\\" + part;
            mkdir(cur);
        }
    }

    /**
     * Upload a stream as dir/name. Never overwrites: on collision tries "name (1).ext", "name (2).ext"...
     * @return final file name used on the server
     */
    public String upload(String dir, String name, InputStream src, long size, Progress prog) throws IOException {
        byte[] fid = null;
        String finalName = name;
        for (int i = 0; i < 50 && fid == null; i++) {
            finalName = i == 0 ? name : withSuffix(name, i);
            try {
                // write data | append | write EA | read/write attributes | read control | synchronize
                fid = create(join(dir, finalName), 0x00120196, 0x80, 1, 2 /*CREATE*/, 0x40 /*NON_DIRECTORY*/);
            } catch (SmbException e) {
                if (e.status != STATUS_OBJECT_NAME_COLLISION) throw e;
            }
        }
        if (fid == null) throw new IOException("Terlalu banyak fail sama nama: " + name);

        boolean ok = false;
        try {
            int chunk = Math.min(Math.max(maxWrite, 65536), dialect >= 0x0210 ? 1024 * 1024 : 65536);
            byte[] buf = new byte[chunk];
            long offset = 0;
            while (true) {
                int n = readFully(src, buf);
                if (n <= 0) break;
                write(fid, offset, buf, n);
                offset += n;
                if (prog != null) prog.onProgress(offset, size);
                if (n < buf.length) break;
            }
            ok = true;
        } finally {
            try { closeFile(fid); } catch (Exception ignored) {}
            if (!ok) {
                try { deleteFile(join(dir, finalName)); } catch (Exception ignored) {}
            }
        }
        return finalName;
    }

    private static int readFully(InputStream s, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = s.read(buf, got, buf.length - got);
            if (n < 0) break;
            got += n;
        }
        return got;
    }

    private static String withSuffix(String name, int i) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + " (" + i + ")" + name.substring(dot) : name + " (" + i + ")";
    }

    private void write(byte[] fid, long offset, byte[] data, int len) throws IOException {
        ByteBuffer b = body(48 + len);
        b.putShort((short) 49);
        b.putShort((short) (64 + 48));   // DataOffset
        b.putInt(len);
        b.putLong(offset);
        b.put(fid);
        b.putInt(0);                     // Channel
        b.putInt(0);                     // RemainingBytes
        b.putShort((short) 0);           // WriteChannelInfoOffset
        b.putShort((short) 0);           // WriteChannelInfoLength
        b.putInt(0);                     // Flags
        b.put(data, 0, len);
        int charge = dialect >= 0x0210 ? Math.max(1, (len - 1) / 65536 + 1) : 1;
        Resp r = call(WRITE, b, charge);
        if (r.status != STATUS_SUCCESS) throw new SmbException("Tulis fail gagal", r.status);
    }

    private void deleteFile(String path) throws IOException {
        // DELETE access + FILE_DELETE_ON_CLOSE
        byte[] fid = create(path, 0x00010000, 0, 7, 1, 0x1040);
        closeFile(fid);
    }

    // ------------------------------------------------------------------ transport

    private static ByteBuffer body(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private class Resp {
        int status, command, treeId;
        long sessionId;
        byte[] data; // full SMB2 message (header + body)
        ByteBuffer body() { return ByteBuffer.wrap(data, 64, data.length - 64).slice().order(ByteOrder.LITTLE_ENDIAN); }
        byte[] secBuffer() {
            ByteBuffer p = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int off = p.getShort(64 + 4) & 0xFFFF, len = p.getShort(64 + 6) & 0xFFFF;
            return Arrays.copyOfRange(data, off, off + len);
        }
    }

    private synchronized Resp call(int cmd, ByteBuffer bodyBuf, int creditCharge) throws IOException {
        if (sock == null) throw new IOException("Tidak bersambung ke NAS");
        if (dialect < 0x0210) creditCharge = 1;
        byte[] bodyBytes = bodyBuf.array();
        byte[] msg = new byte[64 + bodyBytes.length];
        ByteBuffer h = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN);
        long mid = messageId;
        h.put(new byte[]{(byte) 0xFE, 'S', 'M', 'B'});
        h.putShort((short) 64);
        h.putShort((short) (cmd == NEGOTIATE ? 0 : creditCharge));
        h.putInt(0);                         // Status / ChannelSequence
        h.putShort((short) cmd);
        h.putShort((short) 256);             // CreditRequest
        boolean sign = signing && sessionKey != null && cmd != NEGOTIATE && cmd != SESSION_SETUP;
        h.putInt(sign ? 0x08 : 0);           // Flags
        h.putInt(0);                         // NextCommand
        h.putLong(mid);
        h.putInt(0xFEFF);                    // Reserved (ProcessId)
        h.putInt(cmd == TREE_CONNECT || cmd == NEGOTIATE || cmd == SESSION_SETUP ? 0 : treeId);
        h.putLong(sessionId);
        System.arraycopy(bodyBytes, 0, msg, 64, bodyBytes.length);
        if (sign) {
            byte[] sig = hmacSha256(sessionKey, msg);
            System.arraycopy(sig, 0, msg, 48, 16);
        }
        messageId += Math.max(1, creditCharge);

        // NetBIOS session header: 0x00 + 24-bit length
        byte[] nb = new byte[4];
        nb[1] = (byte) (msg.length >>> 16); nb[2] = (byte) (msg.length >>> 8); nb[3] = (byte) msg.length;
        out.write(nb);
        out.write(msg);
        out.flush();

        while (true) {
            int hdr = in.readInt();
            int len = hdr & 0x00FFFFFF;
            byte[] data = new byte[len];
            in.readFully(data);
            ByteBuffer rh = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (len < 64 || rh.getInt(0) != 0x424D53FE) throw new IOException("Respons SMB tidak sah");
            Resp r = new Resp();
            r.data = data;
            r.status = rh.getInt(8);
            r.command = rh.getShort(12) & 0xFFFF;
            int granted = rh.getShort(14) & 0xFFFF;
            int flags = rh.getInt(16);
            long rmid = rh.getLong(24);
            credits += granted;
            if (r.status == STATUS_PENDING && (flags & 0x02) != 0) continue; // async interim response
            if (rmid != mid) { dbg("SMB: abaikan respons mid " + rmid); continue; }
            if ((flags & 0x02) != 0) { r.treeId = 0; } else { r.treeId = rh.getInt(36); }
            r.sessionId = rh.getLong(40);
            return r;
        }
    }

    // ------------------------------------------------------------------ helpers

    static byte[] utf16(String s) { return s.getBytes(StandardCharsets.UTF_16LE); }

    static byte[] hmacMd5(byte[] key, byte[]... parts) {
        return hmac("HmacMD5", key, parts);
    }

    static byte[] hmacSha256(byte[] key, byte[]... parts) {
        return hmac("HmacSHA256", key, parts);
    }

    private static byte[] hmac(String alg, byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(key, alg));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ================================================================== NTLM

    static final class Ntlm {
        static final int F_UNICODE = 0x1, F_REQUEST_TARGET = 0x4, F_SIGN = 0x10, F_NTLM = 0x200,
                F_ALWAYS_SIGN = 0x8000, F_EXT_SESSION = 0x80000, F_TARGET_INFO = 0x800000,
                F_128 = 0x20000000, F_56 = 0x80000000;
        static final int FLAGS = F_UNICODE | F_REQUEST_TARGET | F_SIGN | F_NTLM | F_ALWAYS_SIGN
                | F_EXT_SESSION | F_TARGET_INFO | F_128 | F_56;
        static final byte[] SIG = {'N', 'T', 'L', 'M', 'S', 'S', 'P', 0};

        static class Auth { byte[] message, sessionKey; }

        static byte[] negotiateMessage() {
            ByteBuffer b = body(32);
            b.put(SIG).putInt(1).putInt(FLAGS);
            b.putShort((short) 0).putShort((short) 0).putInt(32); // domain
            b.putShort((short) 0).putShort((short) 0).putInt(32); // workstation
            return b.array();
        }

        static byte[] ntowfv2(String user, String pass, String domain) {
            byte[] nt = Md4.digest(utf16(pass));
            return hmacMd5(nt, utf16(user.toUpperCase(Locale.ROOT) + domain));
        }

        static Auth authenticate(byte[] challenge, String user, String pass, String domain, SecureRandom rnd)
                throws IOException {
            ByteBuffer c = ByteBuffer.wrap(challenge).order(ByteOrder.LITTLE_ENDIAN);
            if (challenge.length < 48 || c.getInt(8) != 2) throw new IOException("Mesej cabaran NTLM tidak sah");
            int srvFlags = c.getInt(20);
            byte[] serverChallenge = Arrays.copyOfRange(challenge, 24, 32);
            int tiLen = c.getShort(40) & 0xFFFF, tiOff = c.getInt(44);
            byte[] targetInfo = tiLen > 0 ? Arrays.copyOfRange(challenge, tiOff, tiOff + tiLen) : new byte[0];

            // Timestamp: use the server's MsvAvTimestamp if present, else now (FILETIME)
            long ts = (System.currentTimeMillis() + 11644473600000L) * 10000L;
            ByteBuffer ti = ByteBuffer.wrap(targetInfo).order(ByteOrder.LITTLE_ENDIAN);
            for (int p = 0; p + 4 <= targetInfo.length; ) {
                int id = ti.getShort(p) & 0xFFFF, l = ti.getShort(p + 2) & 0xFFFF;
                if (id == 0) break;
                if (id == 7 && l == 8) ts = ti.getLong(p + 4);
                p += 4 + l;
            }
            byte[] clientChallenge = new byte[8];
            rnd.nextBytes(clientChallenge);
            return build(user, pass, domain, srvFlags, serverChallenge, clientChallenge, ts, targetInfo);
        }

        static Auth build(String user, String pass, String domain, int srvFlags, byte[] serverChallenge,
                          byte[] clientChallenge, long ts, byte[] targetInfo) {
            byte[] key = ntowfv2(user, pass, domain);

            ByteBuffer temp = body(28 + targetInfo.length + 4);
            temp.put((byte) 1).put((byte) 1).putShort((short) 0).putInt(0);
            temp.putLong(ts).put(clientChallenge).putInt(0).put(targetInfo).putInt(0);
            byte[] tmp = temp.array();

            byte[] ntProof = hmacMd5(key, serverChallenge, tmp);
            byte[] ntResp = new byte[16 + tmp.length];
            System.arraycopy(ntProof, 0, ntResp, 0, 16);
            System.arraycopy(tmp, 0, ntResp, 16, tmp.length);
            byte[] lmResp = new byte[24];
            System.arraycopy(hmacMd5(key, serverChallenge, clientChallenge), 0, lmResp, 0, 16);
            System.arraycopy(clientChallenge, 0, lmResp, 16, 8);

            Auth a = new Auth();
            a.sessionKey = hmacMd5(key, ntProof);

            byte[] dom = utf16(domain), usr = utf16(user), ws = utf16("JSTETHER");
            int flags = FLAGS & (srvFlags | F_UNICODE | F_NTLM);
            int off = 64;
            ByteBuffer m = body(64 + lmResp.length + ntResp.length + dom.length + usr.length + ws.length);
            m.put(SIG).putInt(3);
            off = field(m, lmResp.length, off);
            off = field(m, ntResp.length, off);
            off = field(m, dom.length, off);
            off = field(m, usr.length, off);
            off = field(m, ws.length, off);
            field(m, 0, off);                 // EncryptedRandomSessionKey (none)
            m.putInt(flags);
            m.put(lmResp).put(ntResp).put(dom).put(usr).put(ws);
            a.message = m.array();
            return a;
        }

        private static int field(ByteBuffer b, int len, int off) {
            b.putShort((short) len).putShort((short) len).putInt(off);
            return off + len;
        }
    }

    // ================================================================== SPNEGO (minimal DER)

    static final class Spnego {
        private static final byte[] OID_SPNEGO = {0x06, 0x06, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x02};
        private static final byte[] OID_NTLM = {0x06, 0x0a, 0x2b, 0x06, 0x01, 0x04, 0x01, (byte) 0x82, 0x37, 0x02, 0x02, 0x0a};

        static byte[] tlv(int tag, byte[]... parts) {
            int len = 0;
            for (byte[] p : parts) len += p.length;
            byte[] l;
            if (len < 0x80) l = new byte[]{(byte) len};
            else if (len < 0x100) l = new byte[]{(byte) 0x81, (byte) len};
            else l = new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) len};
            byte[] out = new byte[1 + l.length + len];
            out[0] = (byte) tag;
            System.arraycopy(l, 0, out, 1, l.length);
            int p = 1 + l.length;
            for (byte[] x : parts) { System.arraycopy(x, 0, out, p, x.length); p += x.length; }
            return out;
        }

        static byte[] init(byte[] ntlm) {
            byte[] mechTypes = tlv(0xa0, tlv(0x30, OID_NTLM));
            byte[] mechToken = tlv(0xa2, tlv(0x04, ntlm));
            byte[] negTokenInit = tlv(0xa0, tlv(0x30, mechTypes, mechToken));
            return tlv(0x60, OID_SPNEGO, negTokenInit);
        }

        static byte[] response(byte[] ntlm) {
            return tlv(0xa1, tlv(0x30, tlv(0xa2, tlv(0x04, ntlm))));
        }

        /** Find the raw NTLMSSP message inside a SPNEGO blob. */
        static byte[] findNtlm(byte[] blob) {
            outer:
            for (int i = 0; i + 8 <= blob.length; i++) {
                for (int j = 0; j < 8; j++) if (blob[i + j] != Ntlm.SIG[j]) continue outer;
                return Arrays.copyOfRange(blob, i, blob.length);
            }
            return null;
        }
    }
}
