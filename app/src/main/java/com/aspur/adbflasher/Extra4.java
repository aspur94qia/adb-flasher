package com.aspur.adbflasher;

import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Extra4 {
    static final String HINT = "pilih partisi";
    static final String[] DEFAULT = {"boot", "recovery", "vbmeta", "dtbo", "system", "vendor", "product",
            "userdata", "cache", "persist", "modem", "misc", "super", "logo"};
    static final String[] OPS = {
            "Fastboot: flash ke partisi",
            "Fastboot: boot sementara",
            "Fastboot: erase partisi",
            "Fastboot: matikan verity",
            "Fastboot: flash zip massal",
            "ADB: sideload zip",
            "ADB: install APK",
            "Root: flash ke partisi",
            "Root: backup partisi",
            "Root: pasang zip (recovery)"};
    static ArrayAdapter<String> pa;
    static Spinner ops;

    static ArrayAdapter<String> adapter(MainActivity m, List<String> items) {
        ArrayAdapter<String> ad = new ArrayAdapter<String>(m, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int p, View c, ViewGroup g) {
                TextView v = (TextView) super.getView(p, c, g);
                v.setText(getItem(p) + "  \u25BC");
                v.setTextColor(Ui.TXT);
                v.setTypeface(Typeface.MONOSPACE);
                v.setTextSize(13);
                return v;
            }

            @Override public View getDropDownView(int p, View c, ViewGroup g) {
                TextView v = (TextView) super.getDropDownView(p, c, g);
                v.setTextColor(Ui.TXT);
                v.setBackgroundColor(Ui.BAR);
                v.setTypeface(Typeface.MONOSPACE);
                v.setTextSize(13);
                int pd = m.u.dp(12);
                v.setPadding(pd, pd, pd, pd);
                return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return ad;
    }

    static Spinner spinner(MainActivity m, ArrayAdapter<String> ad) {
        Spinner s = new Spinner(m);
        s.setAdapter(ad);
        s.setBackground(m.u.shape(0xFF010409, Ui.LINE, 6));
        s.setPopupBackgroundDrawable(m.u.shape(Ui.BAR, Ui.LINE, 6));
        s.setPadding(m.u.dp(10), m.u.dp(8), m.u.dp(10), m.u.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, m.u.dp(3), 0, m.u.dp(3));
        s.setLayoutParams(lp);
        return s;
    }

    static void install(MainActivity m) {
        LinearLayout cont = (LinearLayout) m.part.getParent();
        ArrayList<String> init = new ArrayList<>();
        init.add(HINT);
        init.addAll(Arrays.asList(DEFAULT));
        pa = adapter(m, init);
        final Spinner ps = spinner(m, pa);
        ps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (pos > 0) {
                    m.part.setText(pa.getItem(pos));
                    m.part.setSelection(m.part.getText().length());
                    ps.setSelection(0);
                }
            }

            @Override public void onNothingSelected(AdapterView<?> a) { }
        });
        cont.addView(ps, cont.indexOfChild(m.part));

        ops = spinner(m, adapter(m, new ArrayList<>(Arrays.asList(OPS))));
        cont.addView(ops, cont.indexOfChild(m.cmd));

        LinearLayout tmp = new LinearLayout(m);
        tmp.setOrientation(LinearLayout.VERTICAL);
        m.u.pair(tmp, m.u.btn("Deteksi partisi", 2, v -> detect(m)), m.u.btn("START FLASH", 1, v -> start(m)));
        View row = tmp.getChildAt(0);
        tmp.removeView(row);
        cont.addView(row, cont.indexOfChild(m.info) + 1);
    }

    static String capture(String c, boolean su) {
        StringBuilder sb = new StringBuilder();
        try {
            String[] a = su ? new String[]{"su", "-c", c} : new String[]{"sh", "-c", c};
            java.lang.Process pr = new ProcessBuilder(a).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            pr.waitFor();
        } catch (Exception e) { }
        return sb.toString();
    }

    static void addNames(String s, Set<String> set) {
        for (String t : s.split("\\s+")) {
            if (t.matches("[A-Za-z0-9_]{1,32}")) set.add(t);
        }
    }

    static void detect(MainActivity m) {
        final int op = ops.getSelectedItemPosition();
        m.bg(() -> {
            TreeSet<String> set = new TreeSet<>();
            String used = "bawaan";
            if (op <= 4) {
                if (m.fb == null) {
                    m.log("Fastboot belum terhubung, pakai daftar bawaan.");
                } else {
                    final StringBuilder sb = new StringBuilder();
                    m.fb.cmd("getvar:all", 30000, s -> sb.append(s).append('\n'));
                    Matcher x = Pattern.compile("partition-(?:type|size):([A-Za-z0-9_]+):").matcher(sb);
                    while (x.find()) set.add(x.group(1));
                    used = "fastboot";
                }
            } else if (op <= 6) {
                if (m.adb == null) {
                    m.log("ADB belum terhubung, pakai daftar bawaan.");
                } else {
                    addNames(m.adb.run("shell:ls /dev/block/bootdevice/by-name/ 2>/dev/null || ls /dev/block/by-name/ 2>/dev/null"), set);
                    used = "adb";
                }
            } else {
                if (!m.hasRoot) {
                    m.log("Root tidak tersedia, pakai daftar bawaan.");
                } else {
                    addNames(capture("ls /dev/block/bootdevice/by-name/ 2>/dev/null || ls /dev/block/by-name/ 2>/dev/null", true), set);
                    used = "root";
                }
            }
            if (set.isEmpty()) {
                if (!used.equals("bawaan")) m.log("Tidak ada partisi terbaca dari " + used + ", pakai daftar bawaan.");
                set.addAll(Arrays.asList(DEFAULT));
                used = "bawaan";
            }
            final ArrayList<String> list = new ArrayList<>(set);
            m.ui.post(() -> {
                pa.clear();
                pa.add(HINT);
                pa.addAll(list);
            });
            m.log("Partisi terdeteksi (" + used + "): " + list.size() + " buah. Buka dropdown partisi untuk memilih.");
        });
    }

    static void start(MainActivity m) {
        int i = ops.getSelectedItemPosition();
        String p = m.part.getText().toString().trim();
        m.log("== START: " + OPS[i] + (p.isEmpty() ? "" : " | partisi=" + p) + (m.picked != null ? " | file=" + m.pickedName : ""));
        switch (i) {
            case 0: m.fbFlash(); break;
            case 1: m.fbBoot(); break;
            case 2: m.fbErase(); break;
            case 3: Extra3.verity(m); break;
            case 4: Extra3.flashZip(m); break;
            case 5: m.adbSideload(); break;
            case 6: m.adbInstall(); break;
            case 7: m.rootFlash(); break;
            case 8: m.rootBackup(); break;
            case 9: m.rootZip(); break;
            default: break;
        }
    }
}
