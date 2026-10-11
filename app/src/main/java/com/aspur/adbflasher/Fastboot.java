package com.aspur.adbflasher;

import android.hardware.usb.*;
import java.io.*;

class Fastboot {
    interface Sink { void log(String s); }

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

    void flash(InputStream is, long size, String part, Sink k) throws IOException {
        String r = cmd(String.format("download:%08x", size), 5000, k);
        if (!r.startsWith("DATA")) throw new IOException("Ditolak: " + r);
        byte[] buf = new byte[16384];
        long sent = 0;
        int n, last = -1;
        while ((n = is.read(buf)) > 0) {
            send(buf, n);
            sent += n;
            int pct = (int) (sent * 100 / size);
            if (pct / 10 != last) { last = pct / 10; k.log("Kirim " + pct + "%"); }
        }
        r = resp(60000, k);
        if (!r.startsWith("OKAY")) throw new IOException("Gagal kirim: " + r);
        k.log("Menulis ke " + part + " ...");
        k.log(cmd("flash:" + part, 120000, k));
    }
}
