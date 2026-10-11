package com.aspur.adbflasher;

import android.hardware.usb.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

class Fastboot {
    interface Sink { void log(String s); }
    interface Opener { InputStream open() throws IOException; }

    final UsbDeviceConnection c;
    UsbEndpoint in, out;

    static boolean isFastboot(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 255 && f.getInterfaceSubclass() == 66 && f.getInterfaceProtocol() == 3) return true;
        }
        return false;
    }

    Fastboot(UsbManager m, UsbDevice d) throws IOException {
        UsbInterface it = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 255 && f.getInterfaceSubclass() == 66 && f.getInterfaceProtocol() == 3) it = f;
        }
        if (it == null) throw new IOException("Bukan perangkat fastboot");
        c = m.openDevice(d);
        if (c == null) throw new IOException("Gagal membuka perangkat USB");
        c.claimInterface(it, true);
        for (int i = 0; i < it.getEndpointCount(); i++) {
            UsbEndpoint e = it.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e; else out = e;
            }
        }
        if (in == null || out == null) throw new IOException("Endpoint tidak ditemukan");
    }

    void send(byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = c.bulkTransfer(out, b, off, len - off, 5000);
            if (n <= 0) throw new IOException("Gagal kirim data USB");
            off += n;
        }
    }

    String resp(int timeout, Sink k) throws IOException {
        for (;;) {
            byte[] r = new byte[64];
            int n = c.bulkTransfer(in, r, 64, timeout);
            if (n < 0) throw new IOException("Timeout menunggu respons");
            String t = new String(r, 0, n);
            if (t.startsWith("INFO")) k.log(t.substring(4)); else return t;
        }
    }

    String cmd(String s, int timeout, Sink k) throws IOException {
        byte[] b = s.getBytes();
        send(b, b.length);
        return resp(timeout, k);
    }

    String getvar(String n, Sink k) throws IOException {
        String r = cmd("getvar:" + n, 5000, k);
        return r.startsWith("OKAY") ? r.substring(4) : r;
    }

    long maxDownload(Sink k) {
        try {
            String v = getvar("max-download-size", k).trim();
            if (v.startsWith("0x") || v.startsWith("0X")) v = v.substring(2);
            return Long.parseLong(v, 16);
        } catch (Exception e) {
            return 0x2000000L;
        }
    }

    void download(long total, byte[] head, Opener op, long skip, long rawLen, byte[] tail, Sink k) throws IOException {
        String r = cmd(String.format("download:%08x", total), 5000, k);
        if (!r.startsWith("DATA")) throw new IOException("Ditolak: " + r);
        if (head != null) send(head, head.length);
        long sent = 0;
        int last = -1;
        byte[] buf = new byte[16384];
        if (rawLen > 0) {
            try (InputStream is = op.open()) {
                long s = skip;
                while (s > 0) {
                    long n = is.skip(s);
                    if (n <= 0) {
                        if (is.read() < 0) break;
                        n = 1;
                    }
                    s -= n;
                }
                long remain = rawLen;
                boolean eof = false;
                while (remain > 0) {
                    int want = (int) Math.min(buf.length, remain);
                    int n = eof ? -1 : is.read(buf, 0, want);
                    if (n <= 0) {
                        eof = true;
                        Arrays.fill(buf, 0, want, (byte) 0);
                        n = want;
                    }
                    send(buf, n);
                    remain -= n;
                    sent += n;
                    int pct = (int) (sent * 100 / rawLen);
                    if (pct / 10 != last) { last = pct / 10; k.log("Kirim " + pct + "%"); }
                }
            }
        }
        if (tail != null) send(tail, tail.length);
        r = resp(120000, k);
        if (!r.startsWith("OKAY")) throw new IOException("Gagal kirim: " + r);
    }

    void flash(Opener op, long size, String part, Sink k) throws IOException {
        byte[] m4 = new byte[4];
        try (InputStream is = op.open()) { is.read(m4); }
        boolean sparse = (m4[0] & 0xff) == 0x3a && (m4[1] & 0xff) == 0xff && (m4[2] & 0xff) == 0x26 && (m4[3] & 0xff) == 0xed;
        long max = maxDownload(k);
        k.log("max-download-size: " + max + " byte");
        if (size <= max) {
            download(size, null, op, 0, size, null, k);
            k.log("Menulis ke " + part + " ...");
            k.log(cmd("flash:" + part, 180000, k));
            return;
        }
        if (sparse) throw new IOException("File sparse lebih besar dari batas download perangkat");
        final int BS = 4096;
        long total = (size + BS - 1) / BS;
        long perPiece = (max - 4096) / BS;
        if (perPiece < 1) throw new IOException("Batas download terlalu kecil");
        int pieces = (int) ((total + perPiece - 1) / perPiece);
        k.log("File dipecah " + pieces + " bagian (sparse)");
        for (int p = 0; p < pieces; p++) {
            long sb = p * perPiece;
            long nb = Math.min(perPiece, total - sb);
            long rest = total - sb - nb;
            int chunks = 1 + (sb > 0 ? 1 : 0) + (rest > 0 ? 1 : 0);
            ByteBuffer h = ByteBuffer.allocate(28 + (sb > 0 ? 12 : 0) + 12).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(0xED26FF3A);
            h.putShort((short) 1);
            h.putShort((short) 0);
            h.putShort((short) 28);
            h.putShort((short) 12);
            h.putInt(BS);
            h.putInt((int) total);
            h.putInt(chunks);
            h.putInt(0);
            if (sb > 0) {
                h.putShort((short) 0xCAC3);
                h.putShort((short) 0);
                h.putInt((int) sb);
                h.putInt(12);
            }
            h.putShort((short) 0xCAC1);
            h.putShort((short) 0);
            h.putInt((int) nb);
            h.putInt((int) (12 + nb * BS));
            byte[] tail = null;
            if (rest > 0) {
                ByteBuffer t = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                t.putShort((short) 0xCAC3);
                t.putShort((short) 0);
                t.putInt((int) rest);
                t.putInt(12);
                tail = t.array();
            }
            byte[] head = h.array();
            long totalBytes = head.length + nb * BS + (tail == null ? 0 : tail.length);
            k.log("Bagian " + (p + 1) + "/" + pieces);
            download(totalBytes, head, op, sb * BS, nb * BS, tail, k);
            String r = cmd("flash:" + part, 180000, k);
            k.log(r);
            if (!r.startsWith("OKAY")) throw new IOException("Flash gagal: " + r);
        }
    }

    void boot(Opener op, long size, Sink k) throws IOException {
        download(size, null, op, 0, size, null, k);
        k.log(cmd("boot", 60000, k));
    }
}
