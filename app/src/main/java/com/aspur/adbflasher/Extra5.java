package com.aspur.adbflasher;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

class Extra5 {
    static TextView chipRoot, chipFb, chipAdb, barText;
    static ProgressBar bar;
    static String target = "";
    static Object lastConn = null;
    static final ArrayList<String> hist = new ArrayList<>();
    static int cur = -1;
    static boolean curFailed = false;
    static float fontSize = 11f;

    static TextView chip(MainActivity m, String t) {
        TextView v = m.u.mono(t, 11, Ui.DIM);
        v.setPadding(m.u.dp(10), m.u.dp(4), m.u.dp(10), m.u.dp(4));
        v.setBackground(m.u.shape(0xFF21262D, Ui.LINE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(m.u.dp(3), 0, m.u.dp(3), 0);
        v.setLayoutParams(lp);
        return v;
    }

    static void setChip(MainActivity m, TextView v, boolean on) {
        v.setTextColor(on ? 0xFF56D364 : Ui.DIM);
        v.setBackground(m.u.shape(on ? 0xFF0F2A17 : 0xFF21262D, on ? 0xFF3FB950 : Ui.LINE, 12));
    }

    static void install(MainActivity m) {
        LinearLayout cont = (LinearLayout) m.part.getParent();
        LinearLayout root = (LinearLayout) cont.getParent();

        LinearLayout tb = (LinearLayout) m.tabBtns[0].getParent();
        int ti = root.indexOfChild(tb);
        root.removeView(tb);
        for (Button b : m.tabBtns) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.setMargins(m.u.dp(2), 0, m.u.dp(2), 0);
            b.setLayoutParams(blp);
            b.setPadding(m.u.dp(16), m.u.dp(10), m.u.dp(16), m.u.dp(10));
        }
        HorizontalScrollView hs = new HorizontalScrollView(m);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(tb, new FrameLayout.LayoutParams(-2, -2));
        root.addView(hs, ti, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout chips = new LinearLayout(m);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(m.u.dp(8), m.u.dp(6), m.u.dp(8), 0);
        chipRoot = chip(m, "ROOT");
        chipFb = chip(m, "FASTBOOT");
        chipAdb = chip(m, "ADB");
        chips.addView(chipRoot);
        chips.addView(chipFb);
        chips.addView(chipAdb);
        root.addView(chips, 1, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout term = (LinearLayout) m.osv.getParent();
        LinearLayout pr = new LinearLayout(m);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout mid = new LinearLayout(m);
        mid.setOrientation(LinearLayout.VERTICAL);
        barText = m.u.mono(" siap", 10, Ui.DIM);
        mid.addView(barText);
        bar = new ProgressBar(m, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(0xFF3FB950));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF21262D));
        mid.addView(bar, new LinearLayout.LayoutParams(-1, m.u.dp(8)));
        pr.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        pr.addView(m.u.btn("Batal", 1, v -> cancel(m)), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(0, m.u.dp(4), 0, m.u.dp(4));
        cont.addView(pr, cont.indexOfChild(term), plp);

        LinearLayout t = m.tabs[3];
        m.u.pair(t, m.u.btn("Simpan log", 3, v -> saveLog(m)), m.u.btn("Riwayat", 2, v -> showHist(m)));
        m.u.pair(t, m.u.btn("Font +", 0, v -> font(m, 1)), m.u.btn("Font -", 0, v -> font(m, -1)));
        m.u.pair(t, m.u.btn("Verifikasi hash", 2, v -> verify(m)), null);

        SharedPreferences sp = m.getSharedPreferences("settings", 0);
        fontSize = sp.getFloat("font", 11f);
        m.out.setTextSize(fontSize);
        String lastPart = sp.getString("lastpart", "");
        if (!lastPart.isEmpty()) m.part.setText(lastPart);

        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (m.isDestroyed()) return;
                refresh(m);
                m.ui.postDelayed(this, 1500);
            }
        };
        m.ui.post(tick);
        handleIntent(m, m.getIntent());
    }

