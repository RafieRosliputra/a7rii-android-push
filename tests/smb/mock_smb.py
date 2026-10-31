"""Tiny mock SMB2 server (spec layouts) to exercise SmbClient framing/parsing. Not a real server."""
import socket, struct, sys, os

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 4445
ti = (struct.pack("<HH", 2, 12) + "Domain".encode("utf-16le") + struct.pack("<HH", 1, 12)
      + "Server".encode("utf-16le") + struct.pack("<HH", 0, 0))
NTLM_CHALLENGE = (b"NTLMSSP\0" + struct.pack("<I", 2) + struct.pack("<HHI", 0, 0, 48)
                  + struct.pack("<I", 0xe28a8235) + bytes.fromhex("0123456789abcdef") + b"\0" * 8
                  + struct.pack("<HHI", len(ti), len(ti), 48) + ti)

fs = {"": {"dir": True}, "Existing": {"dir": True}, "Existing/old.jpg": {"dir": False, "data": b"x" * 10}}
fids = {}

def hdr(req, status, cmd, tree=0, sess=0x1234):
    mid = struct.unpack_from("<Q", req, 24)[0]
    return (b"\xfeSMB" + struct.pack("<HHIHHIIQIIQ", 64, 0, status, cmd, 64, 1, 0, mid, 0, tree, sess) + b"\0" * 16)

def send(c, msg):
    c.sendall(struct.pack(">I", len(msg)) + msg)

def recv_exact(c, n):
    b = b""
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x: raise EOFError
        b += x
    return b

def handle(c):
    stage = 0
    while True:
        try: n = struct.unpack(">I", recv_exact(c, 4))[0] & 0xFFFFFF
        except EOFError: return
        m = recv_exact(c, n)
        assert m[:4] == b"\xfeSMB", "bad magic"
        cmd = struct.unpack_from("<H", m, 12)[0]
        b = m[64:]
        if cmd == 0:
            dial = struct.unpack_from("<H", b, 36)[0]
            body = struct.pack("<HHHH16sIIIIQQHHI", 65, 1, 0x0210, 0, b"G" * 16, 0, 8388608, 8388608, 8388608, 0, 0, 128, 0, 0)
            send(c, hdr(m, 0, 0, sess=0) + body + b"\0")
        elif cmd == 1:
            off, ln = struct.unpack_from("<HH", b, 12)
            tok = m[off:off + ln]
            i = tok.find(b"NTLMSSP\0")
            t = struct.unpack_from("<I", tok, i + 8)[0]
            if t == 1:
                blob = b"\xa1\x81" + bytes([len(NTLM_CHALLENGE) + 8]) + b"\x30" + bytes([0x81, len(NTLM_CHALLENGE)+5]) + b"\xa2\x81" + bytes([len(NTLM_CHALLENGE)+3]) + b"\x04\x81" + bytes([len(NTLM_CHALLENGE)]) + NTLM_CHALLENGE
                body = struct.pack("<HHHH", 9, 0, 72, len(blob)) + blob
                send(c, hdr(m, 0xC0000016, 1) + body)
            else:
                a = tok[i:]
                ul, _, uo = struct.unpack_from("<HHI", a, 36)
                print("  auth user:", a[uo:uo+ul].decode("utf-16le"), "ntlen", struct.unpack_from("<H", a, 20)[0])
                send(c, hdr(m, 0, 1) + struct.pack("<HHHH", 9, 0, 0, 0))
        elif cmd == 3:
            po, pl = struct.unpack_from("<HH", b, 4)
            print("  tree:", m[po:po+pl].decode("utf-16le"))
            send(c, hdr(m, 0, 3, tree=7) + struct.pack("<HBBIII", 16, 1, 0, 0, 0, 0x1F01FF))
        elif cmd == 5:
            disp, opts = struct.unpack_from("<II", b, 36)
            no, nl = struct.unpack_from("<HH", b, 44)
            name = m[no:no+nl].decode("utf-16le").replace("\\", "/")
            isdir = bool(opts & 1)
            status = 0
            if disp == 1:
                if name not in fs: status = 0xC0000034
            elif disp == 2:
                if name in fs: status = 0xC0000035
                else: fs[name] = {"dir": isdir, "data": b""}
            print("  create", repr(name), "disp", disp, "->", hex(status))
            if status:
                send(c, hdr(m, status, 5, tree=7) + struct.pack("<HBBI", 9, 0, 0, 0)); continue
            fid = os.urandom(16); fids[fid] = name
            body = struct.pack("<HBBI", 89, 0, 0, 2) + b"\0" * 48 + struct.pack("<II", 0x10 if fs[name]["dir"] else 0x80, 0) + fid + struct.pack("<II", 0, 0)
            send(c, hdr(m, 0, 5, tree=7) + body)
        elif cmd == 6:
            fid = b[8:24]
            name = fids.pop(fid, None)
            if name and (struct.unpack_from("<I", b, 4)[0] == 0) and False: pass
            send(c, hdr(m, 0, 6, tree=7) + struct.pack("<HHI", 60, 0, 0) + b"\0" * 52)
        elif cmd == 9:
            doff, ln, off = struct.unpack_from("<HIQ", b, 2)
            fid = b[16:32]
            name = fids[fid]
            data = m[doff:doff+ln]
            cc = struct.unpack_from("<H", m, 6)[0]
            d = fs[name]["data"]
            fs[name]["data"] = d[:off] + data
            send(c, hdr(m, 0, 9, tree=7) + struct.pack("<HHIII", 17, 0, ln, 0, 0) + b"\0")
        elif cmd == 0x0E:
            flags = b[3]
            fid = b[8:24]
            d = fids[fid]
            if not (flags & 1):
                send(c, hdr(m, 0x80000006, 0x0E, tree=7) + struct.pack("<HHI", 9, 0, 0) + b"\0"); continue
            kids = [k for k in fs if k != d and k.rsplit("/", 1)[0] == d] if d else [k for k in fs if k and "/" not in k]
            names = [".", ".."] + [k.rsplit("/", 1)[-1] for k in kids]
            entries = b""
            for i, nm in enumerate(names):
                full = (d + "/" + nm) if d else nm
                isdir = nm in (".", "..") or fs.get(full, {}).get("dir", False)
                nb = nm.encode("utf-16le")
                e = struct.pack("<II", 0, 0) + b"\0" * 32 + struct.pack("<QQII", len(fs.get(full, {}).get("data", b"")), 0, 0x10 if isdir else 0x80, len(nb)) + nb
                e += b"\0" * ((8 - len(e) % 8) % 8)
                if i < len(names) - 1: e = struct.pack("<I", len(e)) + e[4:]
                entries += e
            send(c, hdr(m, 0, 0x0E, tree=7) + struct.pack("<HHI", 9, 72, len(entries)) + entries)
        else:
            print("  unhandled cmd", cmd); return

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", PORT)); s.listen(5)
print("mock ready", flush=True)
while True:
    c, _ = s.accept(); handle(c); c.close()
    for k, v in fs.items(): print("  FS", repr(k), "dir" if v["dir"] else len(v["data"]))
    sys.stdout.flush()
