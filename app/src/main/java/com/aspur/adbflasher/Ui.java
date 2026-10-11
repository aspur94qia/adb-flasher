package com.aspur.adbflasher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

class Ui {
    static final int BG = 0xFF0D1117, BAR = 0xFF161B22, LINE = 0xFF30363D, TXT = 0xFFC9D1D9, DIM = 0xFF8B949E;
    final Context ctx;

    Ui(Context c) { ctx = c; }

    int dp(int v) { return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f); }

    GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    TextView mono(String t, int size, int color) {
        TextView v = new TextView(ctx);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    View dot(int color) {
        View v = new View(ctx);
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
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(TXT);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setBackground(shape(0xFF010409, LINE, 6));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(3));
        e.setLayoutParams(lp);
        return e;
    }

    Button btn(String t, int kind, View.OnClickListener l) {
        int fill = 0xFF21262D, stroke = LINE, text = TXT;
        if (kind == 1) { fill = 0xFF2D1215; stroke = 0xFFF85149; text = 0xFFFF7B72; }
        else if (kind == 2) { fill = 0xFF0D2240; stroke = 0xFF58A6FF; text = 0xFF79C0FF; }
        else if (kind == 3) { fill = 0xFF0F2A17; stroke = 0xFF3FB950; text = 0xFF56D364; }
        Button b = new Button(ctx);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(text);
        b.setTextSize(12);
        b.setTypeface(Typeface.MONOSPACE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), shape(fill, stroke, 8), null));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6), dp(10), dp(6), dp(10));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    void pair(LinearLayout p, Button a, Button b) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, -2, 1);
        la.setMargins(0, dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, -2, 1);
        lb.setMargins(dp(3), dp(3), 0, dp(3));
        r.addView(a, la);
        if (b != null) r.addView(b, lb); else r.addView(new View(ctx), lb);
        p.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }
}
