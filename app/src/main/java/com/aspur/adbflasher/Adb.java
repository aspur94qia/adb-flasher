package com.aspur.adbflasher;

import android.hardware.usb.*;
import android.util.Base64;
import java.io.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.Arrays;

class Adb {
    interface Sink { void log(String s); }

    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;

    static class Msg { int cmd, a0, a1; byte[] data; }

    UsbDeviceConnection uc;
    UsbEndpoint uin, uout;
    Socket sock;
    InputStream sin;
    OutputStream sout;
    int maxData = 4096;
    int local = 0, remote = 0;
    boolean okayFlag, streamClosed = true;
    ArrayDeque<byte[]> rx = new ArrayDeque<>();
    int rxOff = 0;
    String banner = "";

    static boolean isAdb(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 255 && f.getInterfaceSubclass() == 66 && f.getInterfaceProtocol() == 1) return true;
        }
        return false;
    }

    static Adb usb(UsbManager m, UsbDevice d) throws IOException {
        UsbInterface it = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 255 && f.getInterfaceSubclass() == 66 && f.getInterfaceProtocol() == 1) it = f;
        }
        if (it == null) throw new IOException("Bukan perangkat ADB");
        Adb a = new Adb();
        a.uc = m.openDevice(d);
        if (a.uc == null) throw new IOException("Gagal membuka perangkat USB");
        a.uc.claimInterface(it, true);
        for (int i = 0; i < it.getEndpointCount(); i++) {
            UsbEndpoint e = it.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.getDirection() == UsbConstants.USB_DIR_IN) a.uin = e; else a.uout = e;
            }
        }
        if (a.uin == null || a.uout == null) throw new IOException("Endpoint tidak ditemukan");
        return a;
    }

    static Adb tcp(String host, int port) throws IOException {
        Adb a = new Adb();
        a.sock = new Socket();
        a.sock.connect(new InetSocketAddress(host, port), 5000);
        a.sock.setSoTimeout(60000);
        a.sin = a.sock.getInputStream();
        a.sout = a.sock.getOutputStream();
        return a;
    }

    void close() {
        try {
            if (uc != null) uc.close();
            if (sock != null) sock.close();
        } catch (Exception e) { }
    }

    void wr(byte[] b, int len) throws IOException {
        if (sock != null) { sout.write(b, 0, len); sout.flush(); return; }
        int off = 0;
        while (off < len) {
            int n = uc.bulkTransfer(uout, b, off, len - off, 10000);
            if (n <= 0) throw new IOException("Gagal kirim USB");
            off += n;
        }
    }

    void rd(byte[] b, int len, int timeout) throws IOException {
        int off = 0;
        while (off < len) {
            int n;
            if (sock != null) {
                try { n = sin.read(b, off, len - off); }
                catch (SocketTimeoutException e) { throw new IOException("Timeout"); }
                if (n < 0) throw new IOException("Koneksi putus");
            } else {
                n = uc.bulkTransfer(uin, b, off, len - off, timeout);
                if (n < 0) throw new IOException("Timeout USB");
            }
            off += n;
        }
    }

    void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(0).putInt(cmd ^ 0xffffffff);
        wr(h.array(), 24);
        if (len > 0) wr(data, len);
    }

    Msg recv(int timeout) throws IOException {
        byte[] h = new byte[24];
        rd(h, 24, timeout);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.a0 = b.getInt();
        m.a1 = b.getInt();
        int len = b.getInt();
        b.getInt();
        int magic = b.getInt();
        if (magic != (m.cmd ^ 0xffffffff)) throw new IOException("Paket ADB rusak");
        m.data = new byte[len];
        if (len > 0) rd(m.data, len, timeout);
        return m;
    }

    static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (DataInputStream d = new DataInputStream(new FileInputStream(f))) { d.readFully(b); }
        return b;
    }

    static void writeAll(File f, byte[] b) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }

    static KeyPair loadKey(File dir) throws Exception {
        File pr = new File(dir, "adbkey.priv"), pu = new File(dir, "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pr.exists() && pu.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pu))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(pr))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeAll(pr, kp.getPrivate().getEncoded());
        writeAll(pu, kp.getPublic().getEncoded());
        return kp;
    }

    static byte[] sign(KeyPair kp, byte[] token) throws Exception {
        byte[] pre = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] in = new byte[pre.length + token.length];
        System.arraycopy(pre, 0, in, 0, pre.length);
        System.arraycopy(token, 0, in, pre.length, token.length);
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(kp.getPrivate());
        s.update(in);
        return s.sign();
    }

    static byte[] pubKey(KeyPair kp) {
        RSAPublicKey pk = (RSAPublicKey) kp.getPublic();
        BigInteger n = pk.getModulus(), e = pk.getPublicExponent();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        long n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).longValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt((int) n0inv);
        for (int i = 0; i < 64; i++) b.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < 64; i++) b.putInt(rr.shiftRight(32 * i).intValue());
        b.putInt(e.intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " adbflasher@android\0";
        return s.getBytes();
    }

    void connect(File dir, Sink k) throws Exception {
        KeyPair kp = loadKey(dir);
        send(CNXN, 0x01000001, 256 * 1024, "host::\0".getBytes());
        boolean signed = false;
        for (int i = 0; i < 10; i++) {
            Msg m = recv(60000);
            if (m.cmd == CNXN) {
                maxData = m.a1;
                banner = new String(m.data).trim();
                return;
            }
            if (m.cmd == AUTH && m.a0 == 1) {
                if (!signed) {
                    send(AUTH, 2, 0, sign(kp, m.data));
                    signed = true;
                } else {
                    k.log("Setujui izin di layar HP target (centang 'Selalu izinkan') ...");
                    send(AUTH, 3, 0, pubKey(kp));
                }
            }
        }
        throw new IOException("Gagal autentikasi ADB");
    }

    int chunk() { return maxData > 0 ? Math.min(maxData, 65536) : 4096; }

    void fill() throws IOException {
        Msg m = recv(60000);
        if (m.a1 != local) return;
        if (m.cmd == WRTE) {
            if (m.data.length > 0) rx.add(m.data);
            send(OKAY, local, remote, null);
        } else if (m.cmd == OKAY) {
            okayFlag = true;
        } else if (m.cmd == CLSE) {
            streamClosed = true;
        }
    }

    void open(String svc) throws IOException {
        rx.clear();
        rxOff = 0;
        okayFlag = false;
        streamClosed = false;
        local++;
        send(OPEN, local, 0, (svc + "\0").getBytes());
        for (;;) {
            Msg m = recv(60000);
            if (m.a1 != local) continue;
            if (m.cmd == OKAY) { remote = m.a0; return; }
            if (m.cmd == CLSE) { streamClosed = true; throw new IOException("Layanan ditolak: " + svc); }
        }
    }

    void end() {
        try {
            if (!streamClosed) {
                send(CLSE, local, remote, null);
                int guard = 0;
                while (!streamClosed && guard++ < 50) fill();
            }
        } catch (IOException e) { }
    }

    void put(byte[] d, int off, int len) throws IOException {
        int ch = chunk();
        while (len > 0) {
            int n = Math.min(len, ch);
            byte[] p = Arrays.copyOfRange(d, off, off + n);
            okayFlag = false;
            send(WRTE, local, remote, p);
            while (!okayFlag) {
                if (streamClosed) throw new IOException("Koneksi ditutup perangkat");
                fill();
            }
            off += n;
            len -= n;
        }
    }

    void put(byte[] d) throws IOException { put(d, 0, d.length); }

    void get(byte[] dst, int n) throws IOException {
        int got = 0;
        while (got < n) {
            if (rx.isEmpty()) {
                if (streamClosed) throw new IOException("Stream selesai sebelum waktunya");
                fill();
                continue;
            }
            byte[] h = rx.peek();
            int t = Math.min(h.length - rxOff, n - got);
            System.arraycopy(h, rxOff, dst, got, t);
            got += t;
            rxOff += t;
            if (rxOff >= h.length) { rx.poll(); rxOff = 0; }
        }
    }

    String run(String svc) throws IOException {
        open(svc);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (;;) {
            if (!rx.isEmpty()) {
                byte[] h = rx.poll();
                bo.write(h, rxOff, h.length - rxOff);
                rxOff = 0;
            } else if (streamClosed) {
                break;
            } else {
                fill();
            }
        }
        return bo.toString();
    }

    void fire(String svc) {
        try { run(svc); } catch (IOException e) { }
    }

    static byte[] syncPkt(String id, int val, byte[] body) {
        int bl = body == null ? 0 : body.length;
        ByteBuffer b = ByteBuffer.allocate(8 + bl).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.getBytes());
        b.putInt(val);
        if (bl > 0) b.put(body);
        return b.array();
    }

    static int le(byte[] h, int o) {
        return (h[o] & 0xff) | (h[o + 1] & 0xff) << 8 | (h[o + 2] & 0xff) << 16 | (h[o + 3] & 0xff) << 24;
    }

    void push(InputStream is, long size, String path, Sink k) throws IOException {
        open("sync:");
        try {
            byte[] spec = (path + ",33206").getBytes();
            put(syncPkt("SEND", spec.length, spec));
            byte[] buf = new byte[65536];
            int n;
            long sent = 0;
            int last = -1;
            while ((n = is.read(buf)) > 0) {
                byte[] body = n == buf.length ? buf : Arrays.copyOf(buf, n);
                put(syncPkt("DATA", n, body));
                sent += n;
                int pct = size > 0 ? (int) (sent * 100 / size) : 0;
                if (pct / 10 != last) { last = pct / 10; k.log("Push " + pct + "%"); }
            }
            put(syncPkt("DONE", (int) (System.currentTimeMillis() / 1000), null));
            byte[] h = new byte[8];
            get(h, 8);
            String id = new String(h, 0, 4);
            if (!id.equals("OKAY")) {
                int v = le(h, 4);
                byte[] m = new byte[Math.max(v, 0)];
                if (v > 0) get(m, v);
                throw new IOException("Push gagal: " + new String(m));
            }
            put(syncPkt("QUIT", 0, null));
        } finally {
            end();
        }
    }

    long pull(String path, OutputStream os, Sink k) throws IOException {
        open("sync:");
        long total = 0;
        try {
            byte[] p = path.getBytes();
            put(syncPkt("RECV", p.length, p));
            byte[] h = new byte[8];
            byte[] b = new byte[65536];
            for (;;) {
                get(h, 8);
                String id = new String(h, 0, 4);
                int v = le(h, 4);
                if (id.equals("DATA")) {
                    int left = v;
                    while (left > 0) {
                        int n = Math.min(left, b.length);
                        get(b, n);
                        os.write(b, 0, n);
                        left -= n;
                        total += n;
                    }
                } else if (id.equals("DONE")) {
                    break;
                } else if (id.equals("FAIL")) {
                    byte[] m = new byte[Math.max(v, 0)];
                    if (v > 0) get(m, v);
                    throw new IOException("Pull gagal: " + new String(m));
                } else {
                    throw new IOException("Respons sync tidak dikenal: " + id);
                }
            }
            put(syncPkt("QUIT", 0, null));
        } finally {
            end();
        }
        return total;
    }

    void sideload(FileChannel ch, long size, Sink k) throws IOException {
        final int BS = 65536;
        open("sideload-host:" + size + ":" + BS);
        byte[] h = new byte[8];
        int last = -1;
        for (;;) {
            get(h, 8);
            String s = new String(h);
            if (s.equals("DONEDONE")) { k.log("Sideload selesai."); return; }
            int blk;
            try { blk = Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { throw new IOException("Permintaan tidak dikenal: " + s); }
            long off = (long) blk * BS;
            int want = (int) Math.min(BS, size - off);
            if (want <= 0) throw new IOException("Blok di luar file: " + blk);
            byte[] d = new byte[want];
            ByteBuffer bb = ByteBuffer.wrap(d);
            ch.position(off);
            while (bb.hasRemaining()) { if (ch.read(bb) < 0) break; }
            put(d, 0, want);
            int pct = (int) (off * 100 / size);
            if (pct / 10 != last) { last = pct / 10; k.log("Sideload " + pct + "%"); }
        }
    }
}