    static void refresh(MainActivity m) {
        setChip(m, chipRoot, m.hasRoot);
        UsbManager um = (UsbManager) m.getSystemService(android.content.Context.USB_SERVICE);
        boolean fbDev = false, adbDev = false;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (Fastboot.isFastboot(d)) fbDev = true;
            if (Adb.isAdb(d)) adbDev = true;
        }
        if (Extra2.depth == 0) {
            if (m.fb != null && !fbDev) {
                m.fb = null;
                m.log("Fastboot terputus.");
            }
            if (m.adb != null && m.adb.uc != null && !adbDev) {
                m.adb.close();
                m.adb = null;
                m.log("ADB (USB) terputus.");
            }
        }
        setChip(m, chipFb, m.fb != null);
        setChip(m, chipAdb, m.adb != null);
        Object conn = m.fb != null ? m.fb : (m.adb != null ? m.adb : null);
        if (conn != lastConn) {
            lastConn = conn;
            target = "";
            if (conn != null && Extra2.depth == 0) fetchTarget(m);
        }
    }

    static void fetchTarget(MainActivity m) {
        m.bg(() -> {
            try {
                if (m.fb != null) target = "fastboot: " + m.fb.getvar("product", s -> { }).trim();
                else if (m.adb != null) target = "adb: " + m.adb.run("shell:getprop ro.product.device").trim();
            } catch (Exception e) {
                target = "";
            }
        });
    }

    static void cancel(MainActivity m) {
        new AlertDialog.Builder(m).setTitle("Batalkan proses?")
                .setMessage("Koneksi USB/ADB akan diputus dan proses dihentikan.\nFlash yang terputus di tengah bisa meninggalkan partisi setengah tertulis.")
                .setPositiveButton("Batalkan", (d, w) -> doCancel(m))
                .setNegativeButton("Lanjutkan", null).show();
    }

    static void doCancel(MainActivity m) {
        m.log("Error: dibatalkan pengguna.");
        try {
            if (m.fb != null) {
                m.fb.c.close();
                m.fb = null;
            }
        } catch (Exception e) { }
        try {
            if (m.adb != null) {
                m.adb.close();
                m.adb = null;
            }
        } catch (Exception e) { }
        m.ex.shutdownNow();
        m.ex = Executors.newSingleThreadExecutor();
        if (m.hasRoot) new Thread(() -> Extra4.capture("pkill -x dd || killall dd", true)).start();
        m.setStatus("dibatalkan, sambung ulang perangkat");
    }

    static void prog(MainActivity m, int p) {
        m.ui.post(() -> {
            if (bar == null) return;
            bar.setProgress(p);
            barText.setText(" " + p + "%");
            barText.setTextColor(0xFF79C0FF);
        });
    }

    static void reset(MainActivity m) {
        end();
        m.ui.post(() -> {
            if (bar == null) return;
            bar.setProgress(0);
            barText.setText(" siap");
            barText.setTextColor(Ui.DIM);
        });
    }

    static synchronized void record(String msg) {
        if (cur >= 0) hist.set(cur, hist.get(cur) + " -> ?");
        String first = msg.split("\n")[0];
        if (first.length() > 70) first = first.substring(0, 70);
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        hist.add(ts + " " + first);
        if (hist.size() > 50) hist.remove(0);
        cur = hist.size() - 1;
        curFailed = false;
    }

    static synchronized void end() {
        if (cur >= 0 && cur < hist.size()) {
            hist.set(cur, hist.get(cur) + (curFailed ? " -> GAGAL" : " -> selesai"));
        }
        cur = -1;
    }

    static synchronized void onLog(String s) {
        if (cur < 0) return;
        if (s.startsWith("Error") || s.startsWith("Gagal")) curFailed = true;
        if (s.startsWith("[selesai, kode ")) {
            curFailed = !s.startsWith("[selesai, kode 0]");
            end();
        }
    }

    static void showHist(MainActivity m) {
        ArrayList<String> c;
        synchronized (Extra5.class) { c = new ArrayList<>(hist); }
        if (c.isEmpty()) {
            m.log("Belum ada riwayat operasi.");
            return;
        }
        m.log("--- riwayat (terbaru di bawah) ---");
        for (String s : c) m.log(s);
    }

    static String summary(MainActivity m, String msg) {
        StringBuilder sb = new StringBuilder(msg);
        sb.append("\n\n--- Ringkasan ---");
        sb.append("\nTarget  : ").append(target.isEmpty() ? "(belum diketahui)" : target);
        String p = m.part.getText().toString().trim();
        sb.append("\nPartisi : ").append(p.isEmpty() ? "-" : p);
        if (m.picked != null) {
            sb.append("\nFile    : ").append(m.pickedName);
            sb.append("\nUkuran  : ").append(m.pickedSize).append(" byte (").append(m.pickedSize / 1048576).append(" MB)");
        } else {
            sb.append("\nFile    : -");
        }
        return sb.toString();
    }

    static boolean confirm(MainActivity m, String msg, Runnable r) {
        boolean danger = msg.contains("HAPUS") || msg.contains("menghapus") || msg.contains("mati total") || msg.contains("Yakin");
        String body = summary(m, msg);
        final Runnable go = () -> {
            record(msg);
            m.getSharedPreferences("settings", 0).edit().putString("lastpart", m.part.getText().toString().trim()).apply();
            r.run();
        };
        AlertDialog.Builder b = new AlertDialog.Builder(m).setTitle(danger ? "PERINGATAN" : "Konfirmasi");
        if (!danger) {
            b.setMessage(body).setPositiveButton("Lanjut", (d, w) -> go.run()).setNegativeButton("Batal", null).show();
            return true;
        }
        LinearLayout lay = new LinearLayout(m);
        lay.setOrientation(LinearLayout.VERTICAL);
        int pd = m.u.dp(20);
        lay.setPadding(pd, m.u.dp(10), pd, 0);
        lay.addView(m.u.mono(body, 12, Ui.TXT));
        TextView hint = m.u.mono("Geser sampai ujung kanan untuk melanjutkan", 11, 0xFFFF7B72);
        hint.setPadding(0, m.u.dp(14), 0, 0);
        lay.addView(hint);
        SeekBar sb = new SeekBar(m);
        sb.setMax(100);
        lay.addView(sb);
        final AlertDialog dlg = b.setView(lay).setNegativeButton("Batal", null).create();
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }

            @Override public void onStartTrackingTouch(SeekBar s) { }

            @Override public void onStopTrackingTouch(SeekBar s) {
                if (s.getProgress() >= 95) {
                    dlg.dismiss();
                    go.run();
                } else {
                    s.setProgress(0);
                }
            }
        });
        dlg.show();
        return true;
    }

    static void handleIntent(MainActivity m, Intent i) {
        if (i == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) return;
        UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (d == null) return;
        if (Fastboot.isFastboot(d)) {
            m.showTab(1);
            m.findUsb(0);
        } else if (Adb.isAdb(d)) {
            m.showTab(2);
            m.findUsb(1);
        }
    }

    static void saveLog(MainActivity m) {
        try {
            String nm = "log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
            File f = new File(m.getExternalFilesDir(null), nm);
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(m.out.getText().toString().getBytes()); }
            m.log("Log disimpan: " + f.getPath());
        } catch (Exception e) {
            m.log("Error: " + e.getMessage());
        }
    }

    static void font(MainActivity m, int d) {
        fontSize = Math.max(8f, Math.min(20f, fontSize + d));
        m.out.setTextSize(fontSize);
        m.getSharedPreferences("settings", 0).edit().putFloat("font", fontSize).apply();
        m.log("Ukuran font: " + (int) fontSize);
    }

    static void verify(MainActivity m) {
        if (m.needPicked()) return;
        String exp = m.cmd.getText().toString().trim().toLowerCase(Locale.US);
        final String algo = exp.length() == 64 ? "SHA-256" : (exp.length() == 32 ? "MD5" : "");
        if (algo.isEmpty() || !exp.matches("[0-9a-f]+")) {
            m.log("Error: tempel hash resmi (SHA-256 64 karakter atau MD5 32 karakter) di kolom perintah.");
            return;
        }
        final String want = exp;
        final Uri uri = m.picked;
        m.bg(() -> {
            MessageDigest md = MessageDigest.getInstance(algo);
            try (InputStream is = m.openUri(uri)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = is.read(b)) > 0) md.update(b, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            m.log(algo + " file : " + sb);
            if (sb.toString().equals(want)) m.log("COCOK: file sesuai hash resmi.");
            else m.log("Error: TIDAK COCOK. Jangan di-flash.");
        });
    }
}
