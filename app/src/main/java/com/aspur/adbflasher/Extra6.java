package com.aspur.adbflasher;

import android.content.res.ColorStateList;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Extra6 {
    static class Row {
        String name;
        Uri uri;
        long size;
        String fname = "";
        boolean checked = false;
        CheckBox cb;
        TextView fileTv, statusTv;
    }

    static final Map<String, Row> known = new LinkedHashMap<>();
    static final ArrayList<Row> shown = new ArrayList<>();
    static LinearLayout box;
    static TextView card;
    static Fastboot cardFb;
    static final Pattern PCT = Pattern.compile("^Kirim (\\d+)%$");

    static void install(MainActivity m) {
        LinearLayout t = m.tabs[1];
        card = m.u.mono("perangkat: (belum terhubung)", 11, Ui.TXT);
        card.setBackground(m.u.shape(0xFF161B22, Ui.LINE, 6));
        card.setPadding(m.u.dp(8), m.u.dp(6), m.u.dp(8), m.u.dp(6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(0, m.u.dp(2), 0, m.u.dp(6));
        t.addView(card, 0, clp);

        box = new LinearLayout(m);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(m.u.shape(0xFF010409, Ui.LINE, 6));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(0, 0, 0, m.u.dp(6));
        t.addView(box, 1, blp);

        LinearLayout tmp = new LinearLayout(m);
        tmp.setOrientation(LinearLayout.VERTICAL);
        m.u.pair(tmp, m.u.btn("Muat partisi", 2, v -> load(m)), m.u.btn("Kosongkan file", 0, v -> clearAll(m)));
        m.u.pair(tmp, m.u.btn("FLASH TERPILIH", 1, v -> flashSelected(m)), m.u.btn("Reboot", 0, v -> m.fbCmd("reboot")));
        View a = tmp.getChildAt(0);
        View b = tmp.getChildAt(1);
        tmp.removeAllViews();
        t.addView(a, 2);
        t.addView(b, 3);

        rebuild(m, Arrays.asList(Extra4.DEFAULT));

        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (m.isDestroyed()) return;
                if (m.fb != cardFb && Extra2.depth == 0) {
                    cardFb = m.fb;
                    if (cardFb == null) {
                        card.setText("perangkat: (belum terhubung)");
                    } else {
                        card.setText("perangkat: membaca ...");
                        fetchCard(m);
                    }
                }
                m.ui.postDelayed(this, 2000);
            }
        };
        m.ui.post(tick);

        rail(m);
    }

    static String clean(String s) {
        s = s.trim();
        return s.isEmpty() || s.startsWith("FAIL") ? "-" : s;
    }

    static void fetchCard(MainActivity m) {
        m.bg(() -> {
            if (m.fb == null) return;
            String pr = clean(m.fb.getvar("product", s -> { }));
            String sl = clean(m.fb.getvar("current-slot", s -> { }));
            String un = clean(m.fb.getvar("unlocked", s -> { }));
            final String t = "\u25CF " + pr + " | slot " + sl + " | unlocked: " + un;
            m.ui.post(() -> card.setText(t));
        });
    }

    static void rail(MainActivity m) {
        try {
            LinearLayout tb = (LinearLayout) m.tabBtns[0].getParent();
            if (!(tb.getParent() instanceof HorizontalScrollView)) {
                m.log("Rel ikon dilewati (tata letak berbeda).");
                return;
            }
            HorizontalScrollView hs = (HorizontalScrollView) tb.getParent();
            LinearLayout root = (LinearLayout) hs.getParent();
            LinearLayout cont = (LinearLayout) m.part.getParent();
            if (cont.getParent() != root) {
                m.log("Rel ikon dilewati (tata letak berbeda).");
                return;
            }
            int idx = root.indexOfChild(hs);
            hs.removeView(tb);
            root.removeView(hs);
            root.removeView(cont);
            tb.setOrientation(LinearLayout.VERTICAL);
            tb.setPadding(m.u.dp(4), m.u.dp(6), m.u.dp(4), m.u.dp(6));
            for (Button bt : m.tabBtns) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, m.u.dp(2), 0, m.u.dp(2));
                bt.setLayoutParams(lp);
                bt.setPadding(m.u.dp(2), m.u.dp(12), m.u.dp(2), m.u.dp(12));
                bt.setMinWidth(0);
                bt.setMinimumWidth(0);
                bt.setTextSize(10);
            }
            ScrollView rs = new ScrollView(m);
            rs.setBackgroundColor(0xFF010409);
            rs.addView(tb, new FrameLayout.LayoutParams(-1, -2));
            LinearLayout row = new LinearLayout(m);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(rs, new LinearLayout.LayoutParams(m.u.dp(76), -1));
            row.addView(cont, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(row, idx, new LinearLayout.LayoutParams(-1, 0, 1));
        } catch (Exception e) {
            m.log("Rel ikon gagal: " + e.getMessage());
        }
    }

    static void rebuild(MainActivity m, Collection<String> names) {
        box.removeAllViews();
        shown.clear();
        LinearLayout head = new LinearLayout(m);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setBackgroundColor(Ui.BAR);
        head.setPadding(m.u.dp(6), m.u.dp(4), m.u.dp(6), m.u.dp(4));
        head.addView(new View(m), new LinearLayout.LayoutParams(m.u.dp(32), 1));
        head.addView(m.u.mono("nama", 11, Ui.DIM), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(m.u.mono("file (ketuk)", 11, Ui.DIM), new LinearLayout.LayoutParams(m.u.dp(92), -2));
        head.addView(m.u.mono("status", 11, Ui.DIM), new LinearLayout.LayoutParams(m.u.dp(52), -2));
        box.addView(head);
        for (String n : names) {
            Row r = known.get(n);
            if (r == null) {
                r = new Row();
                r.name = n;
                known.put(n, r);
            }
            shown.add(r);
            box.addView(rowView(m, r));
        }
    }

    static View rowView(MainActivity m, Row r) {
        final Row rr = r;
        LinearLayout w = new LinearLayout(m);
        w.setOrientation(LinearLayout.VERTICAL);
        LinearLayout l = new LinearLayout(m);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(m.u.dp(6), 0, m.u.dp(6), 0);

        r.cb = new CheckBox(m);
        r.cb.setButtonTintList(ColorStateList.valueOf(0xFF58A6FF));
        r.cb.setChecked(r.checked);
        r.cb.setOnCheckedChangeListener((bv, c) -> rr.checked = c);
        l.addView(r.cb, new LinearLayout.LayoutParams(m.u.dp(32), m.u.dp(40)));

        l.addView(m.u.mono(r.name, 12, Ui.TXT), new LinearLayout.LayoutParams(0, -2, 1));

        r.fileTv = m.u.mono(r.fname.isEmpty() ? "-" : r.fname, 11, r.fname.isEmpty() ? Ui.DIM : 0xFF79C0FF);
        r.fileTv.setSingleLine(true);
        r.fileTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        r.fileTv.setPadding(0, m.u.dp(10), 0, m.u.dp(10));
        r.fileTv.setOnClickListener(v -> assign(m, rr));
        r.fileTv.setOnLongClickListener(v -> {
            rr.uri = null;
            rr.size = 0;
            rr.fname = "";
            rr.fileTv.setText("-");
            rr.fileTv.setTextColor(Ui.DIM);
            rr.cb.setChecked(false);
            return true;
        });
        l.addView(r.fileTv, new LinearLayout.LayoutParams(m.u.dp(92), -2));

        r.statusTv = m.u.mono("-", 11, Ui.DIM);
        r.statusTv.setGravity(Gravity.END);
        l.addView(r.statusTv, new LinearLayout.LayoutParams(m.u.dp(52), -2));

        w.addView(l);
        View div = new View(m);
        div.setBackgroundColor(Ui.LINE);
        w.addView(div, new LinearLayout.LayoutParams(-1, 1));
        return w;
    }

    static void assign(MainActivity m, Row r) {
        if (m.picked == null || m.pickedSize <= 0) {
            m.log("Error: pilih file dulu (tombol Pilih file), lalu ketuk kolom file di baris.");
            return;
        }
        r.uri = m.picked;
        r.size = m.pickedSize;
        r.fname = m.pickedName;
        r.fileTv.setText(r.fname);
        r.fileTv.setTextColor(0xFF79C0FF);
        r.cb.setChecked(true);
        m.log("Baris " + r.name + " <- " + r.fname + " (" + r.size + " byte)");
    }

    static void clearAll(MainActivity m) {
        ArrayList<String> names = new ArrayList<>();
        for (Row r : shown) {
            r.uri = null;
            r.size = 0;
            r.fname = "";
            r.checked = false;
            names.add(r.name);
        }
        rebuild(m, names);
        m.log("Semua file di tabel dikosongkan.");
    }

    static void load(MainActivity m) {
        m.bg(() -> {
            TreeSet<String> set = new TreeSet<>();
            String used = "bawaan";
            if (m.fb != null) {
                final StringBuilder sb = new StringBuilder();
                m.fb.cmd("getvar:all", 30000, s -> sb.append(s).append('\n'));
                Matcher x = Pattern.compile("partition-(?:type|size):([A-Za-z0-9_]+):").matcher(sb);
                while (x.find()) set.add(x.group(1));
                if (!set.isEmpty()) used = "fastboot";
                else m.log("Bootloader tidak melaporkan partisi, pakai daftar bawaan.");
            } else {
                m.log("Fastboot belum terhubung, pakai daftar bawaan.");
            }
            if (set.isEmpty()) set.addAll(Arrays.asList(Extra4.DEFAULT));
            final ArrayList<String> list = new ArrayList<>(set);
            m.ui.post(() -> rebuild(m, list));
            m.log("Tabel partisi dimuat (" + used + "): " + list.size() + " baris.");
        });
    }

    static void setStatus(MainActivity m, Row r, String t, int color) {
        m.ui.post(() -> {
            if (r.statusTv != null) {
                r.statusTv.setText(t);
                r.statusTv.setTextColor(color);
            }
        });
    }

    static void flashSelected(MainActivity m) {
        if (m.needFb()) return;
        final ArrayList<Row> sel = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (Row r : shown) {
            if (!r.checked) continue;
            if (r.uri == null) {
                m.log("Error: baris " + r.name + " tercentang tapi belum ada file.");
                return;
            }
            sel.add(r);
            sb.append("\n- ").append(r.name).append(" <- ").append(r.fname);
        }
        if (sel.isEmpty()) {
            m.log("Error: centang baris dan pasang file dulu.");
            return;
        }
        m.confirm("Flash " + sel.size() + " partisi ke HP target, berurutan:" + sb
                + "\nPartisi yang salah bisa membuat HP target mati total.", () -> m.bg(() -> {
            int ok = 0;
            for (Row r : sel) {
                final Row rr = r;
                final Uri u = r.uri;
                setStatus(m, rr, "...", 0xFF79C0FF);
                Fastboot.Sink k = s -> {
                    m.log(s);
                    Matcher x = PCT.matcher(s);
                    if (x.matches()) setStatus(m, rr, x.group(1) + "%", 0xFF79C0FF);
                };
                m.log("== " + r.name + " <- " + r.fname);
                try {
                    m.fb.flash(() -> m.openUri(u), r.size, r.name, k);
                    setStatus(m, rr, "ok", 0xFF56D364);
                    ok++;
                } catch (Exception e) {
                    setStatus(m, rr, "gagal", 0xFFFF7B72);
                    throw e;
                }
            }
            m.log("Selesai: " + ok + "/" + sel.size() + " partisi di-flash.");
        }));
    }
}
