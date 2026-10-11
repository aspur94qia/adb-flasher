package com.aspur.adbflasher;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final String ACT = "com.aspur.adbflasher.USB";
    static final int BG = 0xFF0D1117, BAR = 0xFF161B22, LINE = 0xFF30363D, TXT = 0xFFC9D1D9, DIM = 0xFF8B949E;

    TextView out, info, status;
    EditText part, cmd;
    LinearLayout rootBox, fbBox;
    ScrollView osv;
    Uri picked;
    long pickedSize;
    Fastboot fb;
    ExecutorService ex = Executors.newSingleThreadExecutor();
    Handler ui = new Handler(Looper.getMainLooper());

    BroadcastReceiver rc = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openFb(d);
            else log("Izin USB ditolak.");
        }
    };

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    TextView mono(String t, int size, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    View dot(int color) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(12), dp(12));
        lp.setMargins(0, 0, dp(7), 0);
        v.setLayoutParams(lp);
        return v;
    }

    EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(TXT);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setBackground(shape(0xFF010409, LINE, 6));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        e.setLayoutParams(lp);
        return e;
    }

    Button btn(String t, int kind, View.OnClickListener l) {
        int fill = 0xFF21262D, stroke = LINE, text = TXT;
        if (kind == 1) { fill = 0xFF2D1215; stroke = 0xFFF85149; text = 0xFFFF7B72; }
        else if (kind == 2) { fill = 0xFF0D2240; stroke = 0xFF58A6FF; text = 0xFF79C0FF; }
        else if (kind == 3) { fill = 0xFF0F2A17; stroke = 0xFF3FB950; text = 0xFF56D364; }
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(text);
        b.setTextSize(12);
        b.setTypeface(Typeface.MONOSPACE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), shape(fill, stroke, 8), null));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    void pair(LinearLayout p, Button a, Button b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, -2, 1);
        la.setMargins(0, dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, -2, 1);
        lb.setMargins(dp(3), dp(3), 0, dp(3));
        r.addView(a, la);
        if (b != null) r.addView(b, lb); else r.addView(new View(this), lb);
        p.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    void setStatus(String s) { ui.post(() -> status.setText(" " + s)); }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().setStatusBarColor(BAR);
        getWindow().setNavigationBarColor(BAR);
        IntentFilter f = new IntentFilter(ACT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rc, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rc, f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.addView(dot(0xFFFF5F56));
        bar.addView(dot(0xFFFFBD2E));
        bar.addView(dot(0xFF27C93F));
        TextView title = mono("ADB Flasher - " + (BuildConfig.ROOT_MODE ? "root" : "non-root"), 13, TXT);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = mono("clear", 12, DIM);
        clear.setOnClickListener(v -> out.setText(""));
        bar.addView(clear);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setPadding(dp(10), dp(8), dp(10), dp(8));

        cont.addView(mono("PARTISI / PERINTAH", 10, DIM));
        part = field("partisi, mis. boot");
        cont.addView(part);
        cmd = field("perintah");
        cont.addView(cmd);
        info = mono("file: (belum dipilih)", 11, DIM);
        cont.addView(info);

        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        pair(rootBox, btn("Cek root", 2, v -> sh("id", true)),
                btn("Daftar partisi", 0, v -> sh("ls -l /dev/block/bootdevice/by-name/ | awk '{print $(NF-2), $NF}'", true)));
        pair(rootBox, btn("Pilih file image", 0, v -> pick()), btn("Backup partisi", 3, v -> rootBackup()));
        pair(rootBox, btn("Flash ke partisi", 1, v -> rootFlash()),
                btn("Jalankan perintah", 2, v -> sh(cmd.getText().toString(), true)));
        pair(rootBox, btn("Reboot", 0, v -> sh("reboot", true)), btn("Recovery", 0, v -> sh("reboot recovery", true)));
        pair(rootBox, btn("Bootloader", 0, v -> sh("reboot bootloader", true)), null);

        fbBox = new LinearLayout(this);
        fbBox.setOrientation(LinearLayout.VERTICAL);
        pair(fbBox, btn("Cari fastboot (OTG)", 2, v -> findUsb()), btn("getvar all", 0, v -> fbCmd("getvar:all")));
        pair(fbBox, btn("Pilih file image", 0, v -> pick()), btn("Flash ke partisi", 1, v -> fbFlash()));
        pair(fbBox, btn("Reboot", 0, v -> fbCmd("reboot")), btn("Reboot bootloader", 0, v -> fbCmd("reboot-bootloader")));
        pair(fbBox, btn("Kirim fastboot", 2, v -> fbCmd(cmd.getText().toString())),
                btn("Shell biasa", 0, v -> sh(cmd.getText().toString(), false)));

        ScrollView csv = new ScrollView(this);
        LinearLayout both = new LinearLayout(this);
        both.setOrientation(LinearLayout.VERTICAL);
        both.addView(rootBox);
        both.addView(fbBox);
        csv.addView(both);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, 0, 1);
        cl.setMargins(0, dp(6), 0, dp(6));
        cont.addView(csv, cl);

        LinearLayout term = new LinearLayout(this);
        term.setOrientation(LinearLayout.VERTICAL);
        term.setBackground(shape(0xFF010409, LINE, 8));
        term.setPadding(dp(8), dp(6), dp(8), dp(6));
        term.addView(mono("TERMINAL", 10, DIM));
        out = mono("", 11, 0xFF3FB950);
        out.setTextIsSelectable(true);
        osv = new ScrollView(this);
        osv.addView(out);
        term.addView(osv, new LinearLayout.LayoutParams(-1, 0, 1));
        cont.addView(term, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(cont, new LinearLayout.LayoutParams(-1, 0, 1));

        status = mono(" siap", 11, 0xFFFFFFFF);
        status.setBackgroundColor(0xFF1F6FEB);
        status.setPadding(dp(8), dp(5), dp(8), dp(5));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        rootBox.setVisibility(BuildConfig.ROOT_MODE ? View.VISIBLE : View.GONE);
        fbBox.setVisibility(BuildConfig.ROOT_MODE ? View.GONE : View.VISIBLE);
        log("ADB Flasher 1.0 [" + (BuildConfig.ROOT_MODE ? "ROOT" : "NON-ROOT") + "]");
        log(BuildConfig.ROOT_MODE ? "Tekan 'Cek root' untuk memulai." : "Sambungkan HP target (mode fastboot) lewat OTG.");
        setStatus(BuildConfig.ROOT_MODE ? "mode root" : "mode non-root (OTG fastboot)");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(rc);
    }

    void log(String s) {
        ui.post(() -> {
            int col = 0xFF3FB950;
            if (s.startsWith("$") || s.startsWith(">")) col = 0xFF79C0FF;
            else if (s.startsWith("Error") || s.startsWith("Gagal") || s.startsWith("Izin")) col = 0xFFFF7B72;
            else if (s.startsWith("[selesai")) col = 0xFF8B949E;
            SpannableString sp = new SpannableString(s + "\n");
            sp.setSpan(new ForegroundColorSpan(col), 0, sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append(sp);
            osv.post(() -> osv.fullScroll(View.FOCUS_DOWN));
        });
    }

    String pn() {
        String s = part.getText().toString().trim();
        if (!s.matches("[A-Za-z0-9_]{1,32}")) {
            Toast.makeText(this, "Nama partisi tidak valid", Toast.LENGTH_SHORT).show();
            return null;
        }
        return s;
    }

    void confirm(String msg, Runnable r) {
        new AlertDialog.Builder(this).setTitle("Konfirmasi").setMessage(msg)
                .setPositiveButton("Lanjut", (d, w) -> r.run())
                .setNegativeButton("Batal", null).show();
    }

    int exec(String command, boolean su) {
        try {
            String[] a = su ? new String[]{"su", "-c", command} : new String[]{"sh", "-c", command};
            java.lang.Process pr = new ProcessBuilder(a).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) log(l);
            int code = pr.waitFor();
            log("[selesai, kode " + code + "]");
            setStatus("siap (kode " + code + ")");
            return code;
        } catch (Exception e) {
            log("Error: " + e.getMessage());
            setStatus("error");
            return -1;
        }
    }

    void sh(String command, boolean su) {
        if (command.trim().isEmpty()) return;
        log("$ " + command);
        setStatus("menjalankan ...");
        ex.execute(() -> exec(command, su));
    }

    void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 1);
    }

    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq != 1 || rs != RESULT_OK || d == null) return;
        picked = d.getData();
        String name = "?";
        pickedSize = 0;
        try (Cursor c = getContentResolver().query(picked, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                pickedSize = c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE));
            }
        } catch (Exception e) { log("Gagal baca file: " + e.getMessage()); }
        info.setText("file: " + name + " (" + pickedSize + " byte)");
        info.setTextColor(0xFF79C0FF);
    }

    void rootFlash() {
        String p = pn();
        if (p == null) return;
        if (picked == null) { Toast.makeText(this, "Pilih file dulu", Toast.LENGTH_SHORT).show(); return; }
        confirm("Flash file ke partisi '" + p + "'?\nPartisi yang salah bisa membuat HP mati total.", () -> ex.execute(() -> {
            File f = new File(getCacheDir(), "pick.img");
            try (InputStream is = getContentResolver().openInputStream(picked);
                 OutputStream os = new FileOutputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = is.read(b)) > 0) os.write(b, 0, n);
            } catch (Exception e) { log("Gagal salin: " + e.getMessage()); return; }
            log("Flash ke " + p + " ...");
            setStatus("flash " + p + " ...");
            exec("dd if=" + f.getPath() + " of=/dev/block/bootdevice/by-name/" + p + " bs=4M && sync", true);
            f.delete();
        }));
    }

    void rootBackup() {
        String p = pn();
        if (p == null) return;
        String dst = "/sdcard/Download/backup_" + p + ".img";
        log("Backup " + p + " ke " + dst);
        setStatus("backup " + p + " ...");
        ex.execute(() -> exec("dd if=/dev/block/bootdevice/by-name/" + p + " of=" + dst + " bs=4M && sync", true));
    }

    void findUsb() {
        UsbManager m = (UsbManager) getSystemService(USB_SERVICE);
        for (UsbDevice d : m.getDeviceList().values()) {
            if (Fastboot.isFastboot(d)) {
                log("Ditemukan: " + d.getDeviceName());
                if (m.hasPermission(d)) openFb(d);
                else m.requestPermission(d, PendingIntent.getBroadcast(this, 0,
                        new Intent(ACT).setPackage(getPackageName()), PendingIntent.FLAG_MUTABLE));
                return;
            }
        }
        log("Tidak ada perangkat fastboot. Masuk mode fastboot di HP target, sambung kabel OTG.");
        setStatus("tidak ada perangkat fastboot");
    }

    void openFb(UsbDevice d) {
        try {
            fb = new Fastboot((UsbManager) getSystemService(USB_SERVICE), d);
            log("Terhubung ke fastboot.");
            setStatus("OTG terhubung ke fastboot");
        } catch (IOException e) { log("Gagal: " + e.getMessage()); }
    }

    void fbCmd(String c) {
        if (fb == null) { log("Belum terhubung. Tekan 'Cari fastboot (OTG)'."); return; }
        if (c.trim().isEmpty()) return;
        log("> " + c);
        ex.execute(() -> {
            try { log(fb.cmd(c, 10000, this::log)); }
            catch (IOException e) { log("Error: " + e.getMessage()); }
        });
    }

    void fbFlash() {
        String p = pn();
        if (p == null) return;
        if (fb == null || picked == null || pickedSize <= 0) { log("Hubungkan perangkat dan pilih file dulu."); return; }
        confirm("Flash file ke partisi '" + p + "' di HP target?", () -> ex.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(picked)) {
                setStatus("flash " + p + " ke target ...");
                fb.flash(is, pickedSize, p, this::log);
                setStatus("flash selesai");
            } catch (Exception e) { log("Error: " + e.getMessage()); }
        }));
    }
}
