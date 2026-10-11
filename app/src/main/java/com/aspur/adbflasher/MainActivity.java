package com.aspur.adbflasher;

import android.app.*;
import android.content.*;
import android.database.Cursor;
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
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final String ACT = "com.aspur.adbflasher.USB";
    interface IOR { void run() throws Exception; }

    Ui u;
    TextView out, info, status;
    EditText part, cmd;
    ScrollView osv;
    LinearLayout[] tabs = new LinearLayout[5];
    Button[] tabBtns = new Button[5];
    Uri picked;
    long pickedSize;
    String pickedName = "";
    Fastboot fb;
    Adb adb;
    boolean hasRoot = false;
    int pendingKind = 0;
    ExecutorService ex = Executors.newSingleThreadExecutor();
    Handler ui = new Handler(Looper.getMainLooper());

    BroadcastReceiver rc = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                if (pendingKind == 0) openFb(d); else openAdbUsb(d);
            } else log("Izin USB ditolak.");
        }
    };

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        u = new Ui(this);
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(Ui.BAR);
        IntentFilter f = new IntentFilter(ACT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rc, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rc, f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BAR);
        bar.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
        bar.addView(u.dot(0xFFFF5F56));
        bar.addView(u.dot(0xFFFFBD2E));
        bar.addView(u.dot(0xFF27C93F));
        TextView title = u.mono("ADB Flasher Full", 13, Ui.TXT);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = u.mono("clear", 12, Ui.DIM);
        clear.setOnClickListener(v -> out.setText(""));
        bar.addView(clear);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setPadding(u.dp(6), u.dp(6), u.dp(6), 0);
        String[] names = {"ROOT", "FASTBOOT", "ADB", "TOOLS", "EXTRA"};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            tabBtns[i] = u.btn(names[i], 2, v -> showTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(u.dp(2), 0, u.dp(2), 0);
            tb.addView(tabBtns[i], lp);
        }
        root.addView(tb, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setPadding(u.dp(10), u.dp(6), u.dp(10), u.dp(6));
        part = u.field("partisi, mis. boot");
        cont.addView(part);
        cmd = u.field("perintah / path / ip:port");
        cont.addView(cmd);
        info = u.mono("file: (belum dipilih)", 11, Ui.DIM);
        cont.addView(info);

        for (int i = 0; i < 5; i++) {
            tabs[i] = new LinearLayout(this);
            tabs[i].setOrientation(LinearLayout.VERTICAL);
        }
        buildRoot(tabs[0]);
        buildFastboot(tabs[1]);
        buildAdb(tabs[2]);
        buildTools(tabs[3]);
        Extra.build(this, tabs[4]);

        ScrollView csv = new ScrollView(this);
        LinearLayout both = new LinearLayout(this);
        both.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 5; i++) both.addView(tabs[i]);
        csv.addView(both);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, 0, 1);
        cl.setMargins(0, u.dp(4), 0, u.dp(6));
        cont.addView(csv, cl);

        LinearLayout term = new LinearLayout(this);
        term.setOrientation(LinearLayout.VERTICAL);
        term.setBackground(u.shape(0xFF010409, Ui.LINE, 8));
        term.setPadding(u.dp(8), u.dp(6), u.dp(8), u.dp(6));
        term.addView(u.mono("TERMINAL", 10, Ui.DIM));
        out = u.mono("", 11, 0xFF3FB950);
        out.setTextIsSelectable(true);
        osv = new ScrollView(this);
        osv.addView(out);
        term.addView(osv, new LinearLayout.LayoutParams(-1, 0, 1));
        cont.addView(term, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(cont, new LinearLayout.LayoutParams(-1, 0, 1));

        status = u.mono(" siap", 11, 0xFFFFFFFF);
        status.setBackgroundColor(0xFF1F6FEB);
        status.setPadding(u.dp(8), u.dp(5), u.dp(8), u.dp(5));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        showTab(1);
        log("ADB Flasher Full 2.0");
        log("Mendeteksi root ...");
        detectRoot();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(rc);
        if (adb != null) adb.close();
    }

    void showTab(int i) {
        for (int j = 0; j < 5; j++) {
            tabs[j].setVisibility(j == i ? View.VISIBLE : View.GONE);
            tabBtns[j].setAlpha(j == i ? 1f : 0.45f);
        }
    }

    void buildRoot(LinearLayout t) {
        u.pair(t, u.btn("Cek root", 2, v -> detectRoot()),
                u.btn("Daftar partisi", 0, v -> sh("ls -l /dev/block/bootdevice/by-name/ | awk '{print $(NF-2), $NF}'", true)));
        u.pair(t, u.btn("Pilih file", 0, v -> pick()), u.btn("Backup partisi", 3, v -> rootBackup()));
        u.pair(t, u.btn("Flash ke partisi", 1, v -> rootFlash()), u.btn("Pasang zip (recovery)", 1, v -> rootZip()));
        u.pair(t, u.btn("Jalankan perintah", 2, v -> sh(cmd.getText().toString(), true)), u.btn("Reboot", 0, v -> sh("reboot", true)));
        u.pair(t, u.btn("Recovery", 0, v -> sh("reboot recovery", true)), u.btn("Bootloader", 0, v -> sh("reboot bootloader", true)));
        u.pair(t, u.btn("EDL", 0, v -> sh("reboot edl", true)), null);
        u.pair(t, u.btn("Backup semua penting", 3, v -> rootBackupAll()), null);
    }

    void buildFastboot(LinearLayout t) {
        u.pair(t, u.btn("Cari fastboot (OTG)", 2, v -> findUsb(0)), u.btn("Info perangkat", 2, v -> fbInfo()));
        u.pair(t, u.btn("getvar all", 0, v -> fbCmd("getvar:all")), u.btn("Pilih file", 0, v -> pick()));
        u.pair(t, u.btn("Flash ke partisi", 1, v -> fbFlash()), u.btn("Boot sementara", 3, v -> fbBoot()));
        u.pair(t, u.btn("Erase partisi", 1, v -> fbErase()), u.btn("Kirim perintah", 2, v -> fbCmd(cmd.getText().toString())));
        u.pair(t, u.btn("Slot A", 0, v -> fbCmd("set_active:a")), u.btn("Slot B", 0, v -> fbCmd("set_active:b")));
        u.pair(t, u.btn("Unlock bootloader", 1, v -> fbLock(true)), u.btn("Lock bootloader", 1, v -> fbLock(false)));
        u.pair(t, u.btn("Reboot", 0, v -> fbCmd("reboot")), u.btn("Reboot bootloader", 0, v -> fbCmd("reboot-bootloader")));
        u.pair(t, u.btn("Reboot recovery", 0, v -> fbCmd("reboot-recovery")), u.btn("Reboot fastbootd", 0, v -> fbCmd("reboot-fastboot")));
        u.pair(t, u.btn("Matikan verity (vbmeta)", 1, v -> fbVerity()), u.btn("Flash zip (.img)", 1, v -> fbFlashZip()));
    }

    void buildAdb(LinearLayout t) {
        u.pair(t, u.btn("Cari ADB (USB)", 2, v -> findUsb(1)), u.btn("Sambung TCP (ip di perintah)", 2, v -> adbTcp()));
        u.pair(t, u.btn("Info perangkat", 2, v -> adbInfo()), u.btn("Shell (perintah)", 2, v -> adbShell(cmd.getText().toString())));
        u.pair(t, u.btn("Pilih file", 0, v -> pick()), u.btn("Install APK", 3, v -> adbInstall()));
        u.pair(t, u.btn("Push ke path", 3, v -> adbPush()), u.btn("Pull dari path", 3, v -> adbPull()));
        u.pair(t, u.btn("Sideload zip", 1, v -> adbSideload()), u.btn("tcpip 5555", 0, v -> adbTcpip()));
        u.pair(t, u.btn("Reboot", 0, v -> adbReboot("")), u.btn("Recovery", 0, v -> adbReboot("recovery")));
        u.pair(t, u.btn("Bootloader", 0, v -> adbReboot("bootloader")), u.btn("Putuskan", 0, v -> adbDisconnect()));
        u.pair(t, u.btn("Logcat", 2, v -> adbLogcat()), u.btn("Screenshot", 3, v -> adbScreenshot()));
        u.pair(t, u.btn("Daftar app", 2, v -> adbApps()), u.btn("Uninstall app", 1, v -> adbUninstall()));
        u.pair(t, u.btn("Lihat folder (ls)", 2, v -> adbLs()), null);
    }

    void buildTools(LinearLayout t) {
        u.pair(t, u.btn("Pilih file", 0, v -> pick()), u.btn("Info file", 2, v -> fileInfo()));
        u.pair(t, u.btn("SHA-256", 3, v -> hash("SHA-256")), u.btn("MD5", 3, v -> hash("MD5")));
        u.pair(t, u.btn("Salin log", 0, v -> copyLog()), u.btn("Bersihkan log", 0, v -> out.setText("")));
        u.pair(t, u.btn("Info boot.img", 2, v -> bootInfo()), null);
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

    void setStatus(String s) { ui.post(() -> status.setText(" " + s)); }

    void bg(IOR r) {
        ex.execute(() -> {
            try { r.run(); }
            catch (Exception e) { log("Error: " + e.getMessage()); setStatus("error"); }
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

    boolean needPicked() {
        if (picked == null || pickedSize <= 0) { log("Error: pilih file dulu."); return true; }
        return false;
    }

    boolean needFb() {
        if (fb == null) { log("Error: belum terhubung fastboot. Tekan 'Cari fastboot (OTG)'."); return true; }
        return false;
    }

    boolean needAdb() {
        if (adb == null) { log("Error: belum terhubung ADB. Tekan 'Cari ADB (USB)' atau 'Sambung TCP'."); return true; }
        return false;
    }

    InputStream openUri(Uri uri) throws IOException {
        InputStream s = getContentResolver().openInputStream(uri);
        if (s == null) throw new IOException("Gagal membuka file");
        return s;
    }

    Fastboot.Opener opener(Uri uri) { return () -> openUri(uri); }

    void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 1);
    }

    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq != 1 || rs != RESULT_OK || d == null) return;
        picked = d.getData();
        pickedName = "file";
        pickedSize = 0;
        try (Cursor c = getContentResolver().query(picked, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                pickedName = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                pickedSize = c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE));
            }
        } catch (Exception e) { log("Gagal baca file: " + e.getMessage()); }
        info.setText("file: " + pickedName + " (" + pickedSize + " byte)");
        info.setTextColor(0xFF79C0FF);
    }

    void copyTo(Uri uri, File f) throws IOException {
        try (InputStream is = openUri(uri); OutputStream os = new FileOutputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = is.read(b)) > 0) os.write(b, 0, n);
        }
    }

    int execQuiet(String command, boolean su) {
        try {
            String[] a = su ? new String[]{"su", "-c", command} : new String[]{"sh", "-c", command};
            java.lang.Process pr = new ProcessBuilder(a).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            while (r.readLine() != null) { }
            return pr.waitFor();
        } catch (Exception e) {
            return -1;
        }
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
        if (su && !hasRoot) { log("Error: root tidak tersedia. Tekan 'Cek root'."); return; }
        log("$ " + command);
        setStatus("menjalankan ...");
        ex.execute(() -> exec(command, su));
    }

    void detectRoot() {
        ex.execute(() -> {
            hasRoot = execQuiet("id", true) == 0;
            log(hasRoot ? "Root: TERSEDIA (fitur tab ROOT aktif)" : "Root: tidak tersedia (pakai tab FASTBOOT/ADB lewat OTG)");
            setStatus(hasRoot ? "root tersedia" : "tanpa root");
            if (hasRoot) ui.post(() -> showTab(0));
        });
    }

    void rootFlash() {
        String p = pn();
        if (p == null || needPicked()) return;
        if (!hasRoot) { log("Error: root tidak tersedia."); return; }
        final Uri uri = picked;
        confirm("Flash file ke partisi '" + p + "'?\nPartisi yang salah bisa membuat HP mati total.", () -> ex.execute(() -> {
            File f = new File(getCacheDir(), "pick.img");
            try { copyTo(uri, f); }
            catch (Exception e) { log("Gagal salin: " + e.getMessage()); return; }
            log("Flash ke " + p + " ...");
            setStatus("flash " + p + " ...");
            exec("dd if=" + f.getPath() + " of=/dev/block/bootdevice/by-name/" + p + " bs=4M && sync", true);
            f.delete();
        }));
    }

    void rootBackup() {
        String p = pn();
        if (p == null) return;
        if (!hasRoot) { log("Error: root tidak tersedia."); return; }
        String dst = "/sdcard/Download/backup_" + p + ".img";
        log("Backup " + p + " ke " + dst);
        setStatus("backup " + p + " ...");
        ex.execute(() -> exec("dd if=/dev/block/bootdevice/by-name/" + p + " of=" + dst + " bs=4M && sync", true));
    }

    void rootZip() {
        if (needPicked()) return;
        if (!hasRoot) { log("Error: root tidak tersedia."); return; }
        final Uri uri = picked;
        confirm("Salin zip lalu reboot ke recovery dan pasang otomatis.\nEksperimental: TWRP/recovery tertentu bisa mengabaikan perintah ini.", () -> bg(() -> {
            File dir = getExternalFilesDir(null);
            File f = new File(dir, "flash.zip");
            log("Menyalin zip ...");
            copyTo(uri, f);
            String p = "/sdcard/Android/data/" + getPackageName() + "/files/flash.zip";
            exec("mkdir -p /cache/recovery && echo '--update_package=" + p + "' > /cache/recovery/command && reboot recovery", true);
        }));
    }

    void findUsb(int kind) {
        pendingKind = kind;
        UsbManager m = (UsbManager) getSystemService(USB_SERVICE);
        for (UsbDevice d : m.getDeviceList().values()) {
            boolean ok = kind == 0 ? Fastboot.isFastboot(d) : Adb.isAdb(d);
            if (ok) {
                log("Ditemukan: " + d.getDeviceName());
                if (m.hasPermission(d)) {
                    if (kind == 0) openFb(d); else openAdbUsb(d);
                } else {
                    m.requestPermission(d, PendingIntent.getBroadcast(this, 0,
                            new Intent(ACT).setPackage(getPackageName()), PendingIntent.FLAG_MUTABLE));
                }
                return;
            }
        }
        log(kind == 0 ? "Tidak ada perangkat fastboot. Masuk mode fastboot di HP target lalu sambung OTG."
                : "Tidak ada perangkat ADB. Aktifkan USB debugging (atau masuk recovery) lalu sambung OTG.");
        setStatus("tidak ada perangkat");
    }

    void openFb(UsbDevice d) {
        try {
            fb = new Fastboot((UsbManager) getSystemService(USB_SERVICE), d);
            log("Terhubung ke fastboot.");
            setStatus("OTG: fastboot terhubung");
        } catch (IOException e) { log("Gagal: " + e.getMessage()); }
    }

    void fbCmd(String c) {
        if (needFb() || c.trim().isEmpty()) return;
        log("> " + c);
        bg(() -> log(fb.cmd(c, 15000, this::log)));
    }

    void fbInfo() {
        if (needFb()) return;
        bg(() -> {
            String[] n = {"product", "serialno", "current-slot", "slot-count", "unlocked", "secure", "max-download-size", "version-bootloader"};
            for (String s : n) log(s + ": " + fb.getvar(s, this::log));
        });
    }

    void fbFlash() {
        String p = pn();
        if (p == null || needFb() || needPicked()) return;
        final Uri uri = picked;
        final long size = pickedSize;
        confirm("Flash file ke partisi '" + p + "' di HP target?", () -> bg(() -> {
            setStatus("flash " + p + " ke target ...");
            fb.flash(opener(uri), size, p, this::log);
            setStatus("flash selesai");
        }));
    }

    void fbBoot() {
        if (needFb() || needPicked()) return;
        final Uri uri = picked;
        final long size = pickedSize;
        confirm("Boot image ini sementara (tanpa menulis ke partisi)?", () -> bg(() -> fb.boot(opener(uri), size, this::log)));
    }

    void fbErase() {
        String p = pn();
        if (p == null || needFb()) return;
        confirm("HAPUS partisi '" + p + "' di HP target?", () -> bg(() -> log(fb.cmd("erase:" + p, 60000, this::log))));
    }

    void fbLock(boolean unlock) {
        if (needFb()) return;
        String c = unlock ? "flashing unlock" : "flashing lock";
        confirm("Perintah '" + c + "' menghapus SEMUA data di HP target. Lanjut?", () -> bg(() -> log(fb.cmd(c, 60000, this::log))));
    }

    void openAdbUsb(UsbDevice d) {
        bg(() -> {
            if (adb != null) adb.close();
            adb = Adb.usb((UsbManager) getSystemService(USB_SERVICE), d);
            adb.connect(getFilesDir(), this::log);
            log("Terhubung ADB: " + adb.banner);
            setStatus("OTG: ADB terhubung");
        });
    }

    void adbTcp() {
        String t = cmd.getText().toString().trim();
        if (t.isEmpty()) { log("Error: isi ip[:port] di kolom perintah."); return; }
        bg(() -> {
            int i = t.indexOf(':');
            String host = i < 0 ? t : t.substring(0, i);
            int port = i < 0 ? 5555 : Integer.parseInt(t.substring(i + 1));
            if (adb != null) adb.close();
            log("Menyambung " + host + ":" + port + " ...");
            adb = Adb.tcp(host, port);
            adb.connect(getFilesDir(), this::log);
            log("Terhubung ADB: " + adb.banner);
            setStatus("TCP: ADB terhubung");
        });
    }

    void adbDisconnect() {
        if (adb != null) { adb.close(); adb = null; }
        log("ADB diputuskan.");
        setStatus("siap");
    }

    void adbShell(String c) {
        if (needAdb() || c.trim().isEmpty()) return;
        log("$ " + c);
        bg(() -> log(adb.run("shell:" + c).trim()));
    }

    void adbInfo() {
        if (needAdb()) return;
        bg(() -> log(adb.run("shell:getprop ro.product.model; getprop ro.product.device; getprop ro.build.version.release; getprop ro.serialno").trim()));
    }

    void adbInstall() {
        if (needAdb() || needPicked()) return;
        final Uri uri = picked;
        final long size = pickedSize;
        bg(() -> {
            try (InputStream is = openUri(uri)) { adb.push(is, size, "/data/local/tmp/_inst.apk", this::log); }
            log(adb.run("shell:pm install -r /data/local/tmp/_inst.apk; rm -f /data/local/tmp/_inst.apk").trim());
        });
    }

    void adbPush() {
        String path = cmd.getText().toString().trim();
        if (needAdb() || needPicked()) return;
        if (path.isEmpty()) { log("Error: isi path tujuan di kolom perintah, mis. /sdcard/Download/"); return; }
        if (path.endsWith("/")) path = path + pickedName;
        final String dst = path;
        final Uri uri = picked;
        final long size = pickedSize;
        bg(() -> {
            try (InputStream is = openUri(uri)) { adb.push(is, size, dst, this::log); }
            log("Push selesai: " + dst);
        });
    }

    void adbPull() {
        String path = cmd.getText().toString().trim();
        if (needAdb()) return;
        if (path.isEmpty()) { log("Error: isi path sumber di kolom perintah."); return; }
        bg(() -> {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) name = "pull.bin";
            File dst = new File(getExternalFilesDir(null), name);
            long n;
            try (OutputStream os = new FileOutputStream(dst)) { n = adb.pull(path, os, this::log); }
            log("Pull selesai (" + n + " byte): " + dst.getPath());
        });
    }

    void adbSideload() {
        if (needAdb() || needPicked()) return;
        final Uri uri = picked;
        final long size = pickedSize;
        confirm("HP target harus di mode recovery > 'ADB sideload'. Lanjut kirim zip?", () -> bg(() -> {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new IOException("Gagal membuka file");
            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                FileChannel ch = fis.getChannel();
                setStatus("sideload ...");
                adb.sideload(ch, size, this::log);
            } finally {
                pfd.close();
            }
        }));
    }

    void adbReboot(String target) {
        if (needAdb()) return;
        bg(() -> {
            adb.fire("reboot:" + target);
            log("Perintah reboot dikirim.");
            adb.close();
            adb = null;
        });
    }

    void adbTcpip() {
        if (needAdb()) return;
        bg(() -> {
            adb.fire("tcpip:5555");
            log("tcpip:5555 dikirim. Sambung ulang lewat 'Sambung TCP' (ip:5555).");
        });
    }

    void hash(String algo) {
        if (picked == null) { log("Error: pilih file dulu."); return; }
        final Uri uri = picked;
        bg(() -> {
            MessageDigest md = MessageDigest.getInstance(algo);
            try (InputStream is = openUri(uri)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = is.read(b)) > 0) md.update(b, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            log(algo + ": " + sb);
        });
    }

    void fileInfo() {
        if (picked == null) { log("Error: pilih file dulu."); return; }
        final Uri uri = picked;
        bg(() -> {
            byte[] m = new byte[4];
            try (InputStream is = openUri(uri)) { is.read(m); }
            boolean sparse = (m[0] & 0xff) == 0x3a && (m[1] & 0xff) == 0xff && (m[2] & 0xff) == 0x26 && (m[3] & 0xff) == 0xed;
            log("nama: " + pickedName);
            log("ukuran: " + pickedSize + " byte");
            log("sparse: " + (sparse ? "ya" : "tidak"));
            log("kelipatan 4096: " + (pickedSize % 4096 == 0 ? "ya" : "tidak"));
        });
    }

    void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("log", out.getText()));
        Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show();
    }

    void rootBackupAll() {
        if (!hasRoot) { log("Error: root tidak tersedia."); return; }
        String d = "/sdcard/Download/backup_all";
        String c = "mkdir -p " + d + "; for p in boot recovery vbmeta dtbo persist modem fsg modemst1 modemst2 misc; do if [ -e /dev/block/bootdevice/by-name/$p ]; then echo backup $p; dd if=/dev/block/bootdevice/by-name/$p of=" + d + "/$p.img bs=4M 2>&1 | tail -1; fi; done; sync; ls -l " + d;
        log("$ backup semua partisi penting ke " + d);
        setStatus("backup semua ...");
        ex.execute(() -> exec(c, true));
    }

    void fbVerity() {
        String p = part.getText().toString().trim();
        if (p.isEmpty()) p = "vbmeta";
        if (!p.matches("[A-Za-z0-9_]{1,32}")) { log("Error: nama partisi tidak valid."); return; }
        if (needFb() || needPicked()) return;
        final String pt = p;
        final Uri uri = picked;
        final long size = pickedSize;
        confirm("Flash '" + pt + "' dengan verity dan verifikasi dimatikan?\nPakai file vbmeta dari ROM yang sama dengan HP target.", () -> bg(() -> {
            if (size > 65536) throw new IOException("File terlalu besar untuk vbmeta (" + size + " byte)");
            byte[] b = new byte[(int) size];
            int off = 0;
            try (InputStream is = openUri(uri)) {
                while (off < b.length) {
                    int n = is.read(b, off, b.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            if (off < 124 || b[0] != 'A' || b[1] != 'V' || b[2] != 'B' || b[3] != '0') throw new IOException("Bukan vbmeta (header AVB0 tidak ada)");
            b[123] = 3;
            setStatus("flash " + pt + " (verity off) ...");
            fb.download(b.length, b, null, 0, 0, null, this::log);
            log(fb.cmd("flash:" + pt, 60000, this::log));
            setStatus("verity dimatikan");
        }));
    }

    void fbFlashZip() {
        if (needFb() || needPicked()) return;
        final Uri uri = picked;
        confirm("Flash SEMUA file .img di dalam zip ke partisi sesuai nama file, berurutan?\nPartisi yang salah bisa membuat HP target mati total.", () -> bg(() -> {
            int count = 0;
            try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(openUri(uri))) {
                java.util.zip.ZipEntry e;
                while ((e = zi.getNextEntry()) != null) {
                    String nm = e.getName();
                    nm = nm.substring(nm.lastIndexOf('/') + 1);
                    if (e.isDirectory() || !nm.endsWith(".img")) continue;
                    final String pt = nm.substring(0, nm.length() - 4);
                    if (!pt.matches("[A-Za-z0-9_]{1,32}")) { log("Lewati " + nm); continue; }
                    final File tmp = new File(getCacheDir(), "zip_part.img");
                    try (OutputStream os = new FileOutputStream(tmp)) {
                        byte[] b = new byte[65536];
                        int r;
                        while ((r = zi.read(b)) > 0) os.write(b, 0, r);
                    }
                    log("== " + pt + " (" + tmp.length() + " byte)");
                    setStatus("flash " + pt + " ...");
                    fb.flash(() -> new FileInputStream(tmp), tmp.length(), pt, this::log);
                    tmp.delete();
                    count++;
                }
            }
            log("Selesai, " + count + " partisi di-flash.");
            setStatus("flash zip selesai");
        }));
    }

    void adbLogcat() {
        if (needAdb()) return;
        bg(() -> log(adb.run("shell:logcat -d -t 300").trim()));
    }

    void adbScreenshot() {
        if (needAdb()) return;
        bg(() -> {
            adb.run("shell:screencap -p /data/local/tmp/_s.png");
            File dst = new File(getExternalFilesDir(null), "screenshot_" + System.currentTimeMillis() + ".png");
            long n;
            try (OutputStream os = new FileOutputStream(dst)) { n = adb.pull("/data/local/tmp/_s.png", os, this::log); }
            adb.run("shell:rm -f /data/local/tmp/_s.png");
            log("Screenshot (" + n + " byte): " + dst.getPath());
        });
    }

    void adbApps() {
        if (needAdb()) return;
        bg(() -> log(adb.run("shell:pm list packages -3").trim()));
    }

    void adbUninstall() {
        String pk = cmd.getText().toString().trim();
        if (needAdb()) return;
        if (!pk.matches("[A-Za-z0-9_.]{3,200}")) { log("Error: isi nama paket di kolom perintah, mis. com.contoh.app"); return; }
        confirm("Uninstall '" + pk + "' dari HP target?", () -> bg(() -> log(adb.run("shell:pm uninstall --user 0 " + pk).trim())));
    }

    void adbLs() {
        if (needAdb()) return;
        String path = cmd.getText().toString().trim();
        if (path.isEmpty()) path = "/sdcard";
        final String pth = path;
        bg(() -> log(adb.run("shell:ls -la " + pth).trim()));
    }

    static String cstr(byte[] b, int o, int len) {
        int e = o;
        while (e < o + len && b[e] != 0) e++;
        return new String(b, o, e - o);
    }

    void bootInfo() {
        if (picked == null) { log("Error: pilih file dulu."); return; }
        final Uri uri = picked;
        bg(() -> {
            byte[] h = new byte[1632];
            int off = 0;
            try (InputStream is = openUri(uri)) {
                while (off < h.length) {
                    int n = is.read(h, off, h.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            if (off < 640 || !new String(h, 0, 8).equals("ANDROID!")) { log("Error: bukan boot.img Android (header ANDROID! tidak ada)."); return; }
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            log("kernel size  : " + (b.getInt(8) & 0xffffffffL) + " byte");
            log("kernel addr  : 0x" + Long.toHexString(b.getInt(12) & 0xffffffffL));
            log("ramdisk size : " + (b.getInt(16) & 0xffffffffL) + " byte");
            log("ramdisk addr : 0x" + Long.toHexString(b.getInt(20) & 0xffffffffL));
            log("second size  : " + (b.getInt(24) & 0xffffffffL) + " byte");
            log("tags addr    : 0x" + Long.toHexString(b.getInt(32) & 0xffffffffL));
            log("page size    : " + b.getInt(36));
            log("header ver   : " + b.getInt(40));
            log("name         : " + cstr(h, 48, 16));
            log("cmdline      : " + cstr(h, 64, 512));
            if (off >= 1632) {
                String x = cstr(h, 608, 1024);
                if (!x.isEmpty()) log("cmdline+     : " + x);
            }
        });
    }
}
