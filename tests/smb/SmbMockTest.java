package com.johorstudio.tether;

import java.io.ByteArrayInputStream;
import java.util.List;

public class SmbMockTest {
    public static void main(String[] a) throws Exception {
        SmbClient s = new SmbClient("127.0.0.1:4446", "photos", "rafi", "secret", "");
        s.setLogger(new SmbClient.Logger() { public void log(String m) { System.out.println("LOG " + m); } });
        s.connect();
        for (SmbClient.Entry e : s.list("")) System.out.println("root: " + e.name + (e.dir ? "/" : ""));
        System.out.println("mkdir new: " + s.mkdir("2026-10-31 Konvo"));
        System.out.println("mkdir again: " + s.mkdir("2026-10-31 Konvo"));
        s.mkdirs("A/B/C");
        byte[] data = new byte[3 * 1024 * 1024 + 123];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        String n1 = s.upload("2026-10-31 Konvo", "DSC00001.JPG", new ByteArrayInputStream(data), data.length, null);
        String n2 = s.upload("2026-10-31 Konvo", "DSC00001.JPG", new ByteArrayInputStream(data), data.length, null);
        System.out.println("uploaded as " + n1 + " and " + n2);
        List<SmbClient.Entry> l = s.list("2026-10-31 Konvo");
        for (SmbClient.Entry e : l) System.out.println("konvo: " + e.name + " " + e.size);
        s.close();
    }
}
