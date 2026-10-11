package com.aspur.adbflasher;

import android.net.Uri;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class Extra3 {
    static void build(MainActivity m, LinearLayout t) {
        Ui u = m.u;
        u.pair(t, u.btn("Backup semua penting", 3, v -> backupAll(m)), u.btn("Info boot.img", 2, v -> bootInfo(m)));
        u.pair(t, u.btn("Matikan verity", 1, v -> verity(m)), u.btn("Flash zip (.img)", 1, v -> flashZip(m)));
        u.pair(t, u.btn("Logcat", 2, v -> logcat(m)), u.btn("Screenshot", 3, v -> screenshot(m)));
        u.pair(t, u.btn("Daftar app", 2, v -> apps(m)), u.btn("Uninstall app", 1, v -> uninstall(m)));
        u.pair(t, u.btn("Lihat folder (ls)", 2, v -> ls(m)), null);
    }

    static void backupAll(MainActivity m) {
        if (!m.hasRoot) { m.log("Error: root tidak tersedia."); return; }
        String d = "/sdcard/Download/backup_all";
        String c = "mkdir -p " + d + "; for p in boot recovery vbmeta dtbo persist modem fsg modemst1 modemst2 misc; do if [ -e /dev/block/bootdevice/by-name/$p ]; then echo backup $p; dd if=/dev/block/bootdevice/by-name/$p of=" + d + "/$p.img bs=4M 2>&1 | tail -1; fi; done; sync; ls -l " + d;
        m.log("$ backup semua partisi penting ke " + d);
        m.setStatus("backup semua ...");
        m.ex.execute(() -> m.exec(c, true));
    }

    static void verity(MainActivity m) {
        String p = m.part.getText().toString().trim();
        if (p.isEmpty()) p = "vbmeta";
        if (!p.matches("[A-Za-z0-9_]{1,32}")) { m.log("Error: nama partisi tidak valid."); return; }
        if (m.needFb() || m.needPicked()) return;
        final String pt = p;
        final Uri uri = m.picked;
        final long size = m.pickedSize;
        m.confirm("Flash '" + pt + "' dengan verity dan verifikasi dimatikan?\nPakai file vbmeta dari ROM yang sama dengan HP target.", () -> m.bg(() -> {
            if (size > 65536) throw new IOException("File terlalu besar untuk vbmeta (" + size + " byte)");
            byte[] b = new byte[(int) size];
            int off = 0;
            try (InputStream is = m.openUri(uri)) {
                while (off < b.length) {
                    int n = is.read(b, off, b.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            if (off < 124 || b[0] != 'A' || b[1] != 'V' || b[2] != 'B' || b[3] != '0') throw new IOException("Bukan vbmeta (header AVB0 tidak ada)");
            b[123] = 3;
            m.setStatus("flash " + pt + " (verity off) ...");
            m.fb.download(b.length, b, null, 0, 0, null, m::log);
            m.log(m.fb.cmd("flash:" + pt, 60000, m::log));
            m.setStatus("verity dimatikan");
        }));
    }

    static void flashZip(MainActivity m) {
        if (m.needFb() || m.needPicked()) return;
        final Uri uri = m.picked;
        m.confirm("Flash SEMUA file .img di dalam zip ke partisi sesuai nama file, berurutan?\nPartisi yang salah bisa membuat HP target mati total.", () -> m.bg(() -> {
            int count = 0;
            try (ZipInputStream zi = new ZipInputStream(m.openUri(uri))) {
                ZipEntry e;
                while ((e = zi.getNextEntry()) != null) {
                    String nm = e.getName();
                    nm = nm.substring(nm.lastIndexOf('/') + 1);
                    if (e.isDirectory() || !nm.endsWith(".img")) continue;
                    final String pt = nm.substring(0, nm.length() - 4);
                    if (!pt.matches("[A-Za-z0-9_]{1,32}")) { m.log("Lewati " + nm); continue; }
                    final File tmp = new File(m.getCacheDir(), "zip_part.img");
                    try (OutputStream os = new FileOutputStream(tmp)) {
                        byte[] b = new byte[65536];
                        int r;
                        while ((r = zi.read(b)) > 0) os.write(b, 0, r);
                    }
                    m.log("== " + pt + " (" + tmp.length() + " byte)");
                    m.setStatus("flash " + pt + " ...");
                    m.fb.flash(() -> new FileInputStream(tmp), tmp.length(), pt, m::log);
                    tmp.delete();
                    count++;
                }
            }
            m.log("Selesai, " + count + " partisi di-flash.");
            m.setStatus("flash zip selesai");
        }));
    }

    static void logcat(MainActivity m) {
        if (m.needAdb()) return;
        m.bg(() -> m.log(m.adb.run("shell:logcat -d -t 300").trim()));
    }

    static void screenshot(MainActivity m) {
        if (m.needAdb()) return;
        m.bg(() -> {
            m.adb.run("shell:screencap -p /data/local/tmp/_s.png");
            File dst = new File(m.getExternalFilesDir(null), "screenshot_" + System.currentTimeMillis() + ".png");
            long n;
            try (OutputStream os = new FileOutputStream(dst)) { n = m.adb.pull("/data/local/tmp/_s.png", os, m::log); }
            m.adb.run("shell:rm -f /data/local/tmp/_s.png");
            m.log("Screenshot (" + n + " byte): " + dst.getPath());
        });
    }

    static void apps(MainActivity m) {
        if (m.needAdb()) return;
        m.bg(() -> m.log(m.adb.run("shell:pm list packages -3").trim()));
    }

    static void uninstall(MainActivity m) {
        String pk = m.cmd.getText().toString().trim();
        if (m.needAdb()) return;
        if (!pk.matches("[A-Za-z0-9_.]{3,200}")) { m.log("Error: isi nama paket di kolom perintah, mis. com.contoh.app"); return; }
        m.confirm("Uninstall '" + pk + "' dari HP target?", () -> m.bg(() -> m.log(m.adb.run("shell:pm uninstall --user 0 " + pk).trim())));
    }

    static void ls(MainActivity m) {
        if (m.needAdb()) return;
        String path = m.cmd.getText().toString().trim();
        if (path.isEmpty()) path = "/sdcard";
        final String pth = path;
        m.bg(() -> m.log(m.adb.run("shell:ls -la " + pth).trim()));
    }

    static String cstr(byte[] b, int o, int len) {
        int e = o;
        while (e < o + len && b[e] != 0) e++;
        return new String(b, o, e - o);
    }

    static void bootInfo(MainActivity m) {
        if (m.needPicked()) return;
        final Uri uri = m.picked;
        m.bg(() -> {
            byte[] h = new byte[1632];
            int off = 0;
            try (InputStream is = m.openUri(uri)) {
                while (off < h.length) {
                    int n = is.read(h, off, h.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            if (off < 640 || !new String(h, 0, 8).equals("ANDROID!")) {
                m.log("Error: bukan boot.img Android (header ANDROID! tidak ada).");
                return;
            }
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            m.log("kernel size  : " + (b.getInt(8) & 0xffffffffL) + " byte");
            m.log("kernel addr  : 0x" + Long.toHexString(b.getInt(12) & 0xffffffffL));
            m.log("ramdisk size : " + (b.getInt(16) & 0xffffffffL) + " byte");
            m.log("ramdisk addr : 0x" + Long.toHexString(b.getInt(20) & 0xffffffffL));
            m.log("second size  : " + (b.getInt(24) & 0xffffffffL) + " byte");
            m.log("tags addr    : 0x" + Long.toHexString(b.getInt(32) & 0xffffffffL));
            m.log("page size    : " + b.getInt(36));
            m.log("header ver   : " + b.getInt(40));
            m.log("name         : " + cstr(h, 48, 16));
            m.log("cmdline      : " + cstr(h, 64, 512));
            if (off >= 1632) {
                String x = cstr(h, 608, 1024);
                if (!x.isEmpty()) m.log("cmdline+     : " + x);
            }
        });
    }
}
