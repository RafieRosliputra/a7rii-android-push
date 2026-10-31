package com.johorstudio.tether;

/** Checks MD4 and NTLMv2 against the MS-NLMP 4.2.4 test vectors. */
public class NtlmVectorTest {
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static byte[] unhex(String h) { byte[] b = new byte[h.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16); return b; }
    static int fails = 0;
    static void check(String what, String got, String exp) {
        boolean ok = got.equals(exp);
        if (!ok) fails++;
        System.out.println((ok ? "OK   " : "FAIL ") + what + " " + got + (ok ? "" : " expected " + exp));
    }

    public static void main(String[] a) {
        check("MD4('')", hex(Md4.digest(new byte[0])), "31d6cfe0d16ae931b73c59d7e0c089c0");
        check("MD4('abc')", hex(Md4.digest("abc".getBytes())), "a448017aaf21d8525fc10ae87aa6729d");
        check("MD4(long)", hex(Md4.digest("12345678901234567890123456789012345678901234567890123456789012345678901234567890".getBytes())), "e33b4ddc9c38f2199c3e7b164fcc0536");
        check("NT hash", hex(Md4.digest(SmbClient.utf16("Password"))), "a4f49c406510bdcab6824ee7c30fd852");
        check("NTOWFv2", hex(SmbClient.Ntlm.ntowfv2("User", "Password", "Domain")), "0c868a403bfd7a93a3001ef22ef02e3f");

        byte[] ti = unhex("02000c0044006f006d00610069006e0001000c005300650072007600650072000000000000");
        ti = java.util.Arrays.copyOf(ti, ti.length - 0); // includes EOL
        SmbClient.Ntlm.Auth au = SmbClient.Ntlm.build("User", "Password", "Domain", 0xe28a8233,
                unhex("0123456789abcdef"), unhex("aaaaaaaaaaaaaaaa"), 0L, unhex("02000c0044006f006d00610069006e0001000c0053006500720076006500720000000000"));
        byte[] m = au.message;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(m).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int lmLen = b.getShort(12), lmOff = b.getInt(16), ntOff = b.getInt(24);
        check("LMv2", hex(java.util.Arrays.copyOfRange(m, lmOff, lmOff + lmLen)), "86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa");
        check("NTProofStr", hex(java.util.Arrays.copyOfRange(m, ntOff, ntOff + 16)), "68cd0ab851e51c96aabc927bebef6a1c");
        check("SessionBaseKey", hex(au.sessionKey), "8de40ccadbc14a82f15cb0ad0de95ca3");
        System.out.println(fails == 0 ? "SEMUA LULUS" : fails + " GAGAL");
    }
}
