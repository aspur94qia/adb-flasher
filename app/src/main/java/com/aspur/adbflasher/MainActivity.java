package com.aspur.adbflasher;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final String ACT = "com.aspur.adbflasher.USB";
    TextView out, info;
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

    Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        IntentFilter f = new IntentFilter(ACT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rc, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rc, f);

        int p = (int) (10 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        part = new EditText(this);
        part.setHint("nama partisi, mis. boot");
        part.setSingleLine(true);
        root.addView(part);
        cmd = new EditText(this);
        cmd.setHint("perintah");
        cmd.setSingleLine(true);
        root.addView(cmd);
        info = new TextView(this);
        info.setText("File: (belum dipilih)");
        root.addView(info);

        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        rootBox.addView(btn("Cek root", v -> sh("id", true)));
        rootBox.addView(btn("Daftar partisi", v -> sh("ls -l /dev/block/bootdevice/by-name/ | awk '{print $(NF-2), $NF}'", true)));
        rootBox.addView(btn("Pilih file image", v -> pick()));
        rootBox.addView(btn("Flash file ke partisi", v -> rootFlash()));
        rootBox.addView(btn("Backup partisi", v -> rootBackup()));
        rootBox.addView(btn("Reboot sistem", v -> sh("reboot", true)));
        rootBox.addView(btn("Reboot recovery", v -> sh("reboot recovery", true)));
        rootBox.addView(btn("Reboot bootloader", v -> sh("reboot bootloader", true)));
        rootBox.addView(btn("Jalankan perintah (root)", v -> sh(cmd.getText().toString(), true)));

        fbBox = new LinearLayout(this);
        fbBox.setOrientation(LinearLayout.VERTICAL);
        fbBox.addView(btn("Cari perangkat fastboot (OTG)", v -> findUsb()));
        fbBox.addView(btn("getvar all", v -> fbCmd("getvar:all")));
        fbBox.addView(btn("Pilih file image", v -> pick()));
        fbBox.addView(btn("Flash file ke partisi", v -> fbFlash()));
        fbBox.addView(btn("Reboot", v -> fbCmd("reboot")));
        fbBox.addView(btn("Reboot bootloader", v -> fbCmd("reboot-bootloader")));
        fbBox.addView(btn("Kirim perintah fastboot", v -> fbCmd(cmd.getText().toString())));
        fbBox.addView(btn("Perintah shell biasa (tanpa root)", v -> sh(cmd.getText().toString(), false)));

        ScrollView csv = new ScrollView(this);
        LinearLayout both = new LinearLayout(this);
        both.setOrientation(LinearLayout.VERTICAL);
        both.addView(rootBox);
        both.addView(fbBox);
        csv.addView(both);
        root.addView(csv, new LinearLayout.LayoutParams(-1, 0, 1));

        out = new TextView(this);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setTextSize(11);
        out.setTextIsSelectable(true);
        osv = new ScrollView(this);
        osv.addView(out);
        root.addView(osv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        rootBox.setVisibility(BuildConfig.ROOT_MODE ? View.VISIBLE : View.GONE);
        fbBox.setVisibility(BuildConfig.ROOT_MODE ? View.GONE : View.VISIBLE);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(rc);
    }

    void log(String s) {
        ui.post(() -> {
            out.append(s + "\n");
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
            return code;
        } catch (Exception e) {
            log("Error: " + e.getMessage());
            return -1;
        }
    }

    void sh(String command, boolean su) {
        if (command.trim().isEmpty()) return;
        log("$ " + command);
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
        info.setText("File: " + name + " (" + pickedSize + " byte)");
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
            exec("dd if=" + f.getPath() + " of=/dev/block/bootdevice/by-name/" + p + " bs=4M && sync", true);
            f.delete();
        }));
    }

    void rootBackup() {
        String p = pn();
        if (p == null) return;
        String dst = "/sdcard/Download/backup_" + p + ".img";
        log("Backup " + p + " ke " + dst);
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
    }

    void openFb(UsbDevice d) {
        try {
            fb = new Fastboot((UsbManager) getSystemService(USB_SERVICE), d);
            log("Terhubung ke fastboot.");
        } catch (IOException e) { log("Gagal: " + e.getMessage()); }
    }

    void fbCmd(String c) {
        if (fb == null) { log("Belum terhubung. Tekan 'Cari perangkat fastboot'."); return; }
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
                fb.flash(is, pickedSize, p, this::log);
            } catch (Exception e) { log("Error: " + e.getMessage()); }
        }));
    }
}
