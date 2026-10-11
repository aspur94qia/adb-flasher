package com.aspur.adbflasher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.widget.LinearLayout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class Extra2 {
    static PowerManager.WakeLock wl;
    static int depth = 0;
    static final Pattern PROG = Pattern.compile("^(Kirim|Push|Sideload) (\\d+)%$");

    static void build(MainActivity m, LinearLayout t) {
        Ui u = m.u;
        u.pair(t, u.btn("Cek android-info (zip)", 2, v -> checkInfo(m)), null);
        u.pair(t, u.btn("Simpan makro", 3, v -> macroSave(m)), u.btn("Jalankan makro", 1, v -> macroRun(m)));
        u.pair(t, u.btn("Daftar makro", 2, v -> macroList(m)), u.btn("Hapus makro", 0, v -> macroDelete(m)));
    }

    static synchronized void lock(MainActivity m, boolean on) {
        try {
            if (on) {
                if (depth++ == 0) {
                    PowerManager pm = (PowerManager) m.getSystemService(Context.POWER_SERVICE);
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adbflasher:job");
                    wl.acquire(60 * 60 * 1000L);
                }
            } else if (depth > 0 && --depth == 0 && wl != null && wl.isHeld()) {
                wl.release();
            }
        } catch (Exception e) { }
    }

    static void progress(MainActivity m, String s) {
        Matcher x = PROG.matcher(s);
        if (!x.matches()) return;
        int p = Integer.parseInt(x.group(2));
        int f = Math.min(10, p / 10);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(i < f ? '#' : '-');
        m.setStatus(x.group(1) + " [" + b + "] " + p + "%");
    }

    static void checkInfo(MainActivity m) {
        if (m.needFb() || m.needPicked()) return;
        final Uri uri = m.picked;
        m.bg(() -> {
            String prod = m.fb.getvar("product", m::log).trim();
            m.log("product perangkat: " + prod);
            String info = null;
            try (ZipInputStream zi = new ZipInputStream(m.openUri(uri))) {
                ZipEntry e;
                while ((e = zi.getNextEntry()) != null) {
                    if (e.getName().endsWith("android-info.txt")) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = zi.read(b)) > 0) bo.write(b, 0, n);
                        info = bo.toString();
                        break;
                    }
                }
            }
            if (info == null) {
                m.log("android-info.txt tidak ada di zip (bukan factory zip), tidak bisa dicek.");
                return;
            }
            boolean found = false, ok = false;
            for (String line : info.split("\n")) {
                line = line.trim();
                if (line.startsWith("require board=")) {
                    found = true;
                    for (String bd : line.substring(14).split("\\|")) {
                        if (bd.trim().equals(prod)) ok = true;
                    }
                    m.log(line);
                }
            }
            if (!found) m.log("Tidak ada baris 'require board=' di android-info.txt.");
            else if (ok) m.log("COCOK: zip ini untuk perangkat '" + prod + "'.");
            else m.log("Error: TIDAK COCOK. Zip ini bukan untuk '" + prod + "'. Jangan di-flash.");
        });
    }

    static SharedPreferences sp(MainActivity m) { return m.getSharedPreferences("macros", 0); }

    static String macroName(MainActivity m) {
        String n = m.part.getText().toString().trim();
        if (!n.matches("[A-Za-z0-9_]{1,24}")) {
            m.log("Error: isi nama makro di kolom partisi (huruf, angka, _).");
            return null;
        }
        return n;
    }

    static void macroSave(MainActivity m) {
        String n = macroName(m);
        if (n == null) return;
        String c = m.cmd.getText().toString().trim();
        if (c.isEmpty()) {
            m.log("Error: isi langkah di kolom perintah, pisah dengan titik koma.");
            return;
        }
        sp(m).edit().putString(n, c).apply();
        m.log("Makro '" + n + "' disimpan: " + c);
    }

    static void macroList(MainActivity m) {
        Map<String, ?> all = sp(m).getAll();
        if (all.isEmpty()) {
            m.log("Belum ada makro.");
            return;
        }
        for (Map.Entry<String, ?> e : all.entrySet()) m.log(e.getKey() + " = " + e.getValue());
    }

    static void macroDelete(MainActivity m) {
        String n = macroName(m);
        if (n == null) return;
        sp(m).edit().remove(n).apply();
        m.log("Makro '" + n + "' dihapus (jika ada).");
    }

    static void macroRun(MainActivity m) {
        String n = macroName(m);
        if (n == null) return;
        final String steps = sp(m).getString(n, null);
        if (steps == null) {
            m.log("Error: makro '" + n + "' tidak ada.");
            return;
        }
        m.confirm("Jalankan makro '" + n + "':\n" + steps.replace(";", "\n"), () -> m.bg(() -> {
            for (String st : steps.split(";")) {
                st = st.trim();
                if (st.isEmpty()) continue;
                m.log("[makro] " + st);
                if (st.startsWith("fb:")) {
                    if (m.fb == null) throw new IOException("fastboot belum terhubung");
                    m.log(m.fb.cmd(st.substring(3).trim(), 60000, m::log));
                } else if (st.startsWith("adb:")) {
                    if (m.adb == null) throw new IOException("ADB belum terhubung");
                    m.log(m.adb.run("shell:" + st.substring(4).trim()).trim());
                } else if (st.startsWith("wait:")) {
                    Thread.sleep(Long.parseLong(st.substring(5).trim()) * 1000);
                } else {
                    throw new IOException("langkah tidak dikenal: " + st);
                }
            }
            m.log("Makro selesai.");
        }));
    }
}
