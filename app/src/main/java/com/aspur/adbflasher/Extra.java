package com.aspur.adbflasher;

import android.widget.LinearLayout;
import java.io.File;
import java.io.FileOutputStream;

class Extra {
    static void build(MainActivity m, LinearLayout t) {
        Ui u = m.u;
        u.pair(t, u.btn("Info lengkap", 2, v -> info(m)), u.btn("Laporan ringkas", 3, v -> report(m)));
        u.pair(t, u.btn("Tap (x y)", 0, v -> input(m, "tap", 2)), u.btn("Swipe (x1 y1 x2 y2)", 0, v -> input(m, "swipe", 4)));
        u.pair(t, u.btn("Ketik teks", 0, v -> typeText(m)), u.btn("Layar hidup/mati", 0, v -> adb(m, "input keyevent 26")));
        u.pair(t, u.btn("Home", 0, v -> adb(m, "input keyevent 3")), u.btn("Back", 0, v -> adb(m, "input keyevent 4")));
        u.pair(t, u.btn("Peta partisi (root)", 2, v -> partMap(m)), u.btn("Status bootloader", 2, v -> blStatus(m)));
        u.pair(t, u.btn("Wipe userdata", 1, v -> wipe(m, "userdata")), u.btn("Wipe cache", 1, v -> wipe(m, "cache")));
        u.pair(t, u.btn("Shell lokal (HP ini)", 0, v -> m.sh(m.cmd.getText().toString(), false)), null);
    }

    static void adb(MainActivity m, String c) {
        if (m.needAdb()) return;
        m.log("$ " + c);
        m.bg(() -> m.log(m.adb.run("shell:" + c).trim()));
    }

    static void info(MainActivity m) {
        adb(m, "for k in ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.treble.enabled ro.boot.slot_suffix ro.boot.verifiedbootstate ro.boot.flash.locked; do echo $k=$(getprop $k); done; grep -m2 -E 'MemTotal|MemAvailable' /proc/meminfo; dumpsys battery | grep -E 'level|temperature|status'");
    }

    static void input(MainActivity m, String kind, int count) {
        String s = m.cmd.getText().toString().trim();
        if (!s.matches("[0-9]+( [0-9]+)*") || s.split(" ").length < count) {
            m.log("Error: isi " + count + " angka dipisah spasi di kolom perintah.");
            return;
        }
        adb(m, "input " + kind + " " + s);
    }

    static void typeText(MainActivity m) {
        String s = m.cmd.getText().toString();
        if (!s.matches("[A-Za-z0-9 .,@_-]{1,200}")) {
            m.log("Error: teks hanya boleh huruf, angka, spasi, dan . , @ _ -");
            return;
        }
        adb(m, "input text " + s.replace(" ", "%s"));
    }

    static void report(MainActivity m) {
        if (m.needAdb()) return;
        m.bg(() -> {
            String s = m.adb.run("shell:getprop; echo ===DMESG===; dmesg 2>&1; echo ===LOGCAT===; logcat -d -t 2000 2>&1");
            File f = new File(m.getExternalFilesDir(null), "laporan_" + System.currentTimeMillis() + ".txt");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes()); }
            m.log("Laporan (" + s.length() + " karakter): " + f.getPath());
        });
    }

    static void partMap(MainActivity m) {
        m.sh("for p in /dev/block/bootdevice/by-name/*; do n=$(basename $p); s=$(blockdev --getsize64 $p 2>/dev/null); [ -n \"$s\" ] && echo \"$n $((s/1048576)) MB\"; done", true);
    }

    static void blStatus(MainActivity m) {
        if (m.needFb()) return;
        m.bg(() -> {
            String[] n = {"unlocked", "secure", "anti", "off-mode-charge", "variant", "hw-revision"};
            for (String s : n) m.log(s + ": " + m.fb.getvar(s, m::log));
            m.log(m.fb.cmd("oem device-info", 10000, m::log));
        });
    }

    static void wipe(MainActivity m, String p) {
        if (m.needFb()) return;
        m.confirm("HAPUS '" + p + "' di HP target? Data hilang permanen.", () ->
                m.confirm("Yakin? Ini tidak bisa dibatalkan.", () ->
                        m.bg(() -> m.log(m.fb.cmd("erase:" + p, 120000, m::log)))));
    }
}
